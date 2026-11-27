# TLA+ specs of the stream layer

Design-level models of the pieces of the stream layer and of `AsyncCall`'s run whose correctness
rests on an interleaving argument. Each spec is a PlusCal algorithm in which **one label is one atomic step
of the implementation** (a volatile read or write, a compare-and-set, a monitor enter / exit, a
promise completion), checked exhaustively with TLC for a small configuration.

They complement the other two verification layers (see `CLAUDE.md`, "Verification"): the
Lincheck model checks run the real classes but only a sample of interleavings and no liveness;
these check every interleaving of a model, deadlock freedom and termination included.

| Spec | Models | Class |
|---|---|---|
| `ZioCursor.tla` | the demand hand-over between `next()`, the stream's one fiber and `cancel()` | `ZioCursor` (ws-client-zio, `ZioInterop.scala`) |
| `CursorDrain.tla` | the work-in-progress drain loop of a published run | `CursorSubscription` (ws-client-core, `stream/impl/CursorPublisher.scala`) |
| `TransformChain.tla` | two chained transform stages between a source and a consumer | `TransformPublisher` (ws-client-core, `stream/TransformPublisher.scala`) |
| `PreflightHandover.tla` | a preflight cancelled by its caller: the queued start, the pull's completion and `cancel()` | `NeutralRunner.startPreflight` (ws-client-core, `stream/impl/NeutralRunner.scala`) |
| `SubscriberCursor.tla` | a `Flow.Subscriber` consumed as a cursor: the lock-free work-in-progress loop that serializes `request` / `cancel`, the demand, the `delivering` window of a cancel | `SubscriberCursor` (ws-client-core, `stream/impl/SubscriberCursor.scala`) |
| `BroadcastHub.tla` | a hot fan-out of one upstream run: the ring buffer trimmed by the slowest consumer, registration, the first terminal event, `shutdown()`, `cancel()`, the refill pull | `AsyncBroadcastHub` (ws-client-core, `stream/AsyncBroadcastHub.scala`) |
| `CallTimeout.tla` | a timeout in the run of an `AsyncCall`: the race between the child run, the timer and a cancel of the parent, the `LateScope`, the loser's late outcome | `CallFiber.startTimeout` / `Suspension` (ws-client-core, `stream/impl/CallRunner.scala`) |
| `CallTraverse.tla` | `traverse` in the run of an `AsyncCall`: children at most `Parallelism` at once, the first failure cancels the others and waits for them, a cancel of the parent | `CallFiber.Traversal` (ws-client-core, `stream/impl/CallRunner.scala`) |

## Running

```
formal/tla/check.sh --fetch        # every spec, config and mutant; exits non-zero on a mismatch
formal/tla/check.sh --slow         # ... and the slow checks too (a minute or more each)
formal/tla/check.sh --list         # the checks and their expected outcomes
formal/tla/check.sh ZioCursor CursorDrainM3
```

Needs Java 11+, `patch`, and the TLA+ tools jar - not part of the repository: `--fetch`
downloads the pinned release (`tla2tools.jar` v1.7.4, TLC 2.19, SHA-256
`936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88`) into `target/`, or point
`TLA2TOOLS_JAR` at a copy. Everything is written below `formal/tla/target/` (git-ignored). The
default run takes about 11 minutes on 20 cores (`TransformChainOuts` takes two of them), 16 with
`--slow` (the two-consumer hub configurations that add an outside `shutdown()` / `cancel()`,
1.5 and 2.2 million states); TLC keeps its state queue on disk (a few hundred MB for the largest
config), and a run whose state files grow beyond `TLC_MAX_MB` (default 700) is stopped and
reported as a failure.

The `.tla` files hold only the PlusCal source: `check.sh` translates a copy (`pcal.trans`)
before it runs TLC, so the algorithm and its translation cannot drift apart.

## What is checked

### ZioCursor

