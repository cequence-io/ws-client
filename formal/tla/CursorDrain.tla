----------------------------- MODULE CursorDrain -----------------------------
(***************************************************************************)
(* The work-in-progress drain loop of CursorSubscription (ws-client-core,  *)
(* stream/impl/CursorPublisher.scala): the bridge out of the neutral       *)
(* interpreter into a Flow.Publisher.                                      *)
(*                                                                         *)
(* Whoever takes `wip` from 0 to 1 runs the loop; everyone else only       *)
(* registers. The start task holds the loop while onSubscribe and the open *)
(* run. A request from outside the loop drains on the run's ec; a cancel   *)
(* and an asynchronous result drain inline. The loop may hand itself over  *)
(* to a fresh ec task (the yield every 1024 synchronous results), keeping  *)
(* its wip count. One result is pulled ahead of the demand.                *)
(*                                                                         *)
(* A label is one atomic step: one read or write of shared state (a        *)
(* volatile field, an atomic counter), together with the thread-local      *)
(* work around it.                                                         *)
(***************************************************************************)
EXTENDS Naturals, Sequences, TLC

CONSTANTS MaxElems,    \* elements of the run before it ends
          SubOps,      \* request / cancel calls from the subscriber's own thread
          MaxYields,   \* hand-overs of the loop to a fresh ec task
          Workers,     \* threads of the run's ec
          Starter,     \* the worker that runs the start task
          BadRequests  \* TRUE: the subscriber may also call request(n <= 0) (rule 3.9)

ASSUME Starter \in Workers

(*--algorithm CursorDrain
variables
  wip = 0,
  demand = 0,
  cancelled = FALSE,
  invalidRequest = FALSE,
  asyncResult = "null",       \* null | elem | end
  cursorOpen = FALSE,
  cursorCancelled = FALSE,
  subscriberRef = TRUE,
  \* accessed only inside the loop
  done = FALSE,
  inFlight = FALSE,
  hasHeld = FALSE,
  \* the environment
  pendingDrains = 0,          \* drain tasks queued on the ec
  pendingPull = FALSE,        \* an asynchronous next() is outstanding
  produced = 0,
  subscribed = FALSE,
  yields = 0,
  \* observations
  requested = 0,
  delivered = 0,
  terminalSent = FALSE,
  userCancelled = FALSE,
  active = 0,                 \* threads inside the loop
  inSignal = 0;               \* threads inside a subscriber signal

define
  Quiet ==
    /\ wip = 0
    /\ pendingDrains = 0
    /\ ~pendingPull
    /\ pc["sub"] = "Done"
    /\ pc["src"] \in {"S1", "Done"}
    /\ \A w \in Workers : pc[w] \in {"W1", "Done"}
end define;

procedure Request()
begin
R1: demand := demand + 1;
    requested := requested + 1;
R2: if wip = 0 then                   \* wip.getAndIncrement() == 0: the loop runs on the ec
      wip := 1;
      pendingDrains := pendingDrains + 1;
    else
      wip := wip + 1;
    end if;
    return;
end procedure;

procedure BadRequest()                \* request(n <= 0)
begin
V1: invalidRequest := TRUE;
V2: if wip = 0 then
      wip := 1;
      pendingDrains := pendingDrains + 1;
    else
      wip := wip + 1;
    end if;
    return;
end procedure;

procedure Cancel()
begin
K1: if cancelled then
      return;
    end if;
K2: cancelled := TRUE;
    userCancelled := TRUE;
K3: if cursorOpen then                \* the cursor, read after the flag was written
      cursorCancelled := TRUE;
    end if;
K4: if wip = 0 then                   \* drain(): inline
      wip := 1;
      call DrainLoop();
      return;
    else
      wip := wip + 1;
      return;
    end if;
end procedure;

procedure DrainLoop()
variables missed = 0, c = FALSE, ready = "null", bad = FALSE;
begin
DL0: active := active + 1;
     missed := 1;
DL1: either                           \* the budget is used up: continue on a fresh ec task
       await yields < MaxYields;
       yields := yields + 1;
       pendingDrains := pendingDrains + 1;
       active := active - 1;
       return;
     or
       c := cancelled;
     end either;
DL2: if c then
       subscriberRef := FALSE;        \* rule 3.13
       goto DLX;
     elsif done then
       goto DLX;
     else
       bad := invalidRequest;
       ready := asyncResult;
     end if;
DL3: if bad then                      \* rule 3.9: the run is cancelled, onError goes out
       done := TRUE;
       cancelled := TRUE;
       goto ERR;
     elsif ready # "null" then        \* the outstanding next() has completed
       asyncResult := "null";
       inFlight := FALSE;
       if ready = "elem" then
         hasHeld := TRUE;             \* an element is held until there is demand
         goto DL1;
       else
         done := TRUE;
         goto CMP;
       end if;
     elsif hasHeld then
       if demand > 0 then
         hasHeld := FALSE;
         demand := demand - 1;
         goto NXT;
       else
         goto DLX;
       end if;
     elsif ~inFlight /\ cursorOpen then
       either                         \* the next() is already completed
         if cursorCancelled \/ produced = MaxElems then
           done := TRUE;
           goto CMP;
         else
           produced := produced + 1;
           hasHeld := TRUE;
           goto DL1;
         end if;
       or                             \* it completes later, on another thread
         inFlight := TRUE;
         pendingPull := TRUE;
         goto DLX;
       end either;
     else
       goto DLX;
     end if;
ERR: if cursorOpen then               \* the cursor, read after the flag was written
       cursorCancelled := TRUE;
     end if;
     if subscriberRef then            \* onError, even though cancelled
       assert ~terminalSent /\ inSignal = 0;
       terminalSent := TRUE;
     end if;
     goto DL1;
CMP: if subscriberRef /\ ~cancelled then          \* onComplete
       assert ~terminalSent /\ inSignal = 0;
       terminalSent := TRUE;
     end if;
     goto DL1;
NXT: if subscriberRef /\ ~cancelled then          \* onNext
       assert ~terminalSent /\ inSignal = 0;
       inSignal := inSignal + 1;
       delivered := delivered + 1;
     else
       goto DL1;
     end if;
NX1: either                           \* what the subscriber does inside onNext
       inSignal := inSignal - 1;
       goto DL1;
     or
       call Request();
     or
       call Cancel();
     or
       await BadRequests;
       call BadRequest();
     end either;
NX2: inSignal := inSignal - 1;
     goto DL1;
DLX: if wip = missed then             \* missed = wip.addAndGet(-missed): the loop is left
       wip := 0;
       active := active - 1;
       return;
     else
       with left = wip - missed do
         wip := left;
         missed := left;
       end with;
       goto DL1;
     end if;
end procedure;

fair process worker \in Workers
begin
W0: if self # Starter then
      goto W1;
    end if;
B1: wip := wip + 1;                   \* the start task holds the loop
    assert inSignal = 0;              \* onSubscribe
    inSignal := inSignal + 1;
    subscribed := TRUE;
B2: either                            \* what the subscriber does inside onSubscribe
      skip;
    or
      call Request();
    or
      call Cancel();
    or
      await BadRequests;
      call BadRequest();
    end either;
B3: inSignal := inSignal - 1;
    if cancelled \/ invalidRequest then    \* over inside onSubscribe: the run is never opened
      goto B6;
    end if;
B4: cursorOpen := TRUE;               \* cursor = opener.open(ctx)
B5: if cancelled then                 \* cancelled meanwhile
      cursorCancelled := TRUE;
    end if;
B6: call DrainLoop();                 \* releases the count taken above
W1: await pendingDrains > 0 \/ Quiet;
    if pendingDrains > 0 then
      pendingDrains := pendingDrains - 1;
      call DrainLoop();
      goto W1;
    else
      goto Done;
    end if;
end process;

fair process sub = "sub"
variables ops = 0;
begin
U0: await subscribed;
U1: either
      await ops < SubOps;
      ops := ops + 1;
      call Request();
      goto U1;
    or
      await ops < SubOps;
      ops := ops + 1;
      call Cancel();
      goto U1;
    or
      await BadRequests /\ ops < SubOps;
      ops := ops + 1;
      call BadRequest();
      goto U1;
    or
      goto Done;
    end either;
end process;

fair process src = "src"
begin
S1: await pendingPull \/ Quiet;
    if pendingPull then               \* the outstanding next() completes
      pendingPull := FALSE;
      if cursorCancelled \/ produced = MaxElems then
        asyncResult := "end";
      else
        produced := produced + 1;
        asyncResult := "elem";
      end if;
    else
      goto Done;
    end if;
S2: if wip = 0 then                   \* drain(): inline
      wip := 1;
      call DrainLoop();
      goto S1;
    else
      wip := wip + 1;
      goto S1;
    end if;
end process;

end algorithm; *)
\* BEGIN TRANSLATION
\* END TRANSLATION

\* one loop at a time, one signal at a time (rules 1.3, 2.7), and never two queued loop tasks
Exclusive == active <= 1 /\ inSignal <= 1 /\ pendingDrains <= 1

\* never more elements than requested (rule 1.1)
WithinDemand == delivered <= requested

\* no lost wake-up: when nothing runs and nothing is scheduled, the run is over, or its next
\* result waits for demand (one result is pulled ahead)
NoStall == Quiet => (cancelled \/ terminalSent \/ (hasHeld /\ demand = 0))

\* a cancel reaches the run and drops the subscriber (rules 3.5, 3.13)
CancelSettles == (Quiet /\ cancelled) => (~subscriberRef /\ (cursorOpen => cursorCancelled))

\* a non-positive request is answered with onError, unless the run was over already (rule 3.9)
Rule39 == (Quiet /\ invalidRequest) => (terminalSent \/ userCancelled)

AllDone == <>(\A self \in ProcSet : pc[self] = "Done")
=============================================================================
