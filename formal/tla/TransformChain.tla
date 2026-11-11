---------------------------- MODULE TransformChain ----------------------------
(***************************************************************************)
(* Two chained TransformPublisher stages (ws-client-core,                  *)
(* stream/TransformPublisher.scala - the jdk / zio-http stream pipeline:   *)
(* delimiter framing, then JSON frame parsing):                            *)
(*                                                                         *)
(*     source  ->  stage 1  ->  stage 2  ->  consumer                      *)
(*                                                                         *)
(* A stage delivers its downstream signals under its monitor. Its upstream *)
(* calls - request(1) and cancel - are only recorded where they arise and  *)
(* issued by one serializing loop (flushUpstream), which a frame that      *)
(* still holds the stage's monitor leaves to the thread's outermost frame. *)
(*                                                                         *)
(* Legacy = TRUE is the design up to 1.1.5: the upstream is called under   *)
(* the monitor. Stage 2 calls stage 1 while holding its own monitor, stage *)
(* 1 signals stage 2 while holding its own - the model checker finds the   *)
(* deadlock.                                                               *)
(*                                                                         *)
(* Two threads: the source's (its onNext / onComplete) and the consumer's  *)
(* (request / cancel); the consumer also requests and cancels from inside  *)
(* onNext, on whatever thread delivers. Elements are only counted.         *)
(***************************************************************************)
EXTENDS Naturals, Sequences, TLC

CONSTANTS MaxElems,    \* elements of the source before it completes
          ConOps,      \* request / cancel calls from the consumer's own thread
          Outs,        \* the numbers of outputs a transformer may yield for one input
          EarlyDone,   \* TRUE: a transformer may end the stream early (the [DONE] event)
          Legacy       \* TRUE: upstream calls under the monitor (up to 1.1.5)

Stages == {1, 2}
Threads == {"src", "con"}

