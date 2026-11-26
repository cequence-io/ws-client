------------------------- MODULE PreflightHandover ---------------------------
(***************************************************************************)
(* The hand-over of NeutralRunner.startPreflight (ws-client-core): the     *)
(* preflight of a stream - the request is sent, and the call completes     *)
(* once the first element arrived - cancelled by its caller. Three         *)
(* threads: the queued start (`body`), the pull's completion (`callback`,  *)
(* on the thread that completed the first element) and `cancel()`.         *)
(*                                                                         *)
(* A preflight that is cancelled before it completed must hold NOTHING     *)
(* (the request is aborted), and a cancel that came before the queued      *)
(* start ran must send no request at all. Both are Lincheck scenarios      *)
(* of the real class (PreflightModelCheck); this spec is the design.       *)
(*                                                                         *)
(* Every label is one atomic step of the implementation: a volatile read   *)
(* or write, a getAndSet, a compare-and-set, a call of the cursor.         *)
(***************************************************************************)
EXTENDS Naturals, TLC

(*--algorithm PreflightHandover
variables
  cancelled = FALSE,             \* the AtomicBoolean
  opened = FALSE,                \* the AtomicReference holds the cursor (not handed over)
  promise = "Pending",           \* the result: Pending | Stream | Cancelled
  cursorOpen = FALSE,            \* the request was sent (Cursors.open)
  cursorCancelled = FALSE,       \* Cursor.cancel() was called (idempotent)
  elementArrived = FALSE,        \* the stream's first element
  pulling = FALSE,               \* the pull's completion is registered
  cancelReturned = FALSE,        \* cancel() returned
  bodyStarted = FALSE,
  cancelledBeforeStart = FALSE;  \* cancel() had returned when the queued start began

process body = "body"
variables cur = FALSE;
begin
B1: bodyStarted := TRUE;
    cancelledBeforeStart := cancelReturned;
    if cancelled then                     \* a cancel that came while this was queued
      goto BDone;
    end if;
B2: cursorOpen := TRUE;                   \* Cursors.open: the request is sent
B3: opened := TRUE;                       \* opened.set(cursor)
B4: if cancelled then                     \* the cancel came while the stream opened
      goto B5;
    else
      pulling := TRUE;                    \* Cursors.pull(cursor).onComplete(...)
      goto BDone;
    end if;
B5: cur := opened;                        \* cancelOpened: getAndSet(null) ...
    opened := FALSE;
B6: if cur then
      cursorCancelled := TRUE;            \* ... then cursor.cancel()
    end if;
BDone: skip;
end process;

process producer = "producer"
begin
P1: either
      await cursorOpen;
      elementArrived := TRUE;
    or
      await pc["body"] \in {"BDone", "Done"} /\ ~cursorOpen;     \* nothing was opened: nothing arrives
    end either;
end process;

process callback = "callback"
variables cur2 = FALSE, ok = FALSE;
begin
L0: either
      await pulling /\ (elementArrived \/ cursorCancelled);   \* next() completed
    or
      await pc["body"] \in {"BDone", "Done"} /\ ~pulling;               \* no pull was started
      goto LDone;
    end either;
L1: if cancelled then
      goto Lc1;
    end if;
Ls1: opened := FALSE;                     \* opened.set(null): handed over
Ls2: if promise = "Pending" then          \* promise.trySuccess(stream)
       promise := "Stream";
       ok := TRUE;
     else
       ok := FALSE;
     end if;
Ls3: if ~ok then                          \* the stream nobody got is cancelled
       cursorCancelled := TRUE;
     end if;
     goto LDone;
Lc1: cur2 := opened;                      \* cancelOpened: getAndSet(null) ...
     opened := FALSE;
Lc2: if cur2 then
       cursorCancelled := TRUE;           \* ... then cursor.cancel()
     end if;
Lc3: if promise = "Pending" then          \* promise.tryFailure(cancellation)
       promise := "Cancelled";
     end if;
LDone: skip;
end process;

process canceller = "canceller"
variables cur3 = FALSE;
begin
K1: cancelled := TRUE;
K2: cur3 := opened;                       \* cancelOpened: getAndSet(null) ...
    opened := FALSE;
K3: if cur3 then
      cursorCancelled := TRUE;            \* ... then cursor.cancel()
    end if;
K4: if promise = "Pending" then           \* promise.tryFailure(cancellation)
      promise := "Cancelled";
    end if;
K5: cancelReturned := TRUE;
end process;

end algorithm; *)
\* BEGIN TRANSLATION
\* END TRANSLATION

Finished == \A self \in ProcSet : pc[self] = "Done"

\* a preflight that ended cancelled holds nothing: the request it sent is aborted
NoLeak == (Finished /\ promise = "Cancelled" /\ cursorOpen) => cursorCancelled

\* a cancel() that returned before the queued start began: no request is sent at all
NoRequestAfterCancel == (bodyStarted /\ cancelledBeforeStart) => ~cursorOpen

\* THE WINDOW the docs name, not a property of this design: a stream handed over in the instant
\* its caller cancelled (the caller drops it, unrun) stays open. It is the caller's to release:
\* the loser of a race, a timeout that fired as the first chunk arrived. The config
\* PreflightHandoverWindow expects TLC to find it.
NoDisclaimedStream == (Finished /\ promise = "Stream" /\ cancelReturned) => cursorCancelled
=============================================================================
