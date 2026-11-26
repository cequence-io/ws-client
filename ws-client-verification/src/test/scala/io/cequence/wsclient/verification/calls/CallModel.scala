package io.cequence.wsclient.verification.calls

/**
 * The model of `AsyncCall` that the tests run AND the proofs are about - one source, two uses.
 *
 * The tests (`CallTreeSpec`) run every plan of a scope through the real interpreters and
 * require the outcome and the hook log to equal [[denote]], and to equal what the frame-stack
 * machine [[step]] computes (the machine mirrors `CallFiber`: signals, one continuation frame
 * per operator, a hook as a child run). The Lean extractor (`LeanExtract`, `sbt leanExtract`)
 * translates THIS file - mechanically, construct by construct - into
 * `formal/lean/WsClientProofs/Calls/Model.lean`, where `Calls/Proofs.lean` proves, for EVERY
 * plan, that the machine reaches [[denote]]. So what the tests compare the code with is
 * exactly what is proved: nothing is transliterated by hand.
 *
 * What a plan is: a call as first-order data. The user functions the real calls take (`map`,
 * `flatMap`, `recover`, ...) are the few shapes a test needs - add n, throw t, continue with a
 * plan - which keeps every plan finite, so every recursion below is structural. A plan with a
 * [[Gate]] is cancelled where it first reaches it (the tests cancel the run while it is parked
 * there): the sequential fragment, plus cancellation of the one run. Not modelled: time
 * (`Timeout` / `Delay` never fire), concurrency (`traverse`, races) and streams - the
 * small-scope spec of `CallSmallScopeSpec` and the model checks cover those.
 *
 * THE SUBSET the extractor accepts (anything else fails the extraction, loudly):
 *   - `sealed trait`s with `final case class` / `case object` children (-> Lean `inductive`);
 *     one `final case class` without a parent (-> `structure`, `.field` access allowed)
 *   - `def`s with typed parameters and a result type, structurally recursive
 *   - expressions: `Int` / `Boolean` literals, `val`, `if`, `match` (constructor, `Nil`, `::`,
 *     `Some` / `None`, literal, variable and `_` patterns), `+` `-` `==` `&&` `||`, `::` `++`
 *     `List(...)`, calls of the defs above, constructors
 *   - types `Int`, `Boolean`, `List[T]`, `Option[T]` and the types of this object `Int` is
 *     Scala's 32-bit `Int`, which the extractor reads as Lean's unbounded `Int`: the plans the
 *     tests enumerate use small numbers, far from the overflow.
 */
object CallModel {

  // ------------------------------------------------------------------------------- plans

  /** How a leaf is built (the plain, a `Future`, a ZIO effect, ZIO's defect, a `sleep`). */
  sealed trait Kind
  case object Plain extends Kind
  case object Fut extends Kind
  case object Zio extends Kind
  case object ZioDie extends Kind
  case object SleepK extends Kind

  /**
   * A call as data. Every operator is unary on its `up`; `q`, `onOk`, `onErr` are the calls
   * its function returns (a lazy child: evaluated only when reached).
   */
  sealed trait Plan
  final case class Value(
    kind: Kind,
    v: Int
  ) extends Plan
  final case class Fail(
    kind: Kind,
    t: Int
  ) extends Plan
  final case class Defer(q: Plan) extends Plan
  final case class DeferThrow(t: Int) extends Plan
  case object Gate extends Plan
  final case class MapAdd(
    up: Plan,
    n: Int
  ) extends Plan
  final case class MapThrow(
    up: Plan,
    t: Int
  ) extends Plan
  final case class FlatInc(
    up: Plan,
    n: Int
  ) extends Plan
  final case class FlatThen(
    up: Plan,
    q: Plan
  ) extends Plan
  final case class FlatThrow(
    up: Plan,
    t: Int
  ) extends Plan
  final case class MapError(up: Plan) extends Plan
  final case class MapErrorThrow(
    up: Plan,
    t: Int
  ) extends Plan
  final case class RecoverTo(
    up: Plan,
    n: Int
  ) extends Plan
  final case class RecoverUndefined(up: Plan) extends Plan
  final case class RecoverThrow(
    up: Plan,
    t: Int
  ) extends Plan
  final case class RecoverWith(
    up: Plan,
    q: Plan
  ) extends Plan
  final case class TransformSwap(up: Plan) extends Plan
  final case class TransformWith(
    up: Plan,
    onOk: Plan,
    onErr: Plan
  ) extends Plan
  final case class TransformThrow(
    up: Plan,
    t: Int
  ) extends Plan
  final case class AttemptFold(up: Plan) extends Plan
  final case class GuardLog(
    up: Plan,
    id: Int
  ) extends Plan
  final case class GuardFail(
    up: Plan,
    t: Int
  ) extends Plan
  final case class GuardThrow(
    up: Plan,
    t: Int
  ) extends Plan
  final case class GuardRun(
    up: Plan,
    q: Plan
  ) extends Plan
  final case class Timeout(up: Plan) extends Plan
  final case class Delay(up: Plan) extends Plan

