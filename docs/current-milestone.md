# Milestone 4 — solo-pack (sequential mode)

Goal: our sequential pack. N phases, each a fresh headless CLI process
(CONTRACT.md Mode 1; wake-up = fresh invoke carrying the message, per D7),
mandatory structured handoffs, verifier isolated in a clean worktree from the
candidate commit, no tmux and no daemon. Reference: `docs/design-v2.2.md`
§1 (R4, R6, R8, R10), §5, §7.4.

## Standing constraints
- D8 (codex) is a v0.1 exit item; it does not block this milestone.
- The specify phase's human gate is exercised FOR REAL in the evidence run:
  the runner pauses and waits for the owner's approval of the spec. Do not
  add any auto-approve path.

## Behaviors to implement (in order)

1. **Structured handoff schema + validator.** Schema doc (fields: done /
   decisions / assumptions / open items / commands executed) + a validating
   helper reused by runner and inspector. Tests: valid handoff passes;
   missing field, empty decisions-with-changes, malformed header each fail
   with the field named.
2. **Sequential runner** (`bin/run-solo --project <path>`). Three phases:
   *specify* (produces spec + documental Gherkin with scenario IDs; pauses
   for the owner's approval — R6), *code+clean* (implements against the
   approved spec; quality via wrappers only), *verify* (see 3). Each phase:
   fresh adapter invoke in its own workdir, structured handoff written and
   validated before the next phase starts; a failed validation stops the run
   with attribution. Queue/handoff state lives in the persistent location
   probed by doctor (D6) — a crash mid-run must be visible as "session in
   flight".
3. **Verifier isolation.** The verify phase runs in a clean worktree checked
   out from the **candidate commit** (the code phase's result), with inputs
   restricted to: approved spec, diff vs. baseline, handoffs, wrapper/test
   outputs. Tests: a planted mismatch (worktree at baseline instead of
   candidate) must turn the run red; workdir contains no transcript or
   scratch from prior phases.
4. **Breakers (R10).** Per-phase timeout (reuse run-with-timeout), a
   phase-retry cap, and a whole-run timeout. Tests: a stuck fake phase is
   killed with attribution; the retry cap trips and stops the run.
5. **Inspector extension.** `inspect-run` learns solo sessions: same negative
   asserts as m3 (no mutation invocation anywhere; executed threshold 6 from
   wrapper logs; commits only on the enforced branch, no blacklisted paths;
   handoffs schema-valid and consumed) plus scenario-ID traceability: every
   Gherkin scenario ID in the approved spec is referenced by at least one
   test or verify-phase check. Suite-tested against fabricated good/bad
   session dirs, including a planted mutation call.
6. **The real run.** The same toy task as m3, through solo-pack with
   claude-code headless. The owner approves the spec at the gate. Evidence
   into `docs/evidence/m4/`: phase logs, handoffs, run manifest, inspector
   report. One run; stop-and-report on environment failure, no retries.

## Exit criteria
- bb suite green, including: invalid handoff rejected with the field named;
  verifier-at-wrong-commit turns red; retry cap trips; planted mutation call
  turns the inspector red.
- Real-run evidence in `docs/evidence/m4/` with the inspector green and the
  human gate exercised (owner approval visible in the session record).
- Milestone merged to main and pushed.

## After this milestone — owner's checkpoints (NOT autonomous work)
The design's two checkpoints belong to the owner, on her real project:
- **Value checkpoint:** first small real feature in myCQRS using whichever
  light path performed better. If the harness does not improve real work,
  stop and rethink before building more.
- **Survival checkpoint:** keep exactly one light path (two-pack-lite or
  solo-pack); archive the other.
Claude Code's job here is only to assist when asked — the verdicts are hers.

## Out of scope for milestone 4
Real language toolchains (m5–6) · six-pack · codex certification (D8, v0.1
exit item) · any upstream edit · auto-approval of the human gate.