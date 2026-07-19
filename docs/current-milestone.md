# Milestone 4.6 — Handoff routing must not depend on agent-side env expansion (D21)

Goal: make the phase→phase handoff land where run-solo expects it **without**
relying on the audited agent shell-expanding a runner-provided environment
variable. Surfaced by the myCQRS value checkpoint: the first real `claude-code`
solo run failed at `specify` with `no handoff body written to
.../handoffs/specify.handoff`. The agent had done the spec work correctly but
wrote its handoff to a *guessed* filename (`handoffs/specify.md`) because, under
the adapter's `--permission-mode acceptEdits` confinement, it could not expand
`$SOLO_HANDOFF` — the seeded prompt (`HANDOFF_INSTRUCTIONS`) named the variable,
not a resolved path. Reference: `docs/design-v2.2.md` (R6, the handoff
contract), D14 (the routing header is run-solo's job), and the same class as
D17 (a critical control that lived in prompt text the LLM may not honour).

## Root cause (record as D21)

`bin/run-solo` set `SOLO_HANDOFF` as an environment variable and the seeded
prompt instructed the agent to *"write your handoff to the file named by the
environment variable `$SOLO_HANDOFF`"*. A confined headless agent cannot run a
shell to expand that variable, so the destination is left to a guess. The fake
solo agents (`test/fixtures/solo-agent/*`) read `$SOLO_HANDOFF` directly — they
are plain bash, not confined — so the whole suite was green while the real
adapter deterministically failed. The suite could not see the gap.

## Fix (executable, not a prompt tweak)

The runner injects the **resolved absolute** handoff path *literally* into the
phase prompt, on its own machine-parseable line (`HANDOFF_PATH: <abs>`), so
routing never depends on agent-side variable expansion. `$SOLO_HANDOFF` is kept
as a convenience for agents that *can* read it (belt and suspenders), but it is
no longer load-bearing. `PROJECT` is resolved to an absolute path up front so
the injected path and the runner's read path are byte-identical.

We deliberately do NOT add a "normalize/adopt a stray handoff file" fallback:
adopting a mis-located file would blur attribution (a genuinely broken phase
would look like it succeeded). Mis-routing must still fail, attributed.

## Behaviors (in order)

1. **Negative test — mis-routing fails attributed.** A fake agent that writes
   its handoff to the wrong filename must turn the run red, attributed to the
   phase, naming the missing expected handoff. Pins the fail-closed behaviour.
2. **Positive test — the fix, proven with a confined fake agent.** A fake agent
   that discovers the handoff path **only from the injected prompt line** (never
   from `$SOLO_HANDOFF`) lands the handoff where the runner expects and drives
   the pipeline to green. Before the fix this fixture fails (`no handoff body`);
   after it, it passes. Zero paid runs.
3. **The fix in `bin/run-solo`.** Resolve `PROJECT` to absolute; inject
   `HANDOFF_PATH: <abs>` into each phase prompt; keep `$SOLO_HANDOFF` set.

## Exit criteria

- `bb test` green (full suite, no regressions).
- The confined-agent handoff-routing test passes; the mis-route test fails the
  run attributed.
- `docs/decisions.md` records D21 (defect + fix, same class as D17).
- Merged to `main`. Owner pushes from Windows.
- The real myCQRS checkpoint is NOT re-run until this lands.

## Out of scope
Re-running the myCQRS value checkpoint (that is the owner's call, AFTER this
lands) · any change to what the packs' role prompts *say* (prose stays; we only
change how run-solo tells the agent where to put the handoff) · the adapter's
permission model itself (D2/D12 — unchanged here).
