# Calls 📞

[← back to the README](../README.md)

A plain request - and what a service builds around it: parsing, retries, timeouts, fan-out - as
one backend-neutral value, `AsyncCall[A]` (since 1.3.0), the single-value counterpart of
[`AsyncStream`](streaming.md#backend-neutral-streaming-asyncstream). Run it as a `Future`, or
natively as a ZIO effect: on the `zio-http` engine, interrupting the fiber aborts the HTTP request,
and the fiber's context (FiberRefs, log annotations, spans) reaches it.

## Why a call type

A `Future` cannot be cancelled: a request whose caller timed out, lost a race or went away
runs to its end - and is billed. A ZIO service on top of `Future`-returning methods also loses
the fiber's context at the hop, and its interruption never reaches the request. `AsyncCall` is
a plan, so an engine's call keeps its NATIVE effect: compiled for ZIO, the `zio-http` engine's
call is that engine's own ZIO effect, with the service's operators applied as ZIO operators -
nothing is bridged. The `Future` methods stay as they are.

## Getting a call

Every `execXRich` method of an engine has a call twin, `execXRichCall`, with the same parameters:

```scala
import io.cequence.wsclient.stream.AsyncCall

val call: AsyncCall[RichResponse] =
  engine.execPOSTRichCall(site, "chat/completions", bodyParams = body)
```

`execGETRichCall`, `execPOSTRichCall`, `execPOSTBodyRichCall`, `execPOSTMultipartRichCall`,
`execPOSTURLEncodedRichCall`, `execPOSTFileRichCall`, `execDELETERichCall`, `execPATCHRichCall`,
`execPUTRichCall`, `execPUTBodyRichCall`, `execPUTMultipartRichCall`, `execPUTFileRichCall`.

A service on `WSClientWithEngineBase` gets `execXRichCall` and `execXCall`, the twin of
`execX`: a non-acceptable status fails the call with the service's own exception
(`handleErrorCodes`), as a stage of the call. By default a service's calls run its own
`execXRich` methods (whatever those do, the calls do too); with `nativeCalls = true` they go to
the engine's calls - see [Extending](#extending).

| Engine | The call is | `toFuture` | `toZIO` |
|---|---|---|---|
| `zio-http` | the engine's ZIO effect | 1 bridge (runs on the engine's runtime) | native - no bridge; interrupting aborts the request |
| Play, pekko-http, jdk, sttp | a lazy `Future` over `execXRich` | native - no bridge | 1 bridge (`ZIO.fromFuture`) |

Calls of your own come from `AsyncCall.successful` / `failed` / `fromTry`, `defer(thunk)`,
`future(f)` / `fromFuture(ec => f)`, `sleep(d)`, `traverse(...)`, and - with ws-client-zio -
`task.toAsyncCall`.

## Running a call

```scala
call.toFuture                        // a Future; user functions run on the implicit ExecutionContext
val run = call.run(); run.cancel()   // a cancellable Future run

import io.cequence.wsclient.stream.ZioInterop._
call.toZIO                           // a ZIO Task - ws-client-zio
```

## Semantics

- **Cold and re-runnable:** building a call does no I/O; every thunk and user function runs
  per run, and each run of an HTTP call is a new (possibly billed) request. The request
  context (`SiteBinding.requestContextFun`, e.g. a token provider) is evaluated per run.
- **Interruption travels to the request.** Compiled for ZIO, interrupting the fiber
  interrupts the leaf: the `zio-http` request is aborted. The `Future` interpreter stops a
  cancelled run (`run().cancel()`, the loser of a `timeout`, the rest of a failed `traverse`)
  at its next step: a native leaf in flight is cancelled (a `zio-http` request is aborted), a
  `Future` in flight runs to its end - a `Future` cannot be cancelled, so the Play, pekko-http,
  jdk and sttp requests complete, on ZIO too - but nothing after it starts. The hooks of the
  cancelled parts have run before the outcome goes on.
- **Failures:** a non-fatal exception thrown by a user function fails the call with that
  exception, unwrapped (on ZIO a failure, not a defect); fatal errors are never caught. An
  interruption is NOT a failure: `recover`, `transformWith` and `attempt` never see it, only
  the termination hooks do (`Cancelled`) - a retry built on them never retries an interrupted
  try.