  // ---------------------------------------------------------------------------- outcomes

  /** How a run ends: a value, a failure (with its tag), or cancelled. */
  sealed trait Res
  final case class ROk(v: Int) extends Res
  final case class RErr(t: Int) extends Res
  case object RCancelled extends Res

  /** What a termination hook is told. */
  sealed trait Term
  case object Completed extends Term
  final case class Failed(t: Int) extends Term
  case object Cancelled extends Term

  /** A hook that saw a termination. */
  final case class Ev(
    id: Int,
    term: Term
  )

  /** What a run shows: its end, and what its hooks saw, in order. */
  final case class Obs(
    res: Res,
    log: List[Ev]
  )

  // -------------------------------------------------------------------------- denotation

  // The meaning of a plan, from the scaladoc of AsyncCall alone: a failure skips `map` /
  // `flatMap`, a value skips the recoveries, a cancellation is neither (only the hooks see
  // it); a hook runs after its call ended and before the outcome goes on - a failing hook
  // fails a completed call, and is dropped when the call failed or was cancelled.

  def termOf(r: Res): Term = r match {
    case ROk(_)     => Completed
    case RErr(t)    => Failed(t)
    case RCancelled => Cancelled
  }

  def afterHook(
    res: Res,
    hook: Res
  ): Res = res match {
    case RErr(e)    => RErr(e)
    case RCancelled => RCancelled
    case ROk(v) =>
      hook match {
        case RErr(t) => RErr(t)
        case _       => ROk(v)
      }
  }

  def sequence(
    log: List[Ev],
    next: Obs
  ): Obs = Obs(next.res, log ++ next.log)

  def guarded(
    up: Obs,
    hook: Obs
  ): Obs = Obs(afterHook(up.res, hook.res), up.log ++ hook.log)

