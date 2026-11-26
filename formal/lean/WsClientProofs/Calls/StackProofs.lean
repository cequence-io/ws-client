import WsClientProofs.Calls.Proofs

/-!
# The run loop is stack-safe: the frames it holds are bounded by the plan's nesting

`Proofs.lean` shows the machine computes the meaning of a plan. Here: how much stack it needs.
`depth p` counts the operators a plan nests on the way to its leaf - every operator pushes one
frame while its upstream runs - but NOT the calls that follow: a continuation (`flatMap`'s
function, a recovery, a transformation) runs with only the frames below it, which is why a
retry loop or a long chain of `flatMap`s in tail position runs in constant stack. A hook is a
child run on frames of its own, beside its parent's.

`stack_bounded`: running any plan, the loop never holds more frames - the running fiber's and
the waiting parents' together - than the frames it started with plus `depth p`.
Whatever the number of steps.
-/

-- the case proofs share one simp set; each case uses part of it
set_option linter.unusedSimpArgs false

namespace WsClientProofs.Calls

/-- The frames the waiting parents hold. -/
def parentsHeight : List Parent → Nat
  | [] => 0
  | p :: ps => p.frames.length + parentsHeight ps

/-- Every frame the loop holds: the running fiber's and the waiting parents'. -/
def height (c : Config) : Nat := c.frames.length + parentsHeight c.parents

/-- The most frames a plan holds above the frames below it while it runs. -/
def depth : Plan → Nat
  | Plan.Value _ _ => 0
  | Plan.Fail _ _ => 0
  | Plan.Defer q => depth q
  | Plan.DeferThrow _ => 0
  | Plan.Gate => 0
  | Plan.MapAdd up _ => depth up + 1
  | Plan.MapThrow up _ => depth up + 1
  | Plan.FlatInc up _ => depth up + 1
  | Plan.FlatThen up q => max (depth up + 1) (depth q)
  | Plan.FlatThrow up _ => depth up + 1
  | Plan.MapError up => depth up + 1
  | Plan.MapErrorThrow up _ => depth up + 1
  | Plan.RecoverTo up _ => depth up + 1
  | Plan.RecoverUndefined up => depth up + 1
  | Plan.RecoverThrow up _ => depth up + 1
  | Plan.RecoverWith up q => max (depth up + 1) (depth q)
  | Plan.TransformSwap up => depth up + 1
  | Plan.TransformWith up qo qe => max (depth up + 1) (max (depth qo) (depth qe))
  | Plan.TransformThrow up _ => depth up + 1
  | Plan.AttemptFold up => depth up + 1
  | Plan.GuardLog up _ => depth up + 1
  | Plan.GuardFail up _ => depth up + 1
  | Plan.GuardThrow up _ => depth up + 1
  | Plan.GuardRun up q => max (depth up + 1) (depth q)
  | Plan.Timeout up => depth up
  | Plan.Delay up => depth up

