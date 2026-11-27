------------------------- MODULE SubscriberCursor ---------------------------
(***************************************************************************)
(* SubscriberCursor (ws-client-core): a Reactive Streams Flow.Subscriber   *)
(* consumed as a Cursor - the bridge INTO the neutral interpreter.         *)
(* Threads: the publisher's (onSubscribe, onNext, onComplete / onError),   *)
(* the consumer's (next()), the run's ec (completes the promise a waiting  *)
(* next() holds), and a canceller (cancel()).                              *)
(*                                                                         *)
(* The design it checks:                                                   *)
(*  - the calls on the subscription (request / cancel) are SERIALIZED     *)
(*    (RS 2.7) by a lock-free work-in-progress loop: a caller records its *)
(*    request or cancel and leaves, whoever owns the loop makes the calls *)
(*    - also when the publisher calls onNext from inside request (3.3);   *)
(*  - demand is `BufferSize` up front, then `Batch` more after each       *)
(*    `Batch` elements handed to the consumer: at most `BufferSize`        *)
(*    elements are ever buffered, and no request is lost;                  *)
(*  - elements arrive in order, none lost or twice; an error comes after  *)
(*    the elements received before it; every next() completes - a cancel  *)
(*    completes the outstanding one with None, also one already handed    *)
(*    over to the ec (the `delivering` window).                            *)
(*                                                                         *)
(* One label is one atomic step: a `lock.synchronized` block, a volatile  *)
(* read or write, an atomic add / getAndSet, a call into the publisher,   *)
(* an `ec.execute`. The Lincheck scenarios of the real class (the model    *)
(* check suite) sample the same races; this spec is the design.            *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS
  BufferSize,      \* the cursor's bufferSize
  N,               \* elements the publisher sends (their ids are 1..N, in order)
  MaxNext,         \* calls of next() the consumer makes at most
  EndsWithError,   \* the publisher ends with onError instead of onComplete
  WithCancel,      \* a thread calls cancel() at an arbitrary moment
  SyncPublisher    \* the publisher may call onNext from inside request() (3.3)

Batch == IF BufferSize \div 2 > 1 THEN BufferSize \div 2 ELSE 1

(*--algorithm SubscriberCursor
variables
  \* ---- guarded by `lock` (every access is inside one atomic step)
  subscribed = FALSE,         \* subscription != null
  queue = << >>,
  done = FALSE, failed = FALSE,
  cancelled = FALSE,
  pending = 0,                \* the promise of the outstanding next() (its call number), 0: none
  delivering = 0,             \* the promise last handed over to the ec, 0: none
  delivered = 0, deliveredAtLast = 0,
  \* ---- the calls on the subscription: a work-in-progress loop
  wip = 0, pendingReq = 0, cancelPending = FALSE, cancelSent = FALSE,
  inCall = 0,                 \* threads inside subscription.request / cancel
  \* ---- the consumer's promises: "open" | "some" | "none" | "fail"
  pr = [i \in 1..MaxNext |-> "open"], pv = [i \in 1..MaxNext |-> 0],
  ecq = {},                   \* completions handed to the ec: <<k, result, value>>
  \* ---- the publisher: its demand, and who is inside one of its signals (RS 1.3)
  pubDemand = 0, pubSent = 0, pubCancelled = FALSE, pubEnded = FALSE,
  emitOwner = "none", emitDepth = 0,
  \* ---- observations
  obs = << >>, obsEnd = "running", cancelCalled = FALSE, overflowHit = FALSE,
  consumerDone = FALSE;

\* requestFromSubscription(m): record, then drain the calls
procedure RequestFrom(m)
begin
R1: pendingReq := pendingReq + m;
R2: call Drain();
R3: return;
end procedure;

\* cancelSubscription()
procedure CancelSubscription()
begin
C1: cancelPending := TRUE;
C2: call Drain();
C3: return;
end procedure;

\* drainSubscriptionCalls(): whoever increments the counter from 0 owns the loop
procedure Drain()
variables missed = 0, n = 0, s = FALSE;
begin
D1: if wip = 0 then
      wip := 1;
      missed := 1;
    else
      wip := wip + 1;                 \* a caller of the loop: it records and leaves
      return;
    end if;
D2: s := subscribed;                  \* lock.synchronized(subscription) != null
    n := 0;
D3: if s /\ cancelPending /\ ~cancelSent then
      cancelSent := TRUE;
      call SubCancel();
    elsif s /\ ~cancelPending then
      n := pendingReq;                \* pendingRequests.getAndSet(0)
      pendingReq := 0;
    end if;
D4: if n > 0 then
      call SubRequest(n);
    end if;
D5: wip := wip - missed;              \* missed = wip.addAndGet(-missed)
    missed := wip;                    \* (the updated value: a PlusCal step is sequential)
    if missed # 0 then
      goto D2;
    end if;
D6: return;
end procedure;

\* the publisher's side of subscription.cancel()
procedure SubCancel()
begin
X1: inCall := inCall + 1;
X2: pubCancelled := TRUE;
X3: inCall := inCall - 1;
X4: return;
end procedure;

\* the publisher's side of subscription.request(m): it registers the demand and may emit
\* synchronously, on this thread, inside the call
procedure SubRequest(m)
begin
Y1: inCall := inCall + 1;
Y2: pubDemand := pubDemand + m;
Y3: if SyncPublisher then
      call EmitSome();
    end if;
Y4: inCall := inCall - 1;
Y5: return;
end procedure;

\* synchronous emission inside request(): any number of elements the demand allows
procedure EmitSome()
begin
E1: either
      await pubDemand > 0 /\ pubSent < N /\ emitOwner \in {"none", self};
      pubDemand := pubDemand - 1;
      pubSent := pubSent + 1;
      emitOwner := self;
      emitDepth := emitDepth + 1;
      call OnNext(pubSent);               \* (the incremented count: this element's id)
E2:   emitDepth := emitDepth - 1;
      if emitDepth = 0 then
        emitOwner := "none";
      end if;
      goto E1;
    or
      return;
    end either;
end procedure;

\* onNext(e)
procedure OnNext(e)
variables handOver = 0, overflow = FALSE, more = 0;
begin
O1: if ~done /\ ~cancelled then       \* lock.synchronized { ... }
      if pending # 0 then
        handOver := pending;
        pending := 0;
        delivering := handOver;
        delivered := delivered + 1;   \* countDelivery()
        if delivered + 1 - deliveredAtLast >= Batch then
          more := Batch;
          deliveredAtLast := delivered + 1;
        end if;
      elsif Len(queue) >= BufferSize then
        overflow := TRUE;
      else
        queue := Append(queue, e);
      end if;
    end if;
O2: if handOver # 0 then
      ecq := ecq \cup {<<handOver, "some", e>>};        \* deliver(...): ec.execute
    elsif overflow then
      overflowHit := TRUE;            \* cannot happen against a compliant publisher
    end if;
O3: if more > 0 then
      call RequestFrom(more);
    end if;
O4: return;
end procedure;

\* terminate(signal)
procedure Terminate(f)
variables handOver = 0;
begin
T1: if ~done then
      done := TRUE;
      failed := f;
      if pending # 0 then
        handOver := pending;
        pending := 0;
        delivering := handOver;
      end if;
    end if;
T2: if handOver # 0 then
      ecq := ecq \cup {<<handOver, IF f THEN "fail" ELSE "none", 0>>};
    end if;
T3: return;
end procedure;

\* the publisher's thread: onSubscribe, the elements it sends on its own, then the end
fair process publisher = "publisher"
begin
S1: await emitOwner = "none";
    emitOwner := "publisher";
    emitDepth := 1;
S2: if subscribed \/ cancelled \/ done then
      pubCancelled := TRUE;           \* a late subscription is cancelled at once (RS 2.5)
    else
      subscribed := TRUE;
    end if;
S3: if subscribed then
      call RequestFrom(BufferSize);
    end if;
S4: emitDepth := emitDepth - 1;
    if emitDepth = 0 then
      emitOwner := "none";
    end if;
P1: while pubSent < N /\ ~consumerDone do
P2:   either
        await pubDemand > 0 /\ pubSent < N /\ emitOwner \in {"none", "publisher"};
        pubDemand := pubDemand - 1;
        pubSent := pubSent + 1;
        emitOwner := "publisher";
        emitDepth := emitDepth + 1;
        call OnNext(pubSent);               \* (the incremented count: this element's id)
P3:     emitDepth := emitDepth - 1;
        if emitDepth = 0 then
          emitOwner := "none";
        end if;
      or
        await consumerDone \/ pubSent >= N;     \* (the others sent the rest, inside request)
      end either;
    end while;
P4: if pubSent = N then
      await emitOwner = "none";
      emitOwner := "publisher";
      emitDepth := 1;
      pubEnded := TRUE;
    else
      goto PDone;
    end if;
P5: call Terminate(EndsWithError);
P6: emitDepth := 0;
    emitOwner := "none";
PDone: skip;
end process;

\* the consumer: next() until the end, one at a time
fair process consumer = "consumer"
variables k = 0, more = 0, stop = FALSE;
begin
N0: while ~stop /\ k < MaxNext do
N1:   k := k + 1;                     \* (a local)
      more := 0;
N1b:  delivering := 0;                \* next(): lock.synchronized { ... }
      if cancelled then
        pr[k] := "none";
      elsif queue # << >> then
        pr[k] := "some";
        pv[k] := Head(queue);
        queue := Tail(queue);
        delivered := delivered + 1;   \* countDelivery()
        if delivered + 1 - deliveredAtLast >= Batch then
          more := Batch;
          deliveredAtLast := delivered + 1;
        end if;
      elsif done then
        pr[k] := IF failed THEN "fail" ELSE "none";
      else
        pending := k;
      end if;
N2:   if more > 0 then
        call RequestFrom(more);
      end if;
N3:   await pr[k] # "open";
      if pr[k] = "some" then
        obs := Append(obs, pv[k]);
      else
        obsEnd := pr[k];
        stop := TRUE;
      end if;
    end while;
N4: consumerDone := TRUE;
end process;

\* the run's ec: completes the promises handed to it
fair process ec = "ec"
begin
EC: while TRUE do
      either
        with item \in ecq do
          ecq := ecq \ {item};
          if pr[item[1]] = "open" then   \* tryComplete: the first result wins
            pr[item[1]] := item[2];
            pv[item[1]] := item[3];
          end if;
        end with;
      or
        await consumerDone /\ ecq = {};
        goto ECDone;
      end either;
    end while;
ECDone: skip;
end process;

\* cancel(), at an arbitrary moment
fair process canceller = "canceller"
variables waiting = 0, handedOver = 0, first = FALSE;
begin
K0: if WithCancel then
K1:   cancelCalled := TRUE;
      first := ~cancelled;
      if ~cancelled then              \* lock.synchronized { ... }
        cancelled := TRUE;
        queue := << >>;
        waiting := pending;
        pending := 0;
        handedOver := delivering;
        delivering := 0;
      end if;
K2:   if first /\ waiting # 0 /\ pr[waiting] = "open" then
        pr[waiting] := "none";        \* waiting.trySuccess(None)
      end if;
K3:   if first /\ handedOver # 0 /\ pr[handedOver] = "open" then
        pr[handedOver] := "none";     \* handedOver.trySuccess(None)
      end if;
K4:   if first then
        call CancelSubscription();
      end if;
    end if;
K5: skip;
end process;

end algorithm; *)
\* BEGIN TRANSLATION
\* END TRANSLATION

\* the end the publisher signals (when the consumer is not cut short)
ExpectedEnd == IF EndsWithError THEN "fail" ELSE "none"

\* RS 2.7: the calls on the subscription never overlap
Serialized == inCall <= 1

\* at most `BufferSize` elements are buffered, and a compliant publisher never overflows them
NoOverflow == ~overflowHit /\ Len(queue) <= BufferSize

\* the consumer sees the elements in order, none lost, none twice
InOrder == \A i \in 1..Len(obs) : obs[i] = i

\* without a cancel the consumer sees EVERY element, then the publisher's own end - an error
\* comes after the elements received before it
EndsAsSent ==
  (consumerDone /\ ~cancelCalled) => (Len(obs) = N /\ obsEnd = ExpectedEnd)

\* with a cancel the end is None (the cancel's) or the publisher's own - never an error the
\* publisher did not send
EndsWithCancel ==
  consumerDone => obsEnd \in {"none", "fail"} /\ (obsEnd = "fail" => EndsWithError)

\* after cancel() returned, the next() it found outstanding - waiting, or already handed over to
\* the ec - is complete (with None unless it won the race): nothing is left hanging
CancelSettles ==
  (pc["canceller"] \in {"K4", "K5", "Done"} /\ first) =>
    \A p \in {waiting, handedOver} \ {0} : pr[p] # "open"

\* a cancel reaches the publisher (the subscription is cancelled, through the loop)
CancelReaches ==
  ((\A self \in ProcSet : pc[self] = "Done") /\ first /\ subscribed) => pubCancelled

\* cancel() drops what is buffered, and later elements are dropped
CancelledQueueEmpty == cancelled => queue = << >>

AllDone == <>(\A self \in ProcSet : pc[self] = "Done")
=============================================================================
