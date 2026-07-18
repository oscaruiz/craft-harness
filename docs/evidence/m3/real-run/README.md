# m3 B6 — two-pack-lite real run (claude-code), inspector green

One toy task driven end-to-end through the `two-pack-lite` pack with **real
Claude Code** (2.1.214) as coder → cleaner, in upstream's interactive tmux
window mode (adapters/CONTRACT.md Mode 2), under WSL (decisions.md D4). Driven
by `bin/run-pack` with the R10 breakers armed (wake-up cap 8, session timeout
1200s); post-run audit by `bin/inspect-run`.

## Result
`inspect-report.txt` → **RESULT: PASS** (all five checks green):
- (a) mutation never invoked — the star negative assert
- (b) executed CRAP threshold was 6 — read from the durable `logs/wrappers.log`
      (the agent TUI collapses tool stdout; the wrapper writes its own record —
      decisions.md D13)
- (c) commits only on `main`, touching no blacklisted path
- (d) handoff well-formed and consumed (batch mode, `inbox/.../batch_*/`)
- (e) wake-ups 1/8, within the cap

## What the agents did
- **coder**: read task.md, fixed `sut.sh` to print 42, tests pass, committed on
  `main`, queued a `git_handoff` to the cleaner via `swarm_handoff.sh`.
- **cleaner**: consumed the batch via `ready_for_next.sh`, ran the quality
  wrappers (`crap.sh` score 0 / threshold 6 → pass; `dry.sh` pass), found
  nothing to clean, and correctly did not bounce an empty handoff back.

## Files
- `manifest.json` — run provenance (R10): adapter + CLI version, fork commit,
  baseline commit, enforced branch, wake cap/count, duration, outcome.
- `inspect-report.txt` — the inspector's green report.
- `logs/coder.log`, `logs/cleaner.log` — per-window pane captures.
- `logs/wrappers.log` — durable CRAP/DRY output (the (b) evidence).
- `prompts/*.instruction` — the initial per-role instruction prompts.

Context that made this run possible is recorded in `docs/decisions.md`: D9
(faithful tmux mode), D11 (shared workdir / run-pack as daemon), D12 (the
permission envelope — `acceptEdits` + scoped allowedTools), D13 (durable
wrapper evidence + batch handoff recursion).