/-- `StepsB bound c c'`: the loop goes from `c` to `c'` and never holds more than `bound` frames
in a configuration it leaves. -/
inductive StepsB (bound : Nat) : Config → Config → Prop
  | refl (c : Config) : StepsB bound c c
  | cons {c c' c'' : Config} :
      height c ≤ bound → Calls.step c = some c' → StepsB bound c' c'' → StepsB bound c c''

theorem StepsB.mono {b b' : Nat} {c d : Config} (h : b ≤ b') (hs : StepsB b c d) :
    StepsB b' c d := by
  induction hs with
  | refl _ => exact StepsB.refl _
  | cons hh hstep _ ih => exact StepsB.cons (Nat.le_trans hh h) hstep ih

theorem StepsB.trans {b : Nat} {x y z : Config} : StepsB b x y → StepsB b y z → StepsB b x z := by
  intro hxy
  induction hxy with
  | refl _ => exact id
  | cons hh hstep _ ih => exact fun hyz => StepsB.cons hh hstep (ih hyz)

theorem StepsB.one_eq {b : Nat} {c c' d : Config} (hh : height c ≤ b) (h : step c = some c')
    (e : c' = d) : StepsB b c d :=
  e ▸ StepsB.cons hh h (StepsB.refl c')

/-- A bounded run is a run. -/
theorem StepsB.toSteps {b : Nat} {c d : Config} (h : StepsB b c d) : Steps c d := by
  induction h with
  | refl _ => exact Steps.refl _
  | cons _ hstep _ ih => exact Steps.cons hstep ih

/-- One step, to a configuration equal to the goal's up to the associativity of the log. -/
macro "bstep" : tactic =>
  `(tactic| (apply StepsB.one_eq (by simp [height, parentsHeight, depth, onOk, onErr, onStop, evalStep, hookChild, hookLog, hookThrown, finish] <;> omega) rfl
             first
               | rfl
               | simp [onOk, onErr, onStop, hookLog, hookChild, hookThrown, afterHookSig, finish,
                   List.append_assoc]))

/-- A hook that is a child run of `q`, bounded. -/
theorem child_stack {q : Plan} {b : Nat}
    (ih : ∀ (frames : List Frame) (parents : List Parent) (log : List Ev),
      StepsB (frames.length + parentsHeight parents + depth q) ⟨Sig.Eval q, frames, parents, log⟩
        ⟨sigOf (denote q).res, frames, parents, log ++ (denote q).log⟩)
    (rest : List Frame) (after : Sig) (parents : List Parent) (log : List Ev)
    (hb : rest.length + parentsHeight parents + depth q ≤ b) :
    StepsB b ⟨Sig.Eval q, [], ⟨rest, after⟩ :: parents, log⟩
      ⟨afterHookSig after (sigOf (denote q).res), rest, parents, log ++ (denote q).log⟩ := by
  refine StepsB.trans (StepsB.mono (by simp [parentsHeight] <;> omega)
    (ih [] (⟨rest, after⟩ :: parents) log)) ?_
  generalize (denote q).res = r
  cases r <;> exact StepsB.one_eq (by simp [height, parentsHeight] <;> omega) rfl rfl

/-- Running a plan with any frames below it and any suspended parents: the loop ends at the
plan's meaning and never holds more than `depth p` frames above what it started with. -/
theorem eval_stack :
    ∀ (p : Plan) (frames : List Frame) (parents : List Parent) (log : List Ev),
      StepsB (frames.length + parentsHeight parents + depth p)
        ⟨Sig.Eval p, frames, parents, log⟩
        ⟨sigOf (denote p).res, frames, parents, log ++ (denote p).log⟩ := by
  intro p
  induction p with
  | Value k v =>
      intro frames parents log
      simpa [denote, sigOf, depth] using
        StepsB.one_eq (b := frames.length + parentsHeight parents + 0)
          (c' := ⟨Sig.OkS v, frames, parents, log⟩) (by simp [height]) rfl rfl
  | Fail k t =>
      intro frames parents log
      simpa [denote, sigOf, depth] using
        StepsB.one_eq (b := frames.length + parentsHeight parents + 0)
          (c' := ⟨Sig.ErrS t, frames, parents, log⟩) (by simp [height]) rfl rfl
  | Defer q ih =>
      intro frames parents log
      exact StepsB.cons (c' := ⟨Sig.Eval q, frames, parents, log⟩)
        (by simp [height, depth]) rfl (by simpa [denote, depth] using ih frames parents log)
  | DeferThrow t =>
      intro frames parents log
      simpa [denote, sigOf, depth] using
        StepsB.one_eq (b := frames.length + parentsHeight parents + 0)
          (c' := ⟨Sig.ErrS t, frames, parents, log⟩) (by simp [height]) rfl rfl
  | Gate =>
      intro frames parents log
      simpa [denote, sigOf, depth] using
        StepsB.one_eq (b := frames.length + parentsHeight parents + 0)
          (c' := ⟨Sig.StopS, frames, parents, log⟩) (by simp [height]) rfl rfl
  | MapAdd up n ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FMapAdd n :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FMapAdd n :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | MapThrow up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FMapThrow t :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FMapThrow t :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | FlatThrow up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FFlatThrow t :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FFlatThrow t :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | MapError up ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FMapError :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FMapError :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | MapErrorThrow up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FMapErrorThrow t :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FMapErrorThrow t :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | RecoverTo up n ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FRecoverTo n :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FRecoverTo n :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | RecoverUndefined up ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FRecoverUndefined :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FRecoverUndefined :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | RecoverThrow up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FRecoverThrow t :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FRecoverThrow t :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | TransformSwap up ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FTransformSwap :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FTransformSwap :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | TransformThrow up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FTransformThrow t :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FTransformThrow t :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | AttemptFold up ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FAttemptFold :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FAttemptFold :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | GuardLog up id ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FGuardLog id :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FGuardLog id :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | GuardThrow up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FGuardThrow t :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FGuardThrow t :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;> bstep
  | FlatInc up n ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FFlatInc n :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FFlatInc n :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r with
      | ROk v =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          apply StepsB.cons (by simp [height, parentsHeight, depth, onOk, onErr, onStop, evalStep, hookChild, hookLog, hookThrown, finish] <;> omega) rfl
          bstep
      | RErr t =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          bstep
      | RCancelled =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          bstep
  | FlatThen up q ihUp ihQ =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ihUp (Frame.FFlatThen q :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FFlatThen q :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r with
      | ROk v =>
          have h2 := ihQ frames parents (log ++ l)
          simp [denote, hd, sigOf, guarded, afterHook, termOf, sequence]
          apply StepsB.cons (by simp [height, parentsHeight, depth, onOk, onErr, onStop, evalStep, hookChild, hookLog, hookThrown, finish] <;> omega) rfl
          simpa [List.append_assoc] using StepsB.mono (by simp [depth] <;> omega) h2
      | RErr t =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          bstep
      | RCancelled =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          bstep
  | RecoverWith up q ihUp ihQ =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ihUp (Frame.FRecoverWith q :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FRecoverWith q :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r with
      | ROk v =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          bstep
      | RErr t =>
          have h2 := ihQ frames parents (log ++ l)
          simp [denote, hd, sigOf, guarded, afterHook, termOf, sequence]
          apply StepsB.cons (by simp [height, parentsHeight, depth, onOk, onErr, onStop, evalStep, hookChild, hookLog, hookThrown, finish] <;> omega) rfl
          simpa [List.append_assoc] using StepsB.mono (by simp [depth] <;> omega) h2
      | RCancelled =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          bstep
  | TransformWith up qo qe ihUp ihO ihE =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ihUp (Frame.FTransformWith qo qe :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FTransformWith qo qe :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r with
      | ROk v =>
          have h2 := ihO frames parents (log ++ l)
          simp [denote, hd, sigOf, guarded, afterHook, termOf, sequence]
          apply StepsB.cons (by simp [height, parentsHeight, depth, onOk, onErr, onStop, evalStep, hookChild, hookLog, hookThrown, finish] <;> omega) rfl
          simpa [List.append_assoc] using StepsB.mono (by simp [depth] <;> omega) h2
      | RErr t =>
          have h2 := ihE frames parents (log ++ l)
          simp [denote, hd, sigOf, guarded, afterHook, termOf, sequence]
          apply StepsB.cons (by simp [height, parentsHeight, depth, onOk, onErr, onStop, evalStep, hookChild, hookLog, hookThrown, finish] <;> omega) rfl
          simpa [List.append_assoc] using StepsB.mono (by simp [depth] <;> omega) h2
      | RCancelled =>
          simp [denote, hd, sigOf, guarded, afterHook, termOf]
          bstep
  | GuardFail up t ih =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ih (Frame.FGuardFail t :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FGuardFail t :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      cases r <;> simp [denote, hd, sigOf, guarded, afterHook, termOf] <;>
        (apply StepsB.cons (by simp [height, parentsHeight, depth, onOk, onErr, onStop, evalStep, hookChild, hookLog, hookThrown, finish] <;> omega) rfl
         apply StepsB.cons (by simp [height, parentsHeight, depth, onOk, onErr, onStop, evalStep, hookChild, hookLog, hookThrown, finish] <;> omega) rfl
         bstep)
  | GuardRun up q ihUp ihQ =>
      intro frames parents log
      rcases hd : denote up with ⟨r, l⟩
      have h1 := ihUp (Frame.FGuardRun q :: frames) parents log
      rw [hd] at h1
      refine StepsB.cons (c' := ⟨Sig.Eval up, Frame.FGuardRun q :: frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (StepsB.trans (StepsB.mono (by simp [depth] <;> omega) h1) ?_)
      simp only [denote, hd, guarded]
      cases r with
      | ROk v =>
          have h2 := child_stack ihQ frames (sigOf (Res.ROk v)) parents (log ++ l)
            (b := frames.length + parentsHeight parents + depth (Plan.GuardRun up q))
            (by simp [depth] <;> omega)
          rw [afterHookSig_sigOf] at h2
          refine StepsB.cons
            (c' := ⟨Sig.Eval q, [], ⟨frames, sigOf (Res.ROk v)⟩ :: parents, log ++ l⟩)
            (by simp [height, parentsHeight, depth, onOk, onErr, onStop, evalStep, hookChild, hookLog, hookThrown, finish] <;> omega) rfl ?_
          simpa [List.append_assoc] using h2
      | RErr t =>
          have h2 := child_stack ihQ frames (sigOf (Res.RErr t)) parents (log ++ l)
            (b := frames.length + parentsHeight parents + depth (Plan.GuardRun up q))
            (by simp [depth] <;> omega)
          rw [afterHookSig_sigOf] at h2
          refine StepsB.cons
            (c' := ⟨Sig.Eval q, [], ⟨frames, sigOf (Res.RErr t)⟩ :: parents, log ++ l⟩)
            (by simp [height, parentsHeight, depth, onOk, onErr, onStop, evalStep, hookChild, hookLog, hookThrown, finish] <;> omega) rfl ?_
          simpa [List.append_assoc] using h2
      | RCancelled =>
          have h2 := child_stack ihQ frames (sigOf Res.RCancelled) parents (log ++ l)
            (b := frames.length + parentsHeight parents + depth (Plan.GuardRun up q))
            (by simp [depth] <;> omega)
          rw [afterHookSig_sigOf] at h2
          refine StepsB.cons
            (c' := ⟨Sig.Eval q, [], ⟨frames, sigOf Res.RCancelled⟩ :: parents, log ++ l⟩)
            (by simp [height, parentsHeight, depth, onOk, onErr, onStop, evalStep, hookChild, hookLog, hookThrown, finish] <;> omega) rfl ?_
          simpa [List.append_assoc] using h2
  | Timeout up ih =>
      intro frames parents log
      exact StepsB.cons (c' := ⟨Sig.Eval up, frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (by simpa [denote, depth] using ih frames parents log)
  | Delay up ih =>
      intro frames parents log
      exact StepsB.cons (c' := ⟨Sig.Eval up, frames, parents, log⟩)
        (by simp [height, parentsHeight, depth]) rfl
        (by simpa [denote, depth] using ih frames parents log)

/-- THE STATEMENT. Started on a plan with nothing below it, the run loop holds at most `depth p`
frames at any moment - a plan's continuations add nothing, only the operators it nests on the
way down to its leaf do. -/
theorem stack_bounded (p : Plan) :
    ∃ c : Config, StepsB (depth p) (init p) c ∧ step c = none ∧ result c = denote p := by
  have h := eval_stack p [] [] []
  rcases hd : denote p with ⟨r, l⟩
  rw [hd] at h
  refine ⟨⟨sigOf r, [], [], [] ++ l⟩, ?_, ?_, ?_⟩
  · simpa [init, parentsHeight] using h
  · cases r <;> rfl
  · cases r <;> simp [result, sigOf]

/-! ## What the bound says about the shapes that matter -/

/-- A chain of `n` calls, each the continuation of the one before (`a.flatMap(_ => b.flatMap(...))`
- a loop written through `flatMap`). -/
def chainRight : Nat → Plan
  | 0 => Plan.Value Kind.Plain 0
  | n + 1 => Plan.FlatThen (Plan.Value Kind.Plain 0) (chainRight n)

/-- A retry chain: each attempt fails, and its recovery is the next attempt. -/
def retryChain : Nat → Plan
  | 0 => Plan.Value Kind.Plain 0
  | n + 1 => Plan.RecoverWith (Plan.Fail Kind.Plain n) (retryChain n)

/-- A chain of `n` operators on a leaf, each on the one before (`a.map(f).map(g)...`). -/
def chainLeft : Nat → Plan
  | 0 => Plan.Value Kind.Plain 0
  | n + 1 => Plan.FlatInc (chainLeft n) 1

theorem depth_chainRight (n : Nat) : depth (chainRight n) ≤ 1 := by
  induction n with
  | zero => simp [chainRight, depth]
  | succ n ih => simp only [chainRight, depth]; omega

theorem depth_retryChain (n : Nat) : depth (retryChain n) ≤ 1 := by
  induction n with
  | zero => simp [retryChain, depth]
  | succ n ih => simp only [retryChain, depth]; omega

theorem depth_chainLeft (n : Nat) : depth (chainLeft n) = n := by
  induction n with
  | zero => simp [chainLeft, depth]
  | succ n ih => simp only [chainLeft, depth]; omega

/-- A loop through `flatMap` or `recoverWith` - however many times it goes round - runs with one
frame on the stack. -/
theorem constant_stack (p : Plan) (hp : depth p ≤ 1) :
    ∃ c : Config, StepsB 1 (init p) c ∧ result c = denote p := by
  obtain ⟨c, h, _, hr⟩ := stack_bounded p
  exact ⟨c, StepsB.mono hp h, hr⟩

theorem chainRight_constant_stack (n : Nat) :
    ∃ c : Config, StepsB 1 (init (chainRight n)) c ∧ result c = denote (chainRight n) :=
  constant_stack _ (depth_chainRight n)

theorem retryChain_constant_stack (n : Nat) :
    ∃ c : Config, StepsB 1 (init (retryChain n)) c ∧ result c = denote (retryChain n) :=
  constant_stack _ (depth_retryChain n)

end WsClientProofs.Calls