(*--algorithm TransformChain
variables
  \* the monitor of a stage (reentrant) and the state it guards
  lock = [i \in Stages |-> "none"],
  depth = [i \in Stages |-> 0],
  queue = [i \in Stages |-> 0],
  demand = [i \in Stages |-> 0],
  upstreamDone = [i \in Stages |-> FALSE],
  terminated = [i \in Stages |-> FALSE],
  upstreamRequested = [i \in Stages |-> FALSE],
  draining = [i \in Stages |-> FALSE],
  \* the upstream calls owed, and the loop that issues them
  owed = [i \in Stages |-> 0],
  cancelOwed = [i \in Stages |-> FALSE],
  upstreamCalls = [i \in Stages |-> 0],
  cancelSent = [i \in Stages |-> FALSE],
  \* the source
  srcDemand = 0,
  srcCancelled = FALSE,
  srcCompleted = FALSE,
  emitted = 0,
  \* the result of a thread's last drain
  ret = [t \in Threads |-> FALSE],
  \* observations
  conRequested = 0,
  conDelivered = 0,
  conTerminal = FALSE,
  userCancelled = FALSE,
  inUpstream = 0;             \* threads inside stage 2's upstream calls

define
  SourceCanEmit == srcDemand > 0 /\ ~srcCancelled /\ emitted < MaxElems
  SourceCanComplete == emitted = MaxElems /\ ~srcCompleted /\ ~srcCancelled
  Finished == pc["con"] = "Done" /\ ~SourceCanEmit /\ ~SourceCanComplete
end define;

macro Acquire(s) begin
  await lock[s] = "none" \/ lock[s] = self;
  lock[s] := self;
  depth[s] := depth[s] + 1;
end macro;

macro Release(s) begin
  if depth[s] = 1 then
    lock[s] := "none";
  end if;
  depth[s] := depth[s] - 1;
end macro;

\* the subscription a stage hands to its downstream: request(1)
procedure SubRequest(s)
begin
Q1: Acquire(s);
    if terminated[s] then
      ret[self] := FALSE;
    else
      demand[s] := demand[s] + 1;
      call Drain(s);
    end if;
Q2: Release(s);
    if Legacy then
      goto Q5;
    elsif ~ret[self] then
      goto Q4;
    end if;
Q3: owed[s] := owed[s] + 1;           \* requestUpstream()
    call Flush(s);
Q4: call Flush(s);
Q5: return;
end procedure;

\* the subscription a stage hands to its downstream: cancel()
procedure SubCancel(s)
begin
C1: if Legacy then
      Acquire(s);
      if terminated[s] then
        goto C4;
      else
        terminated[s] := TRUE;
        if s = 2 then
          userCancelled := TRUE;
          call SubCancel(1);          \* the upstream's cancel, under the monitor
          goto C4;
        else
          srcCancelled := TRUE;
          goto C4;
        end if;
      end if;
    else
      await lock[s] = "none" \/ lock[s] = self;
      ret[self] := ~terminated[s];
      terminated[s] := TRUE;
      if s = 2 /\ ret[self] then
        userCancelled := TRUE;
      end if;
    end if;
C2: if ret[self] then                 \* cancelUpstream()
      cancelOwed[s] := TRUE;
      call Flush(s);
    end if;
C3: call Flush(s);
    return;
C4: Release(s);
    return;
end procedure;

procedure OnNext(s)
variables stop = FALSE;
begin
N1: Acquire(s);
    if terminated[s] \/ upstreamDone[s] then
      ret[self] := FALSE;
      goto N3;
    else
      upstreamRequested[s] := FALSE;
      with n \in Outs do
        queue[s] := queue[s] + n;
      end with;
      either
        skip;
      or
        await EarlyDone;              \* the transformer ends the stream
        upstreamDone[s] := TRUE;
        stop := TRUE;
        if Legacy then
          if s = 2 then
            call SubCancel(1);
          else
            srcCancelled := TRUE;
          end if;
        end if;
      end either;
    end if;
N2: call Drain(s);
N3: Release(s);
    if Legacy then
      goto N7;
    elsif stop then
      goto N4;
    elsif ret[self] then
      goto N5;
    else
      goto N6;
    end if;
N4: cancelOwed[s] := TRUE;            \* cancelUpstream(); no request after an early end
    call Flush(s);
    goto N6;
N5: owed[s] := owed[s] + 1;           \* requestUpstream()
    call Flush(s);
N6: call Flush(s);
N7: return;
end procedure;

procedure OnComplete(s)
begin
O1: Acquire(s);
    if terminated[s] then
      ret[self] := FALSE;
      goto O3;
    else
      upstreamDone[s] := TRUE;
    end if;
O2: call Drain(s);
O3: Release(s);
    if Legacy then
      goto O6;
    elsif ~ret[self] then
      goto O5;
    end if;
O4: owed[s] := owed[s] + 1;
    call Flush(s);
O5: call Flush(s);
O6: return;
end procedure;

\* under the monitor; ret: the upstream is to be asked for one more element
procedure Drain(s)
begin
D1: if draining[s] then
      ret[self] := FALSE;
      return;
    else
      draining[s] := TRUE;
    end if;
D2: if terminated[s] then
      goto D8;
    elsif queue[s] > 0 /\ demand[s] > 0 then
      demand[s] := demand[s] - 1;
      queue[s] := queue[s] - 1;
      if s = 1 then
        call OnNext(2);
        goto D2;
      else
        assert ~conTerminal;
        conDelivered := conDelivered + 1;
      end if;
    elsif queue[s] = 0 /\ upstreamDone[s] then
      terminated[s] := TRUE;
      if s = 1 then
        call OnComplete(2);
        goto D8;
      else
        assert ~conTerminal;
        conTerminal := TRUE;
        goto D8;
      end if;
    elsif queue[s] = 0 /\ demand[s] > 0 /\ ~upstreamRequested[s] then
      upstreamRequested[s] := TRUE;
      if Legacy then
        goto D5;
      else
        goto D9;
      end if;
    else
      goto D8;
    end if;
D3: either                            \* what the consumer does inside onNext
      goto D2;
    or
      conRequested := conRequested + 1;
      call SubRequest(2);
      goto D2;
    or
      call SubCancel(2);
      goto D2;
    end either;
D5: if s = 2 then                     \* legacy: the upstream's request(1), under the monitor
      call SubRequest(1);
    else
      srcDemand := srcDemand + 1;
    end if;
D6: if ~upstreamRequested[s] \/ upstreamDone[s] \/ terminated[s] then
      goto D2;
    end if;
D8: draining[s] := FALSE;
    ret[self] := FALSE;
    return;
D9: draining[s] := FALSE;
    ret[self] := TRUE;
    return;
end procedure;

\* flushUpstream(): one loop at a time issues the owed upstream calls
procedure Flush(s)
variables missed = 0;
begin
F1: if lock[s] = self then            \* holds the monitor: left to the outermost frame
      return;
    elsif upstreamCalls[s] # 0 then   \* a loop is running: it takes the calls over
      upstreamCalls[s] := upstreamCalls[s] + 1;
      return;
    else
      upstreamCalls[s] := 1;
      missed := 1;
    end if;
F2: if owed[s] > 0 /\ ~cancelOwed[s] then
      owed[s] := owed[s] - 1;
      assert ~cancelSent[s];          \* no request after the cancel
      if s = 2 then
        assert inUpstream = 0;        \* rule 2.7
        inUpstream := inUpstream + 1;
        call SubRequest(1);
      else
        srcDemand := srcDemand + 1;
        goto F2;
      end if;
    else
      goto F4;
    end if;
F3: inUpstream := inUpstream - 1;
    goto F2;
F4: if cancelOwed[s] /\ ~cancelSent[s] then
      owed[s] := 0;
      cancelSent[s] := TRUE;
      if s = 2 then
        assert inUpstream = 0;
        inUpstream := inUpstream + 1;
        call SubCancel(1);
      else
        srcCancelled := TRUE;
        goto F6;
      end if;
    else
      if cancelOwed[s] then
        owed[s] := 0;
      end if;
      goto F6;
    end if;
F5: inUpstream := inUpstream - 1;
F6: if upstreamCalls[s] = missed then \* missed = upstreamCalls.addAndGet(-missed)
      upstreamCalls[s] := 0;
      return;
    else
      with left = upstreamCalls[s] - missed do
        upstreamCalls[s] := left;
        missed := left;
      end with;
      goto F2;
    end if;
end procedure;

fair process source = "src"
begin
S1: either
      await SourceCanEmit;
      srcDemand := srcDemand - 1;
      emitted := emitted + 1;
      call OnNext(1);
      goto S1;
    or
      await SourceCanComplete;
      srcCompleted := TRUE;
      call OnComplete(1);
      goto S1;
    or
      await Finished;
      goto Done;
    end either;
end process;

fair process consumer = "con"
variables ops = 0;
begin
U1: either
      await ops < ConOps;
      ops := ops + 1;
      conRequested := conRequested + 1;
      call SubRequest(2);
      goto U1;
    or
      await ops < ConOps;
      ops := ops + 1;
      call SubCancel(2);
      goto U1;
    or
      goto Done;
    end either;
end process;

end algorithm; *)
\* BEGIN TRANSLATION
\* END TRANSLATION

Settled == \A self \in ProcSet : pc[self] = "Done"

\* never more elements than requested (rule 1.1)
WithinDemand == conDelivered <= conRequested

\* nothing is left behind: no monitor held, no upstream call owed, every cancel sent
Flushed ==
  Settled => \A i \in Stages :
    /\ lock[i] = "none"
    /\ upstreamCalls[i] = 0
    /\ owed[i] = 0
    /\ (cancelOwed[i] => cancelSent[i])

\* a cancel reaches the source, unless the source was through already
CancelReachesSource == (Settled /\ userCancelled) => (srcCancelled \/ srcCompleted)

\* no lost request: the consumer's demand is met, or the stream is over
NoStall == Settled => (conTerminal \/ userCancelled \/ conDelivered = conRequested)

AllDone == <>Settled
=============================================================================
