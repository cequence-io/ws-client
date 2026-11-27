---------------------------- MODULE CallTraverse ----------------------------
(***************************************************************************)
(* `traverse` in the run of an AsyncCall (CallFiber.Traversal,             *)
(* ws-client-core): child runs, at most `Parallelism` at once, results by *)
(* index. The first failure decides: no further child starts, the ones    *)
(* still running are cancelled, and the failure goes on once they ended.  *)
(* The parent may be cancelled meanwhile.                                  *)
(*                                                                         *)
(* Threads: the parent's loop (starts the first children), each child's   *)
(* thread (its completion handler decides, and launches the next child),  *)
(* and the caller of cancel().                                             *)
(*                                                                         *)
(* The design it checks:                                                   *)
(*  - never more than `Parallelism` children run at once;                  *)
(*  - no child is added once the traversal finished (a failure, a cancel,  *)
(*    the last result): a launch racing the failure adds nothing;         *)
(*  - the parent is resumed EXACTLY ONCE: with all results when every     *)
(*    child succeeded, with the failure of the first child that failed,   *)
(*    after every child that was running ended; or with Stop, after the   *)
(*    cancelled children ended;                                            *)
(*  - an Ok carries every index.                                           *)
(*                                                                         *)
(* One label is one atomic step: a `synchronized` block, an atomic write, *)
(* a compare-and-set. The Lincheck scenarios of the real class (the model *)
(* check suite) sample the same races; this spec is the design.           *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS
  M,               \* children (indices 1..M)
  Parallelism,
  CanFail,         \* a child may fail
  WithCancel       \* a thread cancels the parent at an arbitrary moment

Min(a, b) == IF a < b THEN a ELSE b

(*--algorithm CallTraverse
variables
  \* ---- guarded by the traversal's monitor
  nextIndex = 0, remaining = M, finished = FALSE,
  running = {}, okResults = {}, addedAny = {},
  \* ---- the children
  started = [i \in 1..M |-> FALSE], runEnded = [i \in 1..M |-> FALSE],
  cancelFlag = [i \in 1..M |-> FALSE], failedOwn = [i \in 1..M |-> FALSE],
  addedAfterFinish = FALSE,
  \* ---- the parent's suspension
  suspSet = FALSE, suspState = "open", resumes = 0, resumeSignal = "none",
  cancelRequested = FALSE, endedAtResume = TRUE;

\* Suspension.complete(signal): the compare-and-set decides who resumes the parent
procedure Complete(sig, pending)
begin
C1: if suspState = "open" then
      suspState := "completed";
      resumes := resumes + 1;
      resumeSignal := sig;
      endedAtResume := \A j \in pending : runEnded[j];     \* nothing it waited for still runs
    end if;
C2: return;
end procedure;

\* launch(i): add the child under the monitor unless the traversal finished, then start it
procedure Launch(idx)
variables added = FALSE;
begin
LA1: if ~finished then
       running := running \cup {idx};
       addedAny := addedAny \cup {idx};
       added := TRUE;
     else
       added := FALSE;
     end if;
LA2: if added then
       started[idx] := TRUE;
     end if;
LA3: return;
end procedure;

\* Suspension.cancel(): claim the suspension, cancelAll(), resume Stop once the children ended
procedure SuspCancel()
variables children = {};
begin
X1: if suspState = "open" then
      suspState := "cancelled";
      children := running;                \* cancelAll(): drainLocked()
      finished := TRUE;
      running := {};
    else
      return;
    end if;
X2: cancelFlag := [j \in 1..M |-> IF j \in children THEN TRUE ELSE cancelFlag[j]];
X3: await \A j \in children : runEnded[j];                  \* whenAllEnded(children)
    resumes := resumes + 1;
    resumeSignal := "stop";
    endedAtResume := TRUE;
X4: return;
end procedure;

\* the parent's loop: start the first children
fair process parent = 0
variables li = 1, first = 0;
begin
P1: suspSet := TRUE;                       \* the suspension; traversal.start(owner)
    nextIndex := Min(Parallelism, M);
    first := Min(Parallelism, M);
P2: while li <= first do
      call Launch(li);
P3:   li := li + 1;
    end while;
P4: if cancelRequested then                \* checkCancelled(suspension)
      call SuspCancel();
    end if;
P5: skip;
end process;

\* a child: runs to an outcome, then its completion handler (ended) decides
fair process child \in 1..M
variables out = "none", next = 0, sig = "none", toCancel = {}, wasRunning = {};
begin
H0: either
      await started[self];
    or
      await finished /\ self \notin addedAny;      \* never launched: the traversal is over
      goto HDone;
    end either;
H1: either
      out := "ok";
      runEnded[self] := TRUE;
    or
      await CanFail;
      out := "fail";
      runEnded[self] := TRUE;
      failedOwn[self] := TRUE;
    or
      await cancelFlag[self];              \* stopped by a cancel
      out := "cancelled";
      runEnded[self] := TRUE;
    end either;
H2: if ~finished then                      \* ended(): synchronized { ... }
      if out = "ok" then
        running := running \ {self};
        okResults := okResults \cup {self};
        remaining := remaining - 1;
        if remaining = 0 then
          finished := TRUE;
          sig := "ok";
        elsif nextIndex < M then
          next := nextIndex + 1;
          nextIndex := nextIndex + 1;
        end if;
      else
        sig := "err";
        toCancel := running \ {self};      \* drainLocked(): the others still running
        wasRunning := running \ {self};    \* (what the failure has to wait for)
        finished := TRUE;
        running := {};
      end if;
    else
      running := running \ {self};
    end if;
H3: if sig # "none" then
      cancelFlag := [j \in 1..M |-> IF j \in toCancel THEN TRUE ELSE cancelFlag[j]];
    end if;
H4: if sig # "none" then
      await \A j \in toCancel : runEnded[j];                \* whenAllEnded(toCancel)
      call Complete(sig, wasRunning);                       \* owner.complete(done)
    end if;
H5: if next > 0 then
      call Launch(next);
    end if;
H6: skip;
HDone: skip;
end process;

\* cancel(), at an arbitrary moment
fair process canceller = M + 1
begin
K1: if WithCancel then
      cancelRequested := TRUE;
K2:   if suspSet then
        call SuspCancel();
      end if;
    end if;
K3: skip;
end process;

end algorithm; *)
\* BEGIN TRANSLATION
\* END TRANSLATION

\* at most `Parallelism` children run at once
AtMostParallel == Cardinality({j \in 1..M : started[j] /\ ~runEnded[j]}) <= Parallelism

\* nothing is added once the traversal finished
NoLateLaunch == finished => running = {}

\* the parent is resumed at most once
ResumedOnce == resumes <= 1

\* what it is resumed with: Ok carries every index, an Err is the failure of a child that failed
\* by itself, and it waited for the children that were running
SignalMatches ==
  /\ resumeSignal = "ok"  => okResults = 1..M
  /\ resumeSignal = "err" => \E j \in 1..M : failedOwn[j]
  /\ resumeSignal # "none" => endedAtResume

\* once everything ran the parent was resumed - exactly once
ResumedAtEnd == (\A self \in ProcSet : pc[self] = "Done") => resumes = 1

AllDone == <>(\A self \in ProcSet : pc[self] = "Done")
=============================================================================
