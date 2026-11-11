------------------------------ MODULE ZioCursor ------------------------------
(***************************************************************************)
(* The demand hand-over of ZioCursor (ws-client-zio, ZioInterop.scala):    *)
(* one fiber runs a ZIO stream for the whole cursor run; next() hands it   *)
(* the demand for one chunk through an atomic state (Idle | Demand |       *)
(* Parked | Closed) and a promise (`asked`); cancel() may race everything. *)
(*                                                                         *)
(* Every label is one atomic step of the implementation: a volatile read   *)
(* or write, a compare-and-set (a CAS retry loop is one step that matches  *)
(* on the current state), a promise completion.                            *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS MaxNext,     \* next() calls the consumer makes at most
          MaxChunks,   \* chunks the stream has before it ends
          Cancellers   \* threads that may call cancel() at any time

(*--algorithm ZioCursor
variables
  state = "Idle",           \* the AtomicReference
  cancelled = FALSE,
  ended = "Stopped",        \* how the run ended: Stopped | End | Fail
  asked = 0,                \* the promise of the outstanding next(); 0: none yet
  promise = [p \in 1..MaxNext |-> "Pending"],   \* Pending | Chunk | Stopped | End | Fail
  resumed = FALSE,          \* the parked fiber's resume callback was called
  interrupted = FALSE,      \* fiber.cancel() was called
  scopeClosed = FALSE,
  demands = 0,              \* demands handed to the fiber
  pulls = 0,                \* pulls the fiber started
  chunks = 0;               \* chunks the stream produced

macro TryComplete(p, v) begin
  if p # 0 /\ promise[p] = "Pending" then
    promise[p] := v;
  end if;
end macro;

procedure Cancel()
variables prev = "", pend = 0;
begin
C1: cancelled := TRUE;
C2: prev := state;                    \* state.getAndSet(Closed)
    state := "Closed";
C3: if prev = "Closed" then
      goto C6;
    else
      pend := asked;
    end if;
C4: TryComplete(pend, "Stopped");
C5: interrupted := TRUE;              \* fiber.cancel()
C6: return;
end procedure;

fair process fiber = "fiber"
variables outcome = "none", sawCancelled = FALSE, fpend = 0;
begin
Open:                                 \* stream.toPull, interruptible
  either
    await interrupted;
    goto Close;
  or
    outcome := "Fail";
    goto Close;
  or
    skip;
  end either;
Await:                                \* awaitDemand: CAS(Demand, Idle), else ZIO.async
  either
    await interrupted;
    goto Close;
  or
    if state = "Demand" then
      state := "Idle";
      goto Pull;
    end if;
  end either;
Park:                                 \* park(parked)
  either
    await interrupted;
    goto Close;
  or
    if state = "Demand" then
      state := "Idle";
      goto Pull;
    elsif state = "Idle" then
      state := "Parked";
      resumed := FALSE;
    end if;                           \* Closed: never resumed, the fiber is being interrupted
  end either;
Parked:
  either
    await interrupted;
    goto Close;
  or
    await resumed;
    resumed := FALSE;
  end either;
Pull:                                 \* pull.repeatUntil(_.nonEmpty)
  pulls := pulls + 1;
  either
    await interrupted;
    goto Close;
  or
    await chunks < MaxChunks;
    chunks := chunks + 1;
  or
    await chunks = MaxChunks;
    outcome := "End";
    goto Close;
  or
    outcome := "Fail";
    goto Close;
  end either;
Deliver:                              \* asked.trySuccess(Some(chunk))
  TryComplete(asked, "Chunk");
  goto Await;
Close:                                \* scope.close, uninterruptible
  scopeClosed := TRUE;
F1: sawCancelled := cancelled;        \* finish, on the run's ec
F2: assert sawCancelled \/ outcome # "none";
    ended := IF sawCancelled THEN "Stopped" ELSE outcome;
F3: state := "Closed";
F4: fpend := asked;
F5: TryComplete(fpend, ended);
end process;

fair process consumer = "consumer"
variables calls = 0, last = "Chunk";
begin
Loop:
  if calls < MaxNext /\ last = "Chunk" then
    calls := calls + 1;
  else
    goto Stop;
  end if;
N0: if cancelled then                 \* Cursors.NoneFuture
      last := "Stopped";
      goto Loop;
    end if;
N1: asked := calls;                   \* written before the demand is signalled
N2: if state = "Idle" then            \* signal(demand)
      state := "Demand";
      demands := demands + 1;
      goto N4;
    elsif state = "Parked" then
      state := "Idle";
      demands := demands + 1;
    elsif state = "Closed" then
      goto N3;
    else
      assert FALSE;                   \* Demand: one next() at a time
    end if;
N2r: resumed := TRUE;                 \* parked.resume
     goto N4;
N3: TryComplete(calls, ended);
N4: await promise[calls] # "Pending";
    last := promise[calls];
    goto Loop;
Stop:                                 \* a consumer that stops before the end cancels
  if last = "Chunk" then
    call Cancel();
  end if;
end process;

fair process canceller \in Cancellers
begin
X0: either
      goto X2;
    or
      skip;
    end either;
X1: call Cancel();
X2: skip;
end process;

end algorithm; *)
\* BEGIN TRANSLATION
\* END TRANSLATION

Promises == 1..MaxNext

\* the fiber never pulls ahead of the demand
NoReadAhead == pulls <= demands

\* the end (or the failure) of the stream is signalled after the scope closed
EndAfterScope == \A p \in Promises : promise[p] \in {"End", "Fail"} => scopeClosed

\* only a cancel stops a run
StoppedOnlyByCancel == \A p \in Promises : promise[p] = "Stopped" => cancelled

\* without a cancel every chunk the stream produced reaches the consumer
NoChunkLost ==
  ~cancelled =>
    chunks = Cardinality({p \in Promises : promise[p] = "Chunk"})
               + (IF pc["fiber"] = "Deliver" THEN 1 ELSE 0)

\* the cancel() that closed the cursor (the first one, and before the run ended by itself)
\* leaves no next() outstanding when it returns - except one that is still before its signal:
\* it sees Closed there and completes itself (the Dekker pair `asked` / `state`). Any other
\* cancel() returns at once; the completion is then the first cancel's, or the run's own end
CancelCompletes ==
  \A t \in Cancellers \cup {"consumer"} :
    (pc[t] = "C6" /\ prev[t] \notin {"", "Closed"} /\ asked # 0 /\ promise[asked] = "Pending")
      => pc["consumer"] \in {"N2", "N3"}

\* every next() completes, the fiber ends, the scope closes
AllDone == <>(\A self \in ProcSet : pc[self] = "Done")
ScopeCloses == <>scopeClosed
=============================================================================
