---------------------------- MODULE CallTimeout -----------------------------
(***************************************************************************)
(* The hand-over of a timeout in the run of an AsyncCall                  *)
(* (CallFiber.startTimeout / Suspension, ws-client-core): the upstream is *)
(* a child run raced against a timer, while the PARENT run is suspended   *)
(* on it and may be cancelled.                                             *)
(*                                                                         *)
(* Threads: the loop that sets the step up (suspend, schedule the timer,  *)
(* start the child, check for a cancel that came first), the timer's task *)
(* (it only claims the race and hands over to the ec), the ec task that   *)
(* decides what the timeout does to a child still running, the child's    *)
(* thread (its completion handler), and the caller of cancel().           *)
(*                                                                         *)
(* The design it checks:                                                   *)
(*  - the parent's suspension is resumed EXACTLY ONCE, by whichever of    *)
(*    the child's completion, the timeout and a cancel claims it first    *)
(*    (a compare-and-set), whatever the interleaving - also a cancel      *)
(*    that arrives before the suspension is set up (`checkCancelled`);    *)
(*  - the race has one winner; what the parent is resumed with agrees     *)
(*    with it: the child's outcome, the timeout's failure, or the stop;   *)
(*  - the loser is never lost: a child the timer beat that still ended    *)
(*    with an outcome of its own (it was left to finish, or was already   *)
(*    past its last step) is reported to `onLate` exactly once; a child   *)
(*    that was stopped, or won, is not reported.                           *)
(*                                                                         *)
(* One label is one atomic step: an atomic read, write, getAndSet or      *)
(* compare-and-set, an `ec.execute`. The Lincheck scenarios of the real   *)
(* class (the model check suite, `CallRunModelCheck`) sample the same     *)
(* races; this spec is the design.                                         *)
(***************************************************************************)
EXTENDS Naturals, Sequences, TLC

CONSTANTS
  WithLate,        \* the timeout has an `onLate` (a LateScope guards its futures)
  WithCancel,      \* a thread cancels the parent run at an arbitrary moment
  TimerRefused     \* the scheduler may refuse the timer (the call then runs unlimited)

(*--algorithm CallTimeout
variables
  \* ---- the timeout's step
  race = "Racing",                 \* AtomicInteger: Racing | ChildEnded | TimedOut
  waiting = "susp",                \* AtomicReference[Suspension]: "susp" | "null"
  suspSet = FALSE,                 \* the parent's `current` holds the suspension
  suspState = "open",              \* the suspension's AtomicInteger: open | completed | cancelled
  resumes = 0,                     \* times the parent loop was resumed
  resumeSignal = "none",           \* "child" | "timeout" | "stop"
  timerSet = FALSE, timerCancelled = FALSE, timerFired = FALSE,
  onTimerPending = FALSE,          \* the ec task the timer handed over to
  \* ---- the child run
  childStarted = FALSE, childEnded = FALSE, childOutcome = "none",   \* "own" | "cancelled"
  childCancelRequested = FALSE,
  futureInFlight = FALSE, futureUsed = FALSE, scopeLocked = FALSE,   \* the LateScope
  \* ---- cancel
  cancelRequested = FALSE, cancelWait = FALSE,
  \* ---- reporting
  lateReports = 0, setupDone = FALSE;

\* Suspension.complete(signal): the compare-and-set decides who resumes the parent
procedure Complete(sig)
begin
C1: if suspState = "open" then
      suspState := "completed";
      resumes := resumes + 1;
      resumeSignal := sig;
    end if;
C2: return;
end procedure;

\* timedOut(): the parent, taken from `waiting` once, is completed with the timeout's failure
procedure TimedOut()
variables taken = "null";
begin
T1: taken := waiting;                  \* waiting.getAndSet(null)
    waiting := "null";
T2: if taken = "susp" then
      call Complete("timeout");
    end if;
T3: return;
end procedure;

\* Suspension.cancel(): claims the suspension, cancels what it waits for; the loop resumes with
\* Stop once the child ended
procedure SuspCancel()
begin
X1: if suspState = "open" then
      suspState := "cancelled";
      cancelWait := TRUE;
    end if;
X2: if cancelWait then
      timerCancelled := TRUE;              \* the handle: timer.cancel() ...
      childCancelRequested := TRUE;        \* ... child.cancel()
    end if;
X3: return;
end procedure;

\* the loop: set the step up
fair process loop = "loop"
begin
L1: suspSet := TRUE;                       \* suspend(...): current.set(suspension)
L2: if TimerRefused then
      either
        timerSet := TRUE;                  \* scheduler.scheduleOnce
      or
        skip;                              \* refused: onUnscheduled, the call runs unlimited
      end either;
    else
      timerSet := TRUE;
    end if;
L3: childStarted := TRUE;                  \* child.start()
L4: if cancelRequested then                \* checkCancelled(suspension)
      call SuspCancel();
    end if;
L5: setupDone := TRUE;
end process;

\* the timer's task: it only claims the race and hands over to the ec
fair process timer = "timer"
begin
M1: either
      await timerSet /\ ~timerCancelled;       \* the scheduler runs the task: cancel() can no
                                              \* longer stop it
M2:   if race = "Racing" then                   \* race.compareAndSet(Racing, TimedOut)
        race := "TimedOut";
        onTimerPending := TRUE;
        timerFired := TRUE;
      end if;
    or
      await (race # "Racing" \/ timerCancelled \/ (setupDone /\ ~timerSet)) ;
    end either;
M3: skip;
end process;

\* the ec task onTimer: a Future in flight below cannot be cancelled - the loser is left to
\* finish; otherwise nothing of it may start a request any more, and it is cancelled
fair process onTimer = "onTimer"
begin
O1: either
      await onTimerPending;
O2:   if WithLate /\ futureInFlight then      \* scope.exists(!_.lockIfIdle())
        call TimedOut();
      else
        if WithLate then
          scopeLocked := TRUE;                \* lockIfIdle: locked from now on
        end if;
        childCancelRequested := TRUE;         \* child.cancel()
      end if;
    or
      await childEnded \/ (setupDone /\ ~timerSet) \/ timerCancelled \/ race = "ChildEnded";
    end either;
O3: skip;
end process;

\* the child run, and its completion handler (child.result.onComplete, on the child's thread)
fair process child = "child"
variables won = FALSE, outcome = "none", parent = "null";
begin
D0: await childStarted;
D1: while ~childEnded do
      either                                  \* a step that creates a Future (refused once locked)
        await ~futureInFlight /\ ~futureUsed /\ ~scopeLocked /\ ~childCancelRequested;
        futureInFlight := TRUE;               \* (once: a run has finitely many steps)
        futureUsed := TRUE;
      or
        await futureInFlight;
        futureInFlight := FALSE;
      or                                      \* the run ends with an outcome of its own ...
        await ~futureInFlight;
        childEnded := TRUE;
        childOutcome := "own";
      or                                      \* ... or is stopped by a cancel
        await childCancelRequested;             \* (a Future in flight is abandoned)
        childEnded := TRUE;
        childOutcome := "cancelled";
      end either;
    end while;
D2: outcome := childOutcome;                  \* the handler
    timerCancelled := TRUE;                   \* timer.cancel()
D3: if race = "Racing" then                   \* race.compareAndSet(Racing, ChildEnded)
      race := "ChildEnded";
      won := TRUE;
    else
      won := FALSE;
    end if;
D4: if won then
      parent := waiting;                      \* waiting.getAndSet(null)
      waiting := "null";
    else
      if outcome = "own" /\ WithLate then
        lateReports := lateReports + 1;       \* onLate.foreach(f => f(finished))
      end if;
    end if;
D5: if won then
      if parent = "susp" then
        call Complete("child");
      end if;
    else
      call TimedOut();                        \* the timer won
    end if;
D6: skip;
end process;

\* cancel(), at an arbitrary moment
fair process canceller = "canceller"
begin
K1: if WithCancel then
      cancelRequested := TRUE;
K2:   if suspSet then
        call SuspCancel();                    \* current.get().cancel()
      end if;
    end if;
K3: skip;
end process;

\* whenAllEnded(children)(() => resume(Stop))
fair process afterCancel = "afterCancel"
begin
W1: either
      await cancelWait /\ childEnded;
      resumes := resumes + 1;
      resumeSignal := "stop";
    or
      await suspState = "completed";           \* nobody can claim it for a cancel any more
    end either;
W2: skip;
end process;

end algorithm; *)
\* BEGIN TRANSLATION
\* END TRANSLATION

\* the parent loop is resumed at most once
ResumedOnce == resumes <= 1

\* what it is resumed with agrees with the race
SignalMatches ==
  /\ resumeSignal = "child"   => race = "ChildEnded"
  /\ resumeSignal = "timeout" => race = "TimedOut"
  /\ resumeSignal = "stop"    => cancelRequested

\* the loser is reported at most once, and only a child the timer beat
LateOnce ==
  /\ lateReports <= 1
  /\ lateReports = 1 => (WithLate /\ race = "TimedOut" /\ childOutcome = "own")

\* nothing is lost: once everything ran, the loser that ended with an outcome of its own was
\* reported
NothingLost ==
  ((\A self \in ProcSet : pc[self] = "Done") /\ WithLate /\ race = "TimedOut" /\ childOutcome = "own") =>
    lateReports = 1

\* the parent run is resumed once everything ran
ResumedAtEnd == ((\A self \in ProcSet : pc[self] = "Done")) => resumes = 1

AllDone == <>(\A self \in ProcSet : pc[self] = "Done")
=============================================================================