  def denote(p: Plan): Obs = p match {
    case Value(_, v)   => Obs(ROk(v), Nil)
    case Fail(_, t)    => Obs(RErr(t), Nil)
    case Defer(q)      => denote(q)
    case DeferThrow(t) => Obs(RErr(t), Nil)
    case Gate          => Obs(RCancelled, Nil)
    case MapAdd(up, n) =>
      denote(up) match {
        case Obs(ROk(v), log) => Obs(ROk(v + n), log)
        case o                => o
      }
    case MapThrow(up, t) =>
      denote(up) match {
        case Obs(ROk(_), log) => Obs(RErr(t), log)
        case o                => o
      }
    case FlatInc(up, n) =>
      denote(up) match {
        case Obs(ROk(v), log) => Obs(ROk(v + n), log)
        case o                => o
      }
    case FlatThen(up, q) =>
      denote(up) match {
        case Obs(ROk(_), log) => sequence(log, denote(q))
        case o                => o
      }
    case FlatThrow(up, t) =>
      denote(up) match {
        case Obs(ROk(_), log) => Obs(RErr(t), log)
        case o                => o
      }
    case MapError(up) =>
      denote(up) match {
        case Obs(RErr(t), log) => Obs(RErr(t + 100), log)
        case o                 => o
      }
    case MapErrorThrow(up, t) =>
      denote(up) match {
        case Obs(RErr(_), log) => Obs(RErr(t), log)
        case o                 => o
      }
    case RecoverTo(up, n) =>
      denote(up) match {
        case Obs(RErr(_), log) => Obs(ROk(n), log)
        case o                 => o
      }
    case RecoverUndefined(up) => denote(up)
    case RecoverThrow(up, t) =>
      denote(up) match {
        case Obs(RErr(_), log) => Obs(RErr(t), log)
        case o                 => o
      }
    case RecoverWith(up, q) =>
      denote(up) match {
        case Obs(RErr(_), log) => sequence(log, denote(q))
        case o                 => o
      }
    case TransformSwap(up) =>
      denote(up) match {
        case Obs(ROk(_), log)  => Obs(RErr(99), log)
        case Obs(RErr(_), log) => Obs(ROk(300), log)
        case o                 => o
      }
    case TransformWith(up, onOk, onErr) =>
      denote(up) match {
        case Obs(ROk(_), log)  => sequence(log, denote(onOk))
        case Obs(RErr(_), log) => sequence(log, denote(onErr))
        case o                 => o
      }
    case TransformThrow(up, t) =>
      denote(up) match {
        case Obs(ROk(_), log)  => Obs(RErr(t), log)
        case Obs(RErr(_), log) => Obs(RErr(t), log)
        case o                 => o
      }
    case AttemptFold(up) =>
      denote(up) match {
        case Obs(ROk(v), log)  => Obs(ROk(v + 1000), log)
        case Obs(RErr(_), log) => Obs(ROk(-1), log)
        case o                 => o
      }
    case GuardLog(up, id) =>
      val o = denote(up)
      guarded(o, Obs(ROk(0), List(Ev(id, termOf(o.res)))))
    case GuardFail(up, t) =>
      guarded(denote(up), Obs(RErr(t), Nil))
    case GuardThrow(up, t) =>
      guarded(denote(up), Obs(RErr(t), Nil))
    case GuardRun(up, q) =>
      guarded(denote(up), denote(q))
    case Timeout(up) => denote(up)
    case Delay(up)   => denote(up)
  }

  // A hook's call is not cancelled (a run cancelled while it waits would never end): the
  // plans of a hook have no Gate, anywhere below.

  def gateFree(p: Plan): Boolean = p match {
    case Value(_, _)               => true
    case Fail(_, _)                => true
    case Defer(q)                  => gateFree(q)
    case DeferThrow(_)             => true
    case Gate                      => false
    case MapAdd(up, _)             => gateFree(up)
    case MapThrow(up, _)           => gateFree(up)
    case FlatInc(up, _)            => gateFree(up)
    case FlatThen(up, q)           => gateFree(up) && gateFree(q)
    case FlatThrow(up, _)          => gateFree(up)
    case MapError(up)              => gateFree(up)
    case MapErrorThrow(up, _)      => gateFree(up)
    case RecoverTo(up, _)          => gateFree(up)
    case RecoverUndefined(up)      => gateFree(up)
    case RecoverThrow(up, _)       => gateFree(up)
    case RecoverWith(up, q)        => gateFree(up) && gateFree(q)
    case TransformSwap(up)         => gateFree(up)
    case TransformWith(up, qo, qe) => gateFree(up) && gateFree(qo) && gateFree(qe)
    case TransformThrow(up, _)     => gateFree(up)
    case AttemptFold(up)           => gateFree(up)
    case GuardLog(up, _)           => gateFree(up)
    case GuardFail(up, _)          => gateFree(up)
    case GuardThrow(up, _)         => gateFree(up)
    case GuardRun(up, q)           => gateFree(up) && gateFree(q)
    case Timeout(up)               => gateFree(up)
    case Delay(up)                 => gateFree(up)
  }

