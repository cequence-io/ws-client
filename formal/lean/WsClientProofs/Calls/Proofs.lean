import WsClientProofs.Calls.Model

/-!
# The run loop of `AsyncCall` computes the meaning of a plan

`Model.lean` is EXTRACTED from `CallModel.scala` (`sbt leanExtract`): the plans, `denote` (what a
plan means, from the scaladoc of `AsyncCall`), and `step` - `CallFiber`'s run loop as a
transition function. This file proves, for EVERY plan, that the loop started on the plan ends
with `denote`: the same outcome (a value, a failure, cancelled) and the same log of what the
termination hooks saw, in the same order.

The loop has an explicit stack of continuation frames (one per operator, pushed on the way down
to the leaf, popped on the way up) and runs a hook as a child run beside its parent, so the
statement is the one that makes the interpreter stack-safe correct: whatever the nesting of the
plan - an operator in a continuation in a recovery in a hook - the frame stack discipline gives
the compositional meaning.
-/

-- the case proofs share one simp set; each case uses part of it
set_option linter.unusedSimpArgs false

namespace WsClientProofs.Calls

/-- The outcome of a plan as the loop's signal. -/
def sigOf : Res → Sig
  | Res.ROk v => Sig.OkS v
  | Res.RErr t => Sig.ErrS t
  | Res.RCancelled => Sig.StopS