A consumer making up to `MaxNext` calls of `next()` (one at a time), the fiber, and any number
of threads calling `cancel()` at any time.

- `NoReadAhead` - the fiber never pulls ahead of the demand
- `EndAfterScope` - the stream's end or failure is signalled after its scope closed
- `StoppedOnlyByCancel` - only a cancel ends a run with `None` before the stream's own end
- `NoChunkLost` - without a cancel, every chunk the stream produced reaches the consumer
- `CancelCompletes` - the `cancel()` that closed the cursor leaves no `next()` outstanding
- `AllDone`, `ScopeCloses` (liveness) - every `next()` completes, the fiber ends, the scope
  closes

`CancelCompletes` is deliberately about the cancel that closed the cursor. TLC rejected the
stronger "no `next()` is outstanding once any `cancel()` returned" three times, each a
legitimate behavior of the class:

1. a `next()` between writing `asked` and its signal sees `Closed` there and completes itself;
2. a `cancel()` that arrives after the run ended by itself returns at once - the outstanding
   `next()` then completes with the run's own end or failure, not with `None`;
3. a second, concurrent `cancel()` returns at once, while the first has not yet completed the
   outstanding `next()`.

### CursorDrain

The start task, the workers of the run's `ec`, an asynchronous cursor result, and a subscriber
that requests (also `n <= 0` with `BadRequests`) and cancels from its own thread and from inside
`onSubscribe` / `onNext`; the loop may hand itself over to a fresh `ec` task (`MaxYields`).

- `Exclusive` - one loop at a time, one signal at a time (Reactive Streams 1.3, 2.7), never two
  queued loop tasks
- `WithinDemand` - never more elements than requested (1.1)
- `NoStall` - no lost wake-up: when nothing runs and nothing is scheduled, the run is over or
  its next result waits for demand
- `CancelSettles` - a cancel reaches the run and drops the subscriber (3.5, 3.13)
- `Rule39` - a non-positive request is answered with `onError`, unless the run was over
- `AllDone` (liveness)

### TransformChain

The source's thread (`onNext` / `onComplete`) and the consumer's (`request` / `cancel`, also
from inside `onNext` on whatever thread delivers); a transformer yields one of `Outs` outputs
per input and, with `EarlyDone`, may end the stream early (the `[DONE]` event).

- `WithinDemand` - never more elements than requested (1.1)
- `Flushed` - at the end no monitor is held, no upstream call is owed, every cancel was sent
- `CancelReachesSource` - a cancel reaches the source, unless the source was through already
- `NoStall` - no lost request: the consumer's demand is met, or the stream is over
- `AllDone` (liveness), and no deadlock

`Legacy = TRUE` is the design up to 1.1.5 (the upstream is called under the stage's monitor):
TLC finds its deadlock - stage 2 calls stage 1 holding its own monitor while stage 1 signals
stage 2 holding its own - within about 200 distinct states.

### PreflightHandover

The preflight of a stream (the call `AsyncStream.preflightCall` on the neutral interpreter, and
`NeutralRunner.preflight`): three threads - the queued start (opens the stream, registers the
pull), the pull's completion (hands the stream over, or - cancelled - drops it) and `cancel()`.

- `NoLeak` - a preflight that ended cancelled holds nothing: if it sent a request, the cursor
  was cancelled (also when the first element arrived in the instant before: the preflight
  cancels the stream it could not hand over)
- `NoRequestAfterCancel` - a `cancel()` that returned before the queued start began: no
  request is sent at all
- the config `PreflightHandoverWindow` checks `NoDisclaimedStream` and TLC finds the
  counterexample on purpose: a stream handed over in the instant its caller cancelled (a race's
  loser, a timeout firing as the first chunk arrives) is dropped unrun, and nothing releases
  it. That is the window the docs of `preflightCall` / `firstCompletedOf` name; closing it
  needs releasable results at every discard site of both interpreters.

Its pair among the model checks is `PreflightModelCheck` (the real class); the Lincheck
scenario was first shown to fail on the code without the cancel of the dropped stream.

### SubscriberCursor

The publisher's thread (`onSubscribe`, `onNext`, `onComplete` / `onError` - also from inside
`request`, with `SyncPublisher`), the consumer's `next()` calls, the run's `ec` (completes the
promise a waiting `next()` holds) and a canceller. The calls on the subscription are serialized
by a lock-free work-in-progress loop: a caller records its request or cancel and leaves, whoever
owns the loop makes the calls.