  /** The plans the model is about: the plan of every hook (`GuardRun`) has no Gate. */
  def hooksOk(p: Plan): Boolean = p match {
    case Value(_, _)               => true
    case Fail(_, _)                => true
    case Defer(q)                  => hooksOk(q)
    case DeferThrow(_)             => true
    case Gate                      => true
    case MapAdd(up, _)             => hooksOk(up)
    case MapThrow(up, _)           => hooksOk(up)
    case FlatInc(up, _)            => hooksOk(up)
    case FlatThen(up, q)           => hooksOk(up) && hooksOk(q)
    case FlatThrow(up, _)          => hooksOk(up)
    case MapError(up)              => hooksOk(up)
    case MapErrorThrow(up, _)      => hooksOk(up)
    case RecoverTo(up, _)          => hooksOk(up)
    case RecoverUndefined(up)      => hooksOk(up)
    case RecoverThrow(up, _)       => hooksOk(up)
    case RecoverWith(up, q)        => hooksOk(up) && hooksOk(q)
    case TransformSwap(up)         => hooksOk(up)
    case TransformWith(up, qo, qe) => hooksOk(up) && hooksOk(qo) && hooksOk(qe)
    case TransformThrow(up, _)     => hooksOk(up)
    case AttemptFold(up)           => hooksOk(up)
    case GuardLog(up, _)           => hooksOk(up)
    case GuardFail(up, _)          => hooksOk(up)
    case GuardThrow(up, _)         => hooksOk(up)
    case GuardRun(up, q)           => hooksOk(up) && gateFree(q) && hooksOk(q)
    case Timeout(up)               => hooksOk(up)
    case Delay(up)                 => hooksOk(up)
  }

  // ------------------------------------------------------------------------------ machine

  // `CallFiber`'s run loop as a transition function: one signal (Eval a node, a value, a
  // failure, Stop), a stack of continuation frames - one per operator, pushed on the way down
  // to the leaf and popped on the way up - and, for a hook, a CHILD run: the parent waits
  // (`Parent`: its frames and the signal that goes on after the hook) while the child runs
  // on its own frames. A cancelled run (Stop) pops frames and runs only the hooks.

  sealed trait Sig
  final case class Eval(p: Plan) extends Sig
  final case class OkS(v: Int) extends Sig
  final case class ErrS(t: Int) extends Sig
  case object StopS extends Sig

  sealed trait Frame
  final case class FMapAdd(n: Int) extends Frame
  final case class FMapThrow(t: Int) extends Frame
  final case class FFlatInc(n: Int) extends Frame
  final case class FFlatThen(q: Plan) extends Frame
  final case class FFlatThrow(t: Int) extends Frame
  case object FMapError extends Frame
  final case class FMapErrorThrow(t: Int) extends Frame
  final case class FRecoverTo(n: Int) extends Frame
  case object FRecoverUndefined extends Frame
  final case class FRecoverThrow(t: Int) extends Frame
  final case class FRecoverWith(q: Plan) extends Frame
  case object FTransformSwap extends Frame
  final case class FTransformWith(
    onOk: Plan,
    onErr: Plan
  ) extends Frame
  final case class FTransformThrow(t: Int) extends Frame
  case object FAttemptFold extends Frame
  final case class FGuardLog(id: Int) extends Frame
  final case class FGuardFail(t: Int) extends Frame
  final case class FGuardThrow(t: Int) extends Frame
  final case class FGuardRun(q: Plan) extends Frame

  final case class Parent(
    frames: List[Frame],
    after: Sig
  )

  final case class Config(
    sig: Sig,
    frames: List[Frame],
    parents: List[Parent],
    log: List[Ev]
  )

  def init(p: Plan): Config = Config(Eval(p), Nil, Nil, Nil)

  /** What goes on after a hook ended with `outcome` (CallFiber.afterHook). */
  def afterHookSig(
    after: Sig,
    outcome: Sig
  ): Sig = outcome match {
    case ErrS(t) =>
      after match {
        case OkS(_) => ErrS(t)
        case _      => after
      }
    case _ => after
  }

  /** The next configuration, or None when the run ended (the root run, with no frame left). */
  def step(c: Config): Option[Config] = c match {
    case Config(sig, frames, parents, log) =>
      sig match {
        case Eval(p) => Some(evalStep(p, frames, parents, log))
        case OkS(v) =>
          frames match {
            case Nil       => finish(sig, parents, log)
            case f :: rest => Some(onOk(v, f, rest, parents, log))
          }
        case ErrS(t) =>
          frames match {
            case Nil       => finish(sig, parents, log)
            case f :: rest => Some(onErr(t, f, rest, parents, log))
          }
        case StopS =>
          frames match {
            case Nil       => finish(sig, parents, log)
            case f :: rest => Some(onStop(f, rest, parents, log))
          }
      }
  }

  // a run with no frame left: the root ends, a hook's child run hands its outcome to its parent
  def finish(
    sig: Sig,
    parents: List[Parent],
    log: List[Ev]
  ): Option[Config] = parents match {
    case Nil => None
    case Parent(pf, after) :: ps =>
      Some(Config(afterHookSig(after, sig), pf, ps, log))
  }

