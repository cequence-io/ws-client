---------------------------- MODULE BroadcastHub ----------------------------
(***************************************************************************)
(* AsyncBroadcastHub (ws-client-core): a hot fan-out of one upstream run  *)
(* to any number of consumers. Threads: each consumer's (register,        *)
(* next(), cancel() when its run ends), an outside canceller, the         *)
(* upstream's (the asynchronous completion of a pull), the creator's     *)
(* task that opens the upstream, shutdown(), and the ec that completes    *)
(* the promises a waiting next() holds.                                    *)
(*                                                                         *)
(* The design it checks:                                                   *)
(*  - the ring buffer holds at most `BufferSize` elements: the pull loop   *)
(*    pulls only with room, and room is made by the SLOWEST registered    *)
(*    consumer (`trim`); no consumer is ever behind the oldest element;    *)
(*  - a consumer sees the elements from where it registered, in order,    *)
(*    none lost; every consumer of a completed upstream sees all of them  *)
(*    (a late one only the end);                                           *)
(*  - the FIRST terminal event decides: the upstream's own end or         *)
(*    shutdown(); a result that arrives after it - an element, or a       *)
(*    failure racing the cancel - changes nothing, `completion` and every *)
(*    consumer agree;                                                      *)
(*  - no lost wake-up: whoever frees buffer space (a consumer's next(),   *)
(*    its cancel) makes the hub pull again, and a waiting consumer is     *)
(*    handed the element / the end - every next() completes, a cancel     *)
(*    completes the one outstanding, also one already handed to the ec.   *)
(*                                                                         *)
(* One label is one atomic step: a `lock.synchronized` block, a volatile  *)
(* read, a promise completion, an `ec.execute`. The Lincheck scenarios of *)
(* the real class (HubModelCheck) sample the same races; this is the     *)
(* design.                                                                 *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS
  Consumers,       \* the consumers' names
  BufferSize,
  N,               \* elements the upstream produces (ids 1..N), then its end
  MaxNext,         \* calls of next() a consumer makes at most
  EndsWithError,   \* the upstream fails instead of completing
  Drain,           \* the hub's always-on consumer
  EarlyStop,       \* a consumer may stop between two calls (a take(n)) before the end
  WithShutdown,    \* shutdown() is called at an arbitrary moment
  WithCancel,      \* a thread cancels the first consumer at an arbitrary moment
  SyncPull         \* a pull's result may be available at once (not only later)

First == CHOOSE c \in Consumers : TRUE

(*--algorithm BroadcastHub
variables
  \* ---- guarded by the hub's `lock`
  base = 0, head = 0,                 \* element at offset o is o + 1
  terminal = "none",                  \* "none" | "ok" | "fail": the first terminal event
  termHead = 0,                       \* the head when it was set (an observation)
  pulling = FALSE,
  members = {},                       \* the registered consumers
  offset = [c \in Consumers |-> 0],
  waiting = [c \in Consumers |-> 0],  \* the promise (call number) of a waiting next(), 0: none
  delivering = [c \in Consumers |-> 0],
  lateT = [c \in Consumers |-> "none"],
  cancelledC = [c \in Consumers |-> FALSE],
  \* ---- the upstream run
  upstreamOpen = FALSE,               \* upstreamCursor != null
  upstreamCancelled = FALSE,
  upstreamTaken = 0,                  \* results the cursor handed out
  inFlight = FALSE, inFlightResult = "none",   \* an asynchronous pull
  \* ---- the consumers' promises
  pr = [c \in Consumers |-> [i \in 1..MaxNext |-> "open"]],
  pv = [c \in Consumers |-> [i \in 1..MaxNext |-> 0]],
  ecq = {},                           \* completions handed to the ec: <<c, k, result, value>>
  completion = "open",                \* completionPromise: "open" | "ok" | "fail"
  \* ---- observations
  obs = [c \in Consumers |-> << >>], obsEnd = [c \in Consumers |-> "running"],
  startOff = [c \in Consumers |-> 0], early = [c \in Consumers |-> FALSE],
  finished = [c \in Consumers |-> FALSE], settled = [c \in Consumers |-> FALSE],
  shutdownDone = FALSE, shutdownCalled = FALSE,
  registered = [c \in Consumers |-> FALSE];   \* a consumer can be cancelled once it exists

define
  MinOffset == IF members # {}
                 THEN CHOOSE o \in {offset[c] : c \in members} : \A p \in {offset[c] : c \in members} : o <= p
                 ELSE IF Drain THEN head ELSE base

  \* the next result of the upstream cursor
  UpstreamResult == IF upstreamTaken < N THEN "element"
                    ELSE IF EndsWithError THEN "fail" ELSE "ok"
end define;

\* onUpstream(result): the lock section, then the deliveries, then the completion promise
procedure OnUpstream(res)
variables batch = {}, term = "none";
begin
U1: pulling := FALSE;
    if terminal # "none" then
      skip;                                   \* the first terminal event stands: dropped
    elsif res = "element" then
      head := head + 1;
      batch := {<<c, waiting[c], "some", head>> : c \in {d \in members : waiting[d] # 0}};
      delivering := [c \in Consumers |-> IF c \in members /\ waiting[c] # 0 THEN waiting[c] ELSE delivering[c]];
      offset := [c \in Consumers |-> IF c \in members /\ waiting[c] # 0 THEN offset[c] + 1 ELSE offset[c]];
      waiting := [c \in Consumers |-> IF c \in members THEN 0 ELSE waiting[c]];
    else
      terminal := res;
      termHead := head;
      term := res;
      batch := {<<c, waiting[c], IF res = "ok" THEN "none" ELSE "fail", 0>> : c \in {d \in members : waiting[d] # 0}};
      delivering := [c \in Consumers |-> IF c \in members /\ waiting[c] # 0 THEN waiting[c] ELSE delivering[c]];
      waiting := [c \in Consumers |-> IF c \in members THEN 0 ELSE waiting[c]];
    end if;
U2: ecq := ecq \cup batch;                    \* consumer.deliver(...): ec.execute
U3: if term # "none" /\ completion = "open" then
      completion := term;                     \* completionPromise.tryComplete
    end if;
    return;
end procedure;

\* pullUpstream(): pulls while there is buffer space
\* (local bookkeeping - the loop test, the clearing of a result - shares the step of the next
\* shared access: a local action commutes with every other thread's)
procedure PullUpstream()
variables r = "none";
begin
PU0: if ~upstreamOpen then
       return;
     end if;
PU2: base := MinOffset;                       \* trim(): drop what every consumer has passed
     if ~pulling /\ terminal = "none" /\ head - MinOffset < BufferSize then
       pulling := TRUE;
       r := UpstreamResult;                   \* Cursors.pull(cursor): only the thread that set
       if UpstreamResult = "element" then     \* `pulling` touches the cursor, so this is local
         upstreamTaken := upstreamTaken + 1;
       end if;
     else
       return;
     end if;
PU3: either                                   \* the result is already there ...
       await SyncPull;
       call OnUpstream(r);
     or                                       \* ... or it arrives later
       inFlight := TRUE;
       inFlightResult := r;
       return;
     end either;
PU4: r := "none";                             \* (cleared: fewer states)
     goto PU2;
end procedure;

\* cancel() of a consumer: unregister, complete what is outstanding, refill
procedure CancelC(who)
variables outstanding = {};
begin
Q1: if ~cancelledC[who] then
      cancelledC[who] := TRUE;
      members := members \ {who};
      outstanding := {p \in {waiting[who], delivering[who]} : p # 0};
      waiting[who] := 0;
      delivering[who] := 0;
    end if;
Q3: while outstanding # {} do
      with p \in outstanding do
        outstanding := outstanding \ {p};
        if pr[who][p] = "open" then
          pr[who][p] := "none";                 \* promise.trySuccess(None)
        end if;
      end with;
    end while;
Q4: settled[who] := TRUE;
Q5: call PullUpstream();                       \* ec.execute(pullUpstream)
    return;
end procedure;

\* the creator's task on the ec: open the upstream
fair process opener = "opener"
begin
A1: if terminal = "none" then
      upstreamOpen := TRUE;                   \* upstreamCursor = Cursors.open(...)
    else
      goto ADone;                             \* shut down before this ran: never started
    end if;
A2: if terminal # "none" then
      upstreamCancelled := TRUE;              \* shut down meanwhile
    else
      call PullUpstream();
    end if;
ADone: skip;
end process;

\* the upstream's thread: the asynchronous completion of a pull
fair process upstream = "upstream"
begin
V1: while TRUE do
      either
        await inFlight;
        inFlight := FALSE;
V2:     call OnUpstream(inFlightResult);
V3:     call PullUpstream();                  \* future.onComplete { onUpstream; pullUpstream }
      or
        await \A c \in Consumers : finished[c];
        goto VDone;
      end either;
    end while;
VDone: skip;
end process;

\* a consumer: register, next() until the end (or an early stop), then cancel
fair process consumer \in Consumers
variables k = 0;
begin
R1: if terminal # "none" then                 \* register()
      offset[self] := head;
      lateT[self] := terminal;
      startOff[self] := head;
    else
      offset[self] := base;
      members := members \cup {self};
      startOff[self] := base;
    end if;
    registered[self] := TRUE;
N1: if k >= MaxNext then                      \* (the loop test and the choice are local: they
      goto N5;                                \* share the step of the lock section)
    else
      either
        k := k + 1;
        delivering[self] := 0;                \* next(): lock.synchronized { ... }
        if cancelledC[self] then
          pr[self][k] := "none";
        elsif lateT[self] # "none" then
          pr[self][k] := IF lateT[self] = "ok" THEN "none" ELSE "fail";
        elsif offset[self] < head then
          pr[self][k] := "some";
          pv[self][k] := offset[self] + 1;
          offset[self] := offset[self] + 1;
        elsif terminal # "none" then
          pr[self][k] := IF terminal = "ok" THEN "none" ELSE "fail";
        else
          waiting[self] := k;
        end if;
      or
        await EarlyStop;
        early[self] := TRUE;                  \* the run stops early: a take(n), a cancel
        goto N5;
      end either;
    end if;
N3: call PullUpstream();
N4: await pr[self][k] # "open";
    if pr[self][k] = "some" then
      obs[self] := Append(obs[self], pv[self][k]);
      goto N1;
    else
      obsEnd[self] := pr[self][k];
      goto N5;
    end if;
N5: call CancelC(self);                       \* onTerminate(_ => consumer.cancel())
N6: finished[self] := TRUE;
end process;

\* an outside thread cancels the first consumer's run at an arbitrary moment
\* (no process at all when disabled: a process that merely sits at its end multiplies the states)
fair process canceller \in (IF WithCancel THEN {"canceller"} ELSE {})
begin
K1: await registered[First];               \* the handle exists only after register()
    call CancelC(First);
K2: skip;
end process;

\* shutdown(), at an arbitrary moment
fair process shutdowner \in (IF WithShutdown THEN {"shutdowner"} ELSE {})
variables result = "none", taken = {};
begin
H1: shutdownCalled := TRUE;                   \* lock.synchronized { ... }
    if terminal = "none" then
      terminal := "ok";
      termHead := head;
    end if;
    result := terminal;
    taken := {<<c, waiting[c], "none", 0>> : c \in {d \in members : waiting[d] # 0}};
    delivering := [c \in Consumers |-> IF c \in members /\ waiting[c] # 0 THEN waiting[c] ELSE delivering[c]];
    waiting := [c \in Consumers |-> IF c \in members THEN 0 ELSE waiting[c]];
H2: if upstreamOpen then
      upstreamCancelled := TRUE;              \* cursor.cancel()
    end if;
H3: ecq := ecq \cup taken;                    \* the waiting consumers complete with None
H4: if completion = "open" then
      completion := result;                   \* completionPromise.tryComplete(terminal)
    end if;
H5: shutdownDone := TRUE;
    result := "none";
    taken := {};
end process;

\* the ec: completes the promises handed to it
fair process ec = "ec"
begin
EC: while TRUE do
      either
        with item \in ecq do
          ecq := ecq \ {item};
          if pr[item[1]][item[2]] = "open" then
            pr[item[1]][item[2]] := item[3];
            pv[item[1]][item[2]] := item[4];
          end if;
        end with;
      or
        await (\A c \in Consumers : finished[c]) /\ ecq = {};
        goto ECDone;
      end either;
    end while;
ECDone: skip;
end process;

end algorithm; *)
\* BEGIN TRANSLATION
\* END TRANSLATION

\* at most `BufferSize` elements are buffered
Bounded == head - base <= BufferSize

\* the consumers are interchangeable
Symm == Permutations(Consumers)

\* no registered consumer is behind the oldest buffered element, or ahead of the newest
InRange == \A c \in members : base <= offset[c] /\ offset[c] <= head

\* a consumer sees the elements from where it registered, in order, none lost
Contiguous == \A c \in Consumers : \A i \in 1..Len(obs[c]) : obs[c][i] = startOff[c] + i

\* a consumer that registered after the end sees nothing but the end
NoLateElement == \A c \in Consumers : lateT[c] # "none" => obs[c] = << >>

\* after the first terminal event no element is added: a late upstream result changes nothing
TerminalFreezesHead == terminal # "none" => head = termHead

\* the first terminal event decides: the completion promise agrees with it
FirstTerminalWins == completion # "open" => completion = terminal

\* no consumer sees a failure the upstream did not end with
EndAgrees == \A c \in Consumers : obsEnd[c] = "fail" => terminal = "fail"

\* a consumer that was not cut short and saw the end of an UPSTREAM that completed has seen
\* EVERY element up to the last (shutdown() ends it at once: it may see fewer)
SeesAll ==
  \A c \in Consumers :
    (finished[c] /\ ~early[c] /\ ~shutdownCalled /\ ~(WithCancel /\ c = First)
       /\ obsEnd[c] # "running") =>
      (Len(obs[c]) > 0 => obs[c][Len(obs[c])] = N) /\ obsEnd[c] = IF EndsWithError THEN "fail" ELSE "none"

\* with enough calls (every element and the end) a consumer that was not cut short sees the end:
\* no wake-up is lost, a waiting next() is always handed the element or the end
SeesEnd ==
  \A c \in Consumers :
    (finished[c] /\ ~early[c] /\ ~(WithCancel /\ c = First) /\ MaxNext >= N + 1) =>
      obsEnd[c] # "running"

\* after cancel() completed what it found outstanding, no next() of that consumer is left hanging
CancelSettles == \A c \in Consumers : settled[c] => \A p \in 1..k[c] : pr[c][p] # "open"

AllDone == <>(\A self \in ProcSet : pc[self] = "Done")
=============================================================================