- `Serialized` - the calls on the subscription never overlap (Reactive Streams 2.7)
- `NoOverflow` - at most `BufferSize` elements are buffered, and a compliant publisher never
  overflows them (demand: `BufferSize` up front, `Batch` more after each `Batch` handed out)
- `InOrder`, `EndsAsSent` - the consumer sees every element in order, none lost or twice, then
  the publisher's own end; an error comes after the elements received before it
- `EndsWithCancel`, `CancelSettles` - after `cancel()` returned, the `next()` it found
  outstanding - waiting, or already handed over to the ec (the `delivering` window) - is complete
- `CancelReaches` - a cancel reaches the publisher, through the loop
- `CancelledQueueEmpty` - a cancel drops what is buffered, later elements are dropped
- `AllDone` (liveness), and no deadlock

### BroadcastHub

Each consumer's thread (register, `next()`, `cancel()` when its run ends), an outside canceller,
the upstream's thread (the asynchronous completion of a pull), the creator's task that opens
the upstream, `shutdown()`, and the ec that completes the promises a waiting `next()` holds.
`Drain` adds the hub's always-on consumer, `SyncPull` lets a pull's result be there at once,
`EndsWithError` makes the upstream fail.

- `Bounded`, `InRange` - the buffer holds at most `BufferSize` elements; room is made by the
  SLOWEST registered consumer, and none is ever behind the oldest element or ahead of the newest
- `Contiguous`, `NoLateElement` - a consumer sees the elements from where it registered, in
  order, none lost; one that registered after the end sees nothing but the end
- `FirstTerminalWins`, `TerminalFreezesHead`, `EndAgrees` - the FIRST terminal event decides
  (the upstream's own end or `shutdown()`): a result that arrives after it - an element, or a
  failure racing the cancel - changes nothing, the completion promise and every consumer agree
- `SeesAll`, `SeesEnd` - a consumer that was not cut short and saw the end of an upstream that
  completed has seen every element; with enough calls it sees the end (no lost wake-up: whoever
  frees buffer space makes the hub pull again, a waiting consumer is handed the element or the
  end)
- `CancelSettles` - after `cancel()` the consumer's outstanding `next()`, also one already
  handed to the ec, is complete
- `AllDone` (liveness), and no deadlock

A process that is switched off by its constant (`WithCancel`, `WithShutdown`) is an EMPTY process
set, not a process that sits at its end: with the latter two idle processes multiplied the state
space ninefold. Local bookkeeping (a loop test, the choice of the next step) shares the step of
the next shared access - a local action commutes with every other thread's - which took the
smallest configuration from 1.7 million to 104,000 states.

### CallTimeout

A timeout in the run of an `AsyncCall`: the loop that sets the step up (suspend, schedule the
timer, start the child, look for a cancel that came first), the timer's task (it only claims the
race and hands over to the ec), the ec task that decides what the timeout does to a child still
running, the child's thread (its completion handler) and the caller of `cancel()`. `WithLate`
gives the timeout an `onLate`; `TimerRefused` lets the scheduler refuse the timer.

- `ResumedOnce` - the parent's suspension is resumed EXACTLY ONCE, by whichever of the child's
  completion, the timeout and a cancel claims it first (a compare-and-set) - also a cancel that
  arrives before the suspension is set up (`checkCancelled`)
- `SignalMatches` - what the parent is resumed with agrees with the race's one winner: the
  child's outcome, the timeout's failure, or the stop