  def evalStep(
    p: Plan,
    frames: List[Frame],
    parents: List[Parent],
    log: List[Ev]
  ): Config =
    p match {
      case Value(_, v)   => Config(OkS(v), frames, parents, log)
      case Fail(_, t)    => Config(ErrS(t), frames, parents, log)
      case Defer(q)      => Config(Eval(q), frames, parents, log)
      case DeferThrow(t) => Config(ErrS(t), frames, parents, log)
      // parked, and cancelled there
      case Gate => Config(StopS, frames, parents, log)
      case MapAdd(up, n) =>
        Config(Eval(up), FMapAdd(n) :: frames, parents, log)
      case MapThrow(up, t) =>
        Config(Eval(up), FMapThrow(t) :: frames, parents, log)
      case FlatInc(up, n) =>
        Config(Eval(up), FFlatInc(n) :: frames, parents, log)
      case FlatThen(up, q) =>
        Config(Eval(up), FFlatThen(q) :: frames, parents, log)
      case FlatThrow(up, t) =>
        Config(Eval(up), FFlatThrow(t) :: frames, parents, log)
      case MapError(up) =>
        Config(Eval(up), FMapError :: frames, parents, log)
      case MapErrorThrow(up, t) =>
        Config(Eval(up), FMapErrorThrow(t) :: frames, parents, log)
      case RecoverTo(up, n) =>
        Config(Eval(up), FRecoverTo(n) :: frames, parents, log)
      case RecoverUndefined(up) =>
        Config(Eval(up), FRecoverUndefined :: frames, parents, log)
      case RecoverThrow(up, t) =>
        Config(Eval(up), FRecoverThrow(t) :: frames, parents, log)
      case RecoverWith(up, q) =>
        Config(Eval(up), FRecoverWith(q) :: frames, parents, log)
      case TransformSwap(up) =>
        Config(Eval(up), FTransformSwap :: frames, parents, log)
      case TransformWith(up, qo, qe) =>
        Config(Eval(up), FTransformWith(qo, qe) :: frames, parents, log)
      case TransformThrow(up, t) =>
        Config(Eval(up), FTransformThrow(t) :: frames, parents, log)
      case AttemptFold(up) =>
        Config(Eval(up), FAttemptFold :: frames, parents, log)
      case GuardLog(up, id) =>
        Config(Eval(up), FGuardLog(id) :: frames, parents, log)
      case GuardFail(up, t) =>
        Config(Eval(up), FGuardFail(t) :: frames, parents, log)
      case GuardThrow(up, t) =>
        Config(Eval(up), FGuardThrow(t) :: frames, parents, log)
      case GuardRun(up, q) =>
        Config(Eval(up), FGuardRun(q) :: frames, parents, log)
      // a timeout that does not fire is its upstream (its child run passes everything on)
      case Timeout(up) => Config(Eval(up), frames, parents, log)
      case Delay(up)   => Config(Eval(up), frames, parents, log)
    }

  // a hook that only logs runs in the loop; any other hook is a child run
  def hookLog(
    id: Int,
    term: Term,
    after: Sig,
    rest: List[Frame],
    parents: List[Parent],
    log: List[Ev]
  ): Config =
    Config(after, rest, parents, log ++ List(Ev(id, term)))

  def hookChild(
    q: Plan,
    after: Sig,
    rest: List[Frame],
    parents: List[Parent],
    log: List[Ev]
  ): Config =
    Config(Eval(q), Nil, Parent(rest, after) :: parents, log)

  // a hook function that throws: no child run
  def hookThrown(
    t: Int,
    after: Sig,
    rest: List[Frame],
    parents: List[Parent],
    log: List[Ev]
  ): Config =
    Config(afterHookSig(after, ErrS(t)), rest, parents, log)

