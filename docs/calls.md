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