- **Stack safety:** long `flatMap` chains and recursion through `flatMap` or `recoverWith`
  (retry loops) run in constant stack on both interpreters.
- **Execution:** the `Future` interpreter runs user functions on the given
  `ExecutionContext`, never on an IO thread. Compiled for ZIO they run on the fiber, and a ZIO
  leaf runs in the calling fiber.
- **Time:** `sleep`, `delay` and `timeout` use the implicit `AsyncScheduler` on the `Future`
  interpreter - the one in scope where the call is built - and ZIO's `Clock` on ZIO, so
  `TestClock` drives them in ZIO tests.

## Operators

- **Transforming:** `map`, `flatMap`, `zip` (one after the other), `mapError`, `recover`,
  `recoverWith`, `transform` / `transformWith` (on the `Try` of the outcome), `attempt`
  (`Either[Throwable, A]`).
- **Time:** `AsyncCall.sleep(d)`, `delay(d)`, `timeout(limit)` / `timeout(limit, error)` - a
  `java.util.concurrent.TimeoutException` by default; the loser is cancelled (interrupted).
- **Fan-out:** `AsyncCall.traverse(items, parallelism)(f)` - at most `parallelism` calls at
  once, the next starting as one finishes, results in input order. The first failure fails the
  whole: no further item starts, the running ones are cancelled. `parallelism = 1` runs them
  one after the other.
- **Race:** `AsyncCall.firstCompletedOf(calls)` - the outcome (a value or a failure) of
  whichever call ends first; the others are then cancelled - on ZIO interrupted, so a
  `zio-http` request is aborted; on the `Future` interpreter a native leaf is cancelled and a
  `Future` in flight runs on. That differs from `Future.firstCompletedOf`, whose losers always
  run on. No calls: a `NoSuchElementException`. A loser that completed in the same instant as
  the winner is discarded with its value - of no consequence for a plain value, but a call that
  yields a resource (a preflighted stream: `preflightCall`, the download twins) leaves it held:
  race the requests that deliver heads (`execXRichCall`), not the calls that yield streams.
- **Lifecycle:** `onTerminate(f)` / `guaranteeCase(f)` - exactly once per run that reached
  the call, with `Completed`, `Failed(e)` or `Cancelled`, before the outcome goes on; the hook
  itself is not interrupted. A failing hook fails a completed call and is added (suppressed) to
  a failure. A run cancelled before it reached the call does not run its hook.
- **Running:** `toFuture`, `run()` (cancellable), `toZIO`; `describe` shows the plan, e.g.
  `native(zio) -> map -> recoverWith -> timeout(30 seconds)`.

### Timeouts with a late outcome

`timeout(limit, error, onLate, onUnscheduled)` keeps what a plain `Future` timeout gives - the
outcome of a loser that could not be stopped goes to `onLate`, on both interpreters. A `Future`
cannot be cancelled, so a loser with a `Future` in flight when the limit passes (a
`Future`-based engine's request, anywhere inside it) is left to finish, its remaining steps
included, and reported; so is a loser that was past its last step and ended on its own. Any
other loser is stopped (on ZIO interrupted: a `zio-http` request is aborted) and not reported.

A loser left to finish runs outside the call: nothing bounds or cancels it any more, and an
enclosing `traverse` does not count it against its parallelism. Put this timeout on the single
request, inside a retry - not around the retry.

On the `Future` interpreter a timer the scheduler refuses (a limit beyond what an actor
scheduler takes, a scheduler shut down) leaves the call unlimited: `onUnscheduled` is told
(the other `timeout`s log a warning).

## Calls and streams together

Since 1.3.0 the two meet without a bridge on ZIO:

- `AsyncStream.fromCall(call)` - a stream of one element, the call's value;
  `AsyncStream.unwrapCall(call)` - the stream a call yields (a session or a cache created
  before the stream opens). The call runs once per run of the stream and is interrupted with
  it. On Akka / Pekko / fs2 it runs on the `Future` interpreter (a `Future` leaf in it costs no
  bridge, as with `futureStream`; a native ZIO leaf counts one); on ZIO a `Future` leaf is
  foreign and counts one - and so does `futureStream` (since 1.3.0). The call starts with the
  first demand on Akka / Pekko, fs2 and ZIO; the neutral interpreter starts it as the run
  opens, just before the first pull.
- `stream.evalMapCall(f)` - `evalMap` with calls: one call at a time, in order (it is
  `flatMapConcat` over `fromCall`). On ZIO the call runs in the stream's own fiber, so
  FiberRefs reach it and an interrupted stream interrupts it.
- `stream.runFoldCall(zero)(f)`, `runToSeqCall`, `runDrainCall` (and `runToBytesCall` for a
  byte stream) - a stream's fold as a call. `zero` is by-name, evaluated per run (a mutable
  accumulator is not shared between runs). On ZIO it is the ZIO stream's own run; on the
  `Future` interpreter a run of the neutral stream interpreter that is cancelled with the
  call.
