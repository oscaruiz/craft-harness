# m4 B6 — solo-pack real run (claude-code headless), inspector green

The same toy task as m3, driven through the **solo-pack** sequential runner
(`bin/run-solo`) with **real Claude Code (2.1.214) in headless Mode 1** (`claude
-p`, fresh process per phase — no tmux, no daemon), under WSL. Three phases —
*specify → code+clean → verify* — with the R6 human gate between specify and
code, and the R10 breakers armed (per-phase timeout 400s, retry cap, whole-run
timeout 1200s).

## Result
`inspect-report.txt` → **RESULT: PASS** on the solo checks:
- (a) mutation never invoked — the star negative assert
- (b) executed CRAP threshold was 6, from the durable `logs/wrappers.log` (the
      code agent ran the wrapper via `$CRAFT_CRAP`; decisions.md D15)
- (c) commits only on `main`, touching no blacklisted path
- (d) the three structured handoffs are schema-valid (B1) and consumed
- (f) every Gherkin scenario ID in the approved spec (`@SUT-1..@SUT-4`) is traced
      to the verify-phase check

## The human gate (R6)
The runner paused after specify (`status: awaiting_approval`) and did not run any
code until the **owner approved the spec**. Approval is recorded in
`approval/APPROVED_BY`; the approval token was printed only to the runner's
console, never to the agent, so the audited code/verify agents could not forge
it. The run only advanced to the code phase because a valid approval was present.

## What the phases did
- **specify** (44s): read task.md, wrote `spec/spec.md` (R1–R4) and
  `spec/features/sut_output.feature` (scenarios `@SUT-1..@SUT-4`), handed off. No
  code, no commit.
- **code** (~50s): implemented the spec — `sut.sh` now prints `42` — ran the
  quality wrappers (`crap: threshold=6` / `dry` both pass), committed the
  candidate on `main`, handed off with the commit.
- **verify** (~46s): in a clean git worktree checked out at the **candidate
  commit** (isolated — no prior-phase transcript), ran `./test.sh` and checked
  each scenario ID; handed off the verdict.

## Files
- `manifest.json` — run provenance (R10): adapter + CLI version, fork/baseline/
  candidate commits, enforced branch, per-phase durations, outcome. The phase
  list shows code+verify twice: the first attempt exposed the wrapper-PATH gap
  (D15) — the code agent could not find the gates because Claude's bash tool
  resets PATH — so code+verify were re-run after the fix (env-based wrapper
  paths), reusing the already-approved specify phase. Like m3's B6, a green real
  run took iterating on real-agent frictions the fakes could not surface; none
  were pipeline bugs.
- `inspect-report.txt` — the inspector's green report.
- `logs/{specify,code,verify}.log` — per-phase headless transcripts.
- `logs/wrappers.log` — the durable CRAP/DRY output (the (b) evidence).
- `handoffs/{specify,code,verify}.handoff` — the structured handoffs; run-solo
  owns the routing header, the agent writes the content sections (D14).
- `spec/` — the approved specification and Gherkin.
- `prompts/` — the per-phase instruction prompts.
- `approval/APPROVED_BY` — the owner's approval record (R6).

Context in `docs/decisions.md`: D14 (run-solo owns the handoff header) and D15
(reach the agent's tools by absolute path via env, not PATH).