- `LateOnce`, `NothingLost` - the loser is never lost: a child the timer beat that still ended
  with an outcome of its own (it was left to finish, or was past its last step) is reported to
  `onLate` exactly once; one that was stopped, or won, is not reported
- `ResumedAtEnd`, `AllDone` (liveness), and no deadlock

### CallTraverse

`traverse` in the run of an `AsyncCall`: the parent's loop (starts the first children), each
child's thread (its completion handler decides and launches the next child) and the caller of
`cancel()`; `CanFail` lets a child fail.

- `AtMostParallel` - never more than `Parallelism` children run at once
- `NoLateLaunch` - no child is added once the traversal finished (a failure, a cancel, the last
  result): a launch racing the failure adds nothing
- `ResumedOnce`, `SignalMatches`, `ResumedAtEnd` - the parent is resumed exactly once: with all
  results when every child succeeded (an Ok carries every index), with the failure of the first
  child that failed after every child that was running ended, or with the stop after the
  cancelled children ended
- `AllDone` (liveness), and no deadlock

Their pairs among the model checks are `CallRunModelCheck` (a timeout's timer race, a failing
`traverse`, a cancel), `HubModelCheck` and the `SubscriberCursor` scenarios (the real classes).

## Results

TLC 2.19. The state counts of the specs that hold do not depend on the run; a rejected spec or
mutant stops at its first counterexample (a few thousand states and a few seconds as a rule -
`CallTraverseM4`'s deadlock takes a million).

| Check | Config | Outcome | Distinct states |
|---|---|---|---|
| `ZioCursor` | 3 `next()` calls, 2 chunks, 2 cancellers | holds | 119,375 |
| `CursorDrain` | 2 elements, 2 subscriber calls, 1 yield, 2 workers, `BadRequests` | holds | 1,059,985 |
| `CursorDrainWide` | as above with 3 subscriber calls, valid requests only | holds | 1,028,095 |
| `TransformChain` | 2 elements, 2 consumer calls, one output per input | holds | 183,151 |
| `TransformChainEarly` | as above, a transformer may end the stream early | holds | 690,834 |
| `TransformChainBurst` | 1 element, 1 or 2 outputs per input | holds | 592,199 |
| `TransformChainOuts` | 2 elements, 0 or 2 outputs per input | holds | 4,000,050 |
| `TransformChainLegacy` | `TransformChain` with `Legacy = TRUE` | deadlock (expected) | about 200 |
| `PreflightHandover` | start, completion and cancel, one stream | holds | 522 |
| `PreflightWindow` | as above, `NoDisclaimedStream` | invariant violated (expected: the documented window) | 522 |
| `SubscriberCursor` | 2 buffered, 3 elements, 4 `next()` calls, a publisher that may call `onNext` from inside `request` | holds | 27,279 |
| `SubscriberCursorError` | as above, the publisher ends with `onError` | holds | 27,279 |
| `SubscriberCursorCancel` | as `SubscriberCursor`, a `cancel()` at an arbitrary moment | holds | 459,613 |
| `SubscriberCursorWide` | 4 buffered, 5 elements, 6 `next()` calls | holds | 51,675 |
| `SubscriberCursorAsync` | as `Error` with a `cancel()`, a publisher that signals from its own thread only | holds | 144,063 |
| `CallTimeout` | the timeout's race with an `onLate`, a cancel and a refusing scheduler | holds | 248,295 |
| `CallTimeoutNoLate` | as above, no `onLate` | holds | 177,219 |
| `CallTimeoutNoCancel` | the race alone | holds | 12,852 |
| `CallTraverse` | 3 children, parallelism 2, a child may fail, a cancel | holds | 956,960 |
| `CallTraverseNoCancel` | as above without the cancel | holds | 118,473 |
| `CallTraverseSerial` | as `CallTraverse`, parallelism 1 | holds | 88,260 |
| `CallTraverseWide` | 4 children, parallelism 2, no cancel | holds | 1,047,237 |
| `BroadcastHub` | 2 consumers, 1 buffered, 1 element, 1 `next()` each, an early stop | holds | 104,126 |
| `BroadcastHubWide` | as above with 2 `next()` calls (the end is seen) | holds | 224,546 |
| `BroadcastHubError` | as `Wide`, the upstream fails | holds | 224,546 |
| `BroadcastHubDrain` | as `Wide` with the hub's always-on consumer | holds | 259,196 |
| `BroadcastHubSync` | as `BroadcastHub`, a pull's result may be there at once | holds | 318,638 |
| `BroadcastHubTwo` | 2 consumers, 2 elements, 3 `next()` calls | holds | 661,515 |
| `BroadcastHubShutdown` | 1 consumer, 2 `next()` calls, the upstream fails, `shutdown()` at any moment | holds | 54,295 |
| `BroadcastHubCancel` | 1 consumer, 2 `next()` calls, an outside `cancel()` at any moment | holds | 54,441 |
| `BroadcastHubShutdown2` (slow) | 2 consumers, 1 `next()` call each, the upstream fails, `shutdown()` | holds | 1,450,646 |
| `BroadcastHubCancel2` (slow) | 2 consumers, 1 `next()` call each, an outside `cancel()` of the first (no symmetry reduction) | holds | 2,245,369 |

## Mutants

A spec that passes says little unless it can fail: each mutant seeds one bug into a spec
(`mutants/*.patch`), and the check passes only if TLC rejects it with the expected outcome.

| Mutant | Seeded bug | Rejected by |
|---|---|---|
| `ZioCursorM1` | `cancel()` reads `asked` before it writes `Closed` | deadlock |
| `ZioCursorM2` | the fiber parks without looking at a demand already handed over | deadlock |
| `CursorDrainM1` | the start task does not cancel the cursor when the run was cancelled meanwhile | `CancelSettles` |
| `CursorDrainM2` | the loop is left without re-checking for missed work | `NoStall` |
| `CursorDrainM3` | the start task does not hold the loop while `onSubscribe` runs | the assertion "one signal at a time": `onError` inside `onSubscribe` |
| `CursorDrainM4` | a request that arrives while a loop runs is not registered | `NoStall` |
| `TransformChainM1` | the flush is not left to the outermost frame while the monitor is held (config `TransformChainBurst`) | deadlock |
| `TransformChainM2` | the flush loop is left without re-checking for missed calls | `Flushed` |
| `TransformChainM3` | no flush after an entry point's synchronized block | `Flushed` |
| `PreflightHandoverM1` | the stream the preflight could not hand over (the cancel won the promise) is not cancelled | `NoLeak` |
| `PreflightHandoverM2` | the queued start does not look for a cancel that came while it was queued | `NoRequestAfterCancel` |
| `SubscriberCursorM1` | every caller runs the loop (no work-in-progress counter) | `Serialized` |
| `SubscriberCursorM2` | the loop resets the counter instead of subtracting what it handled: a record that came meanwhile is lost | deadlock |
| `SubscriberCursorM3` | a cancel leaves the promise already handed to the ec (config `Cancel`) | `CancelSettles` |
| `SubscriberCursorM4` | a waiting `next()` is not handed the end | deadlock |
| `SubscriberCursorM5` | no more demand after the first batch | deadlock |
| `SubscriberCursorM6` | the subscription is not cancelled (config `Cancel`) | `CancelReaches` |
| `SubscriberCursorM7` | elements after a cancel are kept (config `Cancel`) | `CancelledQueueEmpty` |
| `CallTimeoutM1` | `Suspension.complete` is no compare-and-set: every completer resumes the parent | `ResumedOnce` |
| `CallTimeoutM2` | the child always wins the race | `SignalMatches` |
| `CallTimeoutM3` | the loser's outcome is dropped, not reported to `onLate` | `NothingLost` |
| `CallTimeoutM4` | the timer takes the race without a compare-and-set | `SignalMatches` |
| `CallTimeoutM5` | a cancel claims the suspension without a compare-and-set | `ResumedOnce` |
| `CallTraverseM1` | a launch racing the end still adds the child | `NoLateLaunch` |
| `CallTraverseM2` | a failure neither cancels the others nor waits for them | `SignalMatches` |
| `CallTraverseM3` | the count of results never reaches zero (config `NoCancel`: a cancel masks it) | `ResumedAtEnd` |
| `CallTraverseM4` | a failure does not mark the traversal finished | deadlock |
| `CallTraverseM5` | the parallelism is ignored | `AtMostParallel` |
| `BroadcastHubM1` | the buffer is trimmed by the FASTEST consumer | `InRange` |
| `BroadcastHubM2` | `shutdown()` overwrites the terminal event the upstream decided (config `Shutdown`) | `FirstTerminalWins` |
| `BroadcastHubM3` | an upstream result that arrives after the terminal event is added (config `Shutdown`) | `TerminalFreezesHead` |
| `BroadcastHubM4` | `cancel()` does not complete the `next()` already handed to the ec (config `Cancel`) | `CancelSettles` |
| `BroadcastHubM5` | no refill pull after a cancel (config `Wide`: the first consumer stops early) | deadlock |
| `BroadcastHubM6` | a cancelled consumer stays registered and holds the buffer back (config `Wide`) | deadlock |
| `BroadcastHubM7` | `next()` does not pull for the room it made (config `Wide`) | deadlock |

Two observations the mutants gave:

- `CursorDrainM3` (the start task does not hold the loop while `onSubscribe` runs) is rejected
  only with `BadRequests`: holding the loop there matters for the rule-3.9 path - an invalid
  request from inside `onSubscribe` - not for ordinary requests.
- `TransformChainM1` (the flush is not left to the outermost frame while the stage's monitor is
  held) is rejected only when a transformer may yield more than one output per input
  (`TransformChainBurst.cfg`), hence its config.

## Changing a class or a spec

A change to the synchronization of one of the specified classes changes its spec first: adjust the
labels to the new atomic steps, run `check.sh`, then change the code. A new invariant gets a
mutant that violates it. A change that makes a mutant's patch no longer apply fails the check -
regenerate the patch (`diff -u` of the spec and its mutated copy, labels `a/<Module>.tla` /
`b/<Module>.tla`).

Limits, to be kept in mind when reading a pass: the configurations are small (two or three
elements, two or three calls per thread); the specs model the synchronization, not the data
(elements are counted, not carried); and a model is only as good as its correspondence to the
code - which is what the Lincheck checks of the real classes are for.

Not specified yet: the Akka / Pekko stages (`NestedStreamsLogic`, `CursorSourceStage`) and the
jdk engine's `IdleTimeoutPublisher` / `DeadlineBodySubscriber` / `BoundedBodyReader`; the fs2
pull bridge has a small-scope spec instead (`Fs2PullBridgeSpec`). The state of all layers and
what comes next is kept in `CLAUDE.md` ("State of the formalization").

Two modelling rules the hub spec taught (they cut its smallest configuration from 1.7 million to
104,000 states): a process that a constant switches off is an EMPTY process set (`process p \in
IF WithCancel THEN {"p"} ELSE {}`), not one that idles at its end - each idling process
multiplies the state space by its number of positions; and local bookkeeping (a loop test, the
choice of the next step) shares the step of the next shared access, which is sound because a
local action commutes with every other thread's. And `SYMMETRY` is sound only while no process
singles out one of the interchangeable ones: the two-consumer cancelling configuration has none
(a canceller that always targets the first consumer breaks the symmetry). A counterexample is
read as a fact about the model before the spec is weakened: the hub's first cancel deadlock was
the spec cancelling a consumer before it had registered, which the real class cannot do.