- `stream.preflightCall` - `preflight` as a call: per run the request is sent and the call
  completes once the body started. Interrupting the call before the body started aborts the
  request on every engine: a native ZIO stream (the `zio-http` engine's) and a native Akka /
  Pekko stream (Play, pekko-http) are preflighted by their own backend, any other stream by the
  neutral interpreter (the `RunPreflight` node), each cancelled with the call. Once the call
  completed, the stream it returned is its consumer's - to run or to cancel. A call that
  completes at the very instant it is cancelled (the loser of a race, a timeout firing as the
  first chunk arrives) may have its stream discarded unrun, and a discarded stream holds its
  request open: do not race or time out a call that yields a stream where that matters.

The service downloads have call twins: `execGETBytesCall`, `execPOSTBytesCall`,
`execGETStreamCall`, `execPOSTStreamCall` (and the protected `execBytesDownloadCall` /
`execStreamDownloadCall`), built on those. Like the `Future` downloads they go straight to the
engine - a service's own `execXRich` overrides apply to neither.

## Service wrappers

`ServiceWrapper` (the base of `roundRobin`, `randomOrder`, `log`, `preAction`,
`repackExceptions` and `parallelTakeFirst`) has `wrapCall(fun: S => AsyncCall[T])` next to
`wrap`: the function is applied to the wrapped service PER RUN, so a service behind a wrapper
keeps its calls native. The default is a `Future` leaf over `wrap` (a wrapper written before
1.3.0 keeps working); every wrapper of the library overrides it: the service picked per run
(a re-run of a call goes to the next one), the `log` line written per run (the twin `fooCall`
is logged as `foo`), the `preAction` action and the exception repacking inside the call, and
`parallelTakeFirst` as a race whose losers are cancelled.

## Bridges and diagnostics

A BRIDGE is a hop between a backend's effect and a `Future`. `CallDiagnostics.counted` counts
the bridges of the calls compiled or run inside it - the pinned expectations: the `zio-http`
engine's call costs none with `toZIO` and one with `toFuture`; a `Future`-based engine's call
(e.g. `jdk`) one with `toZIO` and none with `toFuture`.

```scala
val (task, bridges) = CallDiagnostics.counted(call.toZIO)
// run the task, then:
bridges.get   // 0 on the zio-http engine
```

## Extending

- **An engine that overrides `execXRich`** (e.g. one that signs each request) keeps the
  default twin, which runs its `execXRich` - correct, but a `Future` leaf - or overrides
  `execXRichCall` too, building the request per run (`AsyncCall.defer`) and delegating to the
  inner engine's call to stay native.
- **A service on `WSClientWithEngineBase`** runs its own `execXRich` for its calls by default,
  so an override there (signing, extra headers, metrics) is never bypassed - a `Future` leaf.
  A service that overrides none of them (or overrides the twins too) sets
  `override protected def nativeCalls = true`: its calls then go to the engine's own calls -
  native on `zio-http`.
- **Your own effect type:** wrap it as a `NativeCall` (`AsyncCall.fromNative`): its `start()`
  runs it on its own runtime for any other interpreter (one bridge), and the run's `cancel()`
  should stop it.