  def onOk(
    v: Int,
    f: Frame,
    rest: List[Frame],
    parents: List[Parent],
    log: List[Ev]
  ): Config =
    f match {
      case FMapAdd(n)            => Config(OkS(v + n), rest, parents, log)
      case FMapThrow(t)          => Config(ErrS(t), rest, parents, log)
      case FFlatInc(n)           => Config(Eval(Value(Plain, v + n)), rest, parents, log)
      case FFlatThen(q)          => Config(Eval(q), rest, parents, log)
      case FFlatThrow(t)         => Config(ErrS(t), rest, parents, log)
      case FMapError             => Config(OkS(v), rest, parents, log)
      case FMapErrorThrow(_)     => Config(OkS(v), rest, parents, log)
      case FRecoverTo(_)         => Config(OkS(v), rest, parents, log)
      case FRecoverUndefined     => Config(OkS(v), rest, parents, log)
      case FRecoverThrow(_)      => Config(OkS(v), rest, parents, log)
      case FRecoverWith(_)       => Config(OkS(v), rest, parents, log)
      case FTransformSwap        => Config(ErrS(99), rest, parents, log)
      case FTransformWith(qo, _) => Config(Eval(qo), rest, parents, log)
      case FTransformThrow(t)    => Config(ErrS(t), rest, parents, log)
      case FAttemptFold          => Config(OkS(v + 1000), rest, parents, log)
      case FGuardLog(id)         => hookLog(id, Completed, OkS(v), rest, parents, log)
      case FGuardFail(t)         => hookChild(Fail(Plain, t), OkS(v), rest, parents, log)
      case FGuardThrow(t)        => hookThrown(t, OkS(v), rest, parents, log)
      case FGuardRun(q)          => hookChild(q, OkS(v), rest, parents, log)
    }

  def onErr(
    t: Int,
    f: Frame,
    rest: List[Frame],
    parents: List[Parent],
    log: List[Ev]
  ): Config =
    f match {
      case FMapAdd(_)            => Config(ErrS(t), rest, parents, log)
      case FMapThrow(_)          => Config(ErrS(t), rest, parents, log)
      case FFlatInc(_)           => Config(ErrS(t), rest, parents, log)
      case FFlatThen(_)          => Config(ErrS(t), rest, parents, log)
      case FFlatThrow(_)         => Config(ErrS(t), rest, parents, log)
      case FMapError             => Config(ErrS(t + 100), rest, parents, log)
      case FMapErrorThrow(t2)    => Config(ErrS(t2), rest, parents, log)
      case FRecoverTo(n)         => Config(OkS(n), rest, parents, log)
      case FRecoverUndefined     => Config(ErrS(t), rest, parents, log)
      case FRecoverThrow(t2)     => Config(ErrS(t2), rest, parents, log)
      case FRecoverWith(q)       => Config(Eval(q), rest, parents, log)
      case FTransformSwap        => Config(OkS(300), rest, parents, log)
      case FTransformWith(_, qe) => Config(Eval(qe), rest, parents, log)
      case FTransformThrow(t2)   => Config(ErrS(t2), rest, parents, log)
      case FAttemptFold          => Config(OkS(-1), rest, parents, log)
      case FGuardLog(id)         => hookLog(id, Failed(t), ErrS(t), rest, parents, log)
      case FGuardFail(t2)        => hookChild(Fail(Plain, t2), ErrS(t), rest, parents, log)
      case FGuardThrow(t2)       => hookThrown(t2, ErrS(t), rest, parents, log)
      case FGuardRun(q)          => hookChild(q, ErrS(t), rest, parents, log)
    }

  // a cancelled run: the frames are skipped, but a hook sees `Cancelled`
  def onStop(
    f: Frame,
    rest: List[Frame],
    parents: List[Parent],
    log: List[Ev]
  ): Config =
    f match {
      case FGuardLog(id)  => hookLog(id, Cancelled, StopS, rest, parents, log)
      case FGuardFail(t)  => hookChild(Fail(Plain, t), StopS, rest, parents, log)
      case FGuardThrow(t) => hookThrown(t, StopS, rest, parents, log)
      case FGuardRun(q)   => hookChild(q, StopS, rest, parents, log)
      case _              => Config(StopS, rest, parents, log)
    }

  /** The end of a run, read off a terminal configuration. */
  def result(c: Config): Obs = c.sig match {
    case OkS(v)  => Obs(ROk(v), c.log)
    case ErrS(t) => Obs(RErr(t), c.log)
    case StopS   => Obs(RCancelled, c.log)
    case Eval(_) => Obs(RCancelled, c.log)
  }
}
