# TLA+ specs of the stream layer

Design-level models of the three pieces of the stream layer whose correctness rests on an
interleaving argument. Each spec is a PlusCal algorithm in which **one label is one atomic step
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

## Running

```
formal/tla/check.sh --fetch        # every spec, config and mutant; exits non-zero on a mismatch
formal/tla/check.sh --list         # the checks and their expected outcomes
formal/tla/check.sh ZioCursor CursorDrainM3
```

Needs Java 11+, `patch`, and the TLA+ tools jar - not part of the repository: `--fetch`
downloads the pinned release (`tla2tools.jar` v1.7.4, TLC 2.19, SHA-256
`936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88`) into `target/`, or point
`TLA2TOOLS_JAR` at a copy. Everything is written below `formal/tla/target/` (git-ignored). The
whole run takes about 5 minutes on 20 cores (half of it `TransformChainOuts`); TLC keeps its
state queue on disk (a few hundred MB for the largest config), and a run whose state files grow
beyond `TLC_MAX_MB` (default 700) is stopped and reported as a failure.

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

## Results

TLC 2.19. The state counts of the specs that hold do not depend on the run; a rejected spec or
mutant stops at its first counterexample (tens of thousands of states at most, a few seconds
each).

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

Two observations the mutants gave:

- `CursorDrainM3` (the start task does not hold the loop while `onSubscribe` runs) is rejected
  only with `BadRequests`: holding the loop there matters for the rule-3.9 path - an invalid
  request from inside `onSubscribe` - not for ordinary requests.
- `TransformChainM1` (the flush is not left to the outermost frame while the stage's monitor is
  held) is rejected only when a transformer may yield more than one output per input
  (`TransformChainBurst.cfg`), hence its config.

## Changing a class or a spec

A change to the synchronization of one of the three classes changes its spec first: adjust the
labels to the new atomic steps, run `check.sh`, then change the code. A new invariant gets a
mutant that violates it. A change that makes a mutant's patch no longer apply fails the check -
regenerate the patch (`diff -u` of the spec and its mutated copy, labels `a/<Module>.tla` /
`b/<Module>.tla`).

Limits, to be kept in mind when reading a pass: the configurations are small (two or three
elements, two or three calls per thread); the specs model the synchronization, not the data
(elements are counted, not carried); and a model is only as good as its correspondence to the
code - which is what the Lincheck checks of the real classes are for.

Not specified yet: `SubscriberCursor` (its work-in-progress loop) and `AsyncBroadcastHub`
(the first terminal event decides; registration against shutdown) - both are model-checked
with Lincheck only, so nothing checks their liveness. The state of all three layers and what
comes next is kept in `CLAUDE.md` ("State of the formalization").