/-- `Steps c c'`: the loop goes from `c` to `c'` in some number of steps. -/
inductive Steps : Config → Config → Prop
  | refl (c : Config) : Steps c c
  | cons {c c' c'' : Config} : Calls.step c = some c' → Steps c' c'' → Steps c c''

theorem Steps.one {c c' : Config} (h : step c = some c') : Steps c c' :=
  Steps.cons h (Steps.refl c')

theorem Steps.trans {a b c : Config} : Steps a b → Steps b c → Steps a c := by
  intro hab
  induction hab with
  | refl _ => exact id
  | cons h _ ih => exact fun hbc => Steps.cons h (ih hbc)

theorem Steps.one_eq {c c' d : Config} (h : step c = some c') (e : c' = d) : Steps c d :=
  e ▸ Steps.one h

/-- One step that the loop takes, to a configuration equal to the goal's up to the
associativity of the log. -/
macro "finish_step" : tactic =>
  `(tactic| (apply Steps.one_eq rfl
             first
               | rfl
               | simp [onOk, onErr, onStop, hookLog, hookChild, hookThrown, afterHookSig, finish,
                   List.append_assoc]))

/-! ## What the hook does to the end of the call it follows -/

/-- `afterHookSig` is `afterHook` on signals - for every outcome of the hook. -/
theorem afterHookSig_sigOf (r h : Res) :
    afterHookSig (sigOf r) (sigOf h) = sigOf (afterHook r h) := by
  cases r <;> cases h <;> rfl

/-! ## The loop on a plan: the frames below stay untouched -/

/-- A hook that is a child run of the plan `q`: the parent waits (`⟨rest, after⟩`), the child
runs on frames of its own, and what it ends with goes on through `afterHookSig`. -/
theorem child_steps {q : Plan}
    (ih : ∀ (frames : List Frame) (parents : List Parent) (log : List Ev),
      Steps ⟨Sig.Eval q, frames, parents, log⟩
        ⟨sigOf (denote q).res, frames, parents, log ++ (denote q).log⟩)
    (rest : List Frame) (after : Sig) (parents : List Parent) (log : List Ev) :
    Steps ⟨Sig.Eval q, [], ⟨rest, after⟩ :: parents, log⟩
      ⟨afterHookSig after (sigOf (denote q).res), rest, parents, log ++ (denote q).log⟩ := by
  refine Steps.trans (ih [] (⟨rest, after⟩ :: parents) log) ?_
  generalize (denote q).res = r
  cases r <;> exact Steps.one rfl

/-- Running a plan with any frames below it, any suspended parents and any log so far ends at
the plan's meaning, with the frames, the parents untouched and the hooks' log extended. -/
theorem eval_steps :
    ∀ (p : Plan) (frames : List Frame) (parents : List Parent) (log : List Ev),
      Steps ⟨Sig.Eval p, frames, parents, log⟩
        ⟨sigOf (denote p).res, frames, parents, log ++ (denote p).log⟩ := by
  intro p
  induction p with
  | Value k v =>
      intro frames parents log
      simpa [denote, sigOf] using Steps.one (c' := ⟨Sig.OkS v, frames, parents, log⟩) rfl
  | Fail k t =>
      intro frames parents log
      simpa [denote, sigOf] using Steps.one (c' := ⟨Sig.ErrS t, frames, parents, log⟩) rfl
  | Defer q ih =>
      intro frames parents log
      exact Steps.cons (c' := ⟨Sig.Eval q, frames, parents, log⟩) rfl
        (by simpa [denote] using ih frames parents log)
  | DeferThrow t =>
      intro frames parents log
      simpa [denote, sigOf] using Steps.one (c' := ⟨Sig.ErrS t, frames, parents, log⟩) rfl
  | Gate =>
      intro frames parents log
      simpa [denote, sigOf] using Steps.one (c' := ⟨Sig.StopS, frames, parents, log⟩) rfl
  | MapAdd up n ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FMapAdd n :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FMapAdd n :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | MapThrow up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FMapThrow t :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FMapThrow t :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | FlatThrow up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FFlatThrow t :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FFlatThrow t :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | MapError up ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FMapError :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FMapError :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | MapErrorThrow up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FMapErrorThrow t :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FMapErrorThrow t :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | RecoverTo up n ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FRecoverTo n :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FRecoverTo n :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | RecoverUndefined up ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FRecoverUndefined :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FRecoverUndefined :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | RecoverThrow up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FRecoverThrow t :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FRecoverThrow t :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | TransformSwap up ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FTransformSwap :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FTransformSwap :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | TransformThrow up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FTransformThrow t :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FTransformThrow t :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | AttemptFold up ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FAttemptFold :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FAttemptFold :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | GuardLog up id ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FGuardLog id :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FGuardLog id :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | GuardThrow up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FGuardThrow t :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FGuardThrow t :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> finish_step
  | FlatInc up n ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FFlatInc n :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FFlatInc n :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r with
      | ROk v =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          apply Steps.cons rfl
          finish_step
      | RErr t =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          finish_step
      | RCancelled =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          finish_step
  | FlatThen up q ihUp ihQ =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ihUp (Frame.FFlatThen q :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FFlatThen q :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r with
      | ROk v =>
          have h2 := ihQ frames parents (log ++ l)
          simp [denote, hd, sigOf, guarded, afterHook, termOf, sequence]
          apply Steps.cons rfl
          simpa [List.append_assoc] using h2
      | RErr t =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          finish_step
      | RCancelled =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          finish_step
  | RecoverWith up q ihUp ihQ =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ihUp (Frame.FRecoverWith q :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FRecoverWith q :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r with
      | ROk v =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          finish_step
      | RErr t =>
          have h2 := ihQ frames parents (log ++ l)
          simp [denote, hd, sigOf, guarded, afterHook, termOf, sequence]
          apply Steps.cons rfl
          simpa [List.append_assoc] using h2
      | RCancelled =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          finish_step
  | TransformWith up qo qe ihUp ihO ihE =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ihUp (Frame.FTransformWith qo qe :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FTransformWith qo qe :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      cases r with
      | ROk v =>
          have h2 := ihO frames parents (log ++ l)
          simp [denote, hd, sigOf, guarded, afterHook, termOf, sequence]
          apply Steps.cons rfl
          simpa [List.append_assoc] using h2
      | RErr t =>
          have h2 := ihE frames parents (log ++ l)
          simp [denote, hd, sigOf, guarded, afterHook, termOf, sequence]
          apply Steps.cons rfl
          simpa [List.append_assoc] using h2
      | RCancelled =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          finish_step
  | GuardFail up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FGuardFail t :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FGuardFail t :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      -- the hook is a child run of `Fail Plain t`: it fails at once
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;>
        (apply Steps.cons rfl; apply Steps.cons rfl; finish_step)
  | GuardRun up q ihUp ihQ =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ihUp (Frame.FGuardRun q :: frames) parents log
      rw [hd] at h1
      refine Steps.cons (c' := ⟨Sig.Eval up, Frame.FGuardRun q :: frames, parents, log⟩) rfl
        (Steps.trans h1 ?_)
      -- the hook is a child run of `q`, whose end goes on through `afterHookSig`
      simp only [denote, hd, guarded]
      cases r with
      | ROk v =>
          have h2 := child_steps ihQ frames (sigOf (Res.ROk v)) parents (log ++ l)
          rw [afterHookSig_sigOf] at h2
          refine Steps.cons
            (c' := ⟨Sig.Eval q, [], ⟨frames, sigOf (Res.ROk v)⟩ :: parents, log ++ l⟩) rfl ?_
          simpa [List.append_assoc] using h2
      | RErr t =>
          have h2 := child_steps ihQ frames (sigOf (Res.RErr t)) parents (log ++ l)
          rw [afterHookSig_sigOf] at h2
          refine Steps.cons
            (c' := ⟨Sig.Eval q, [], ⟨frames, sigOf (Res.RErr t)⟩ :: parents, log ++ l⟩) rfl ?_
          simpa [List.append_assoc] using h2
      | RCancelled =>
          have h2 := child_steps ihQ frames (sigOf Res.RCancelled) parents (log ++ l)
          rw [afterHookSig_sigOf] at h2
          refine Steps.cons
            (c' := ⟨Sig.Eval q, [], ⟨frames, sigOf Res.RCancelled⟩ :: parents, log ++ l⟩) rfl ?_
          simpa [List.append_assoc] using h2
  | Timeout up ih =>
      intro frames parents log
      exact Steps.cons (c' := ⟨Sig.Eval up, frames, parents, log⟩) rfl
        (by simpa [denote] using ih frames parents log)
  | Delay up ih =>
      intro frames parents log
      exact Steps.cons (c' := ⟨Sig.Eval up, frames, parents, log⟩) rfl
        (by simpa [denote] using ih frames parents log)

/-! ## The theorem -/

/-- THE THEOREM. Started on any plan, the run loop reaches a configuration it cannot leave
(`step c = none`: the root run ended) whose outcome and hook log are `denote p`. -/
theorem machine_correct (p : Plan) :
    ∃ c : Config, Steps (init p) c ∧ step c = none ∧ result c = denote p := by
  have h := eval_steps p [] [] []
  rcases hd : denote p with ⟨r, l⟩
  rw [hd] at h
  refine ⟨⟨sigOf r, [], [], [] ++ l⟩, h, ?_, ?_⟩
  · cases r <;> rfl
  · cases r <;> simp [result, sigOf]

/-- The loop is deterministic: two ends reached from one configuration are the same. -/
theorem Steps.det {a b d : Config} (hab : Steps a b) (had : Steps a d)
    (hb : step b = none) (hd : step d = none) : b = d := by
  induction hab generalizing d with
  | refl =>
      cases had with
      | refl => rfl
      | cons h _ => rw [hb] at h; cases h
  | cons h1 _ ih =>
      cases had with
      | refl => rw [hd] at h1; cases h1
      | cons h2 had' =>
          rw [h1] at h2
          cases h2
          exact ih had' hb hd

/-- The end of the loop on `p` is `denote p` whichever end is meant: any configuration the loop
reaches from `p` and cannot leave. Nothing is assumed about the plan - a `Gate` in a hook
included (the tests exclude it, as the real hook is not cancelled: `hooksOk`). -/
theorem machine_result (p : Plan) {c : Config} (h : Steps (init p) c) (hend : step c = none) :
    result c = denote p := by
  obtain ⟨c', hc', hn', hr'⟩ := machine_correct p
  have := Steps.det h hc' hend hn'
  subst this
  exact hr'

end WsClientProofs.Calls
