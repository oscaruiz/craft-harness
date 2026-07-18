# Milestone 1 — Launcher (`bin/craft-harness`)

Goal: `run | upgrade | doctor` + the R9 pre-commit hook, tested against a toy
repo. Full reference: `docs/design-v2.2.md` §1 (R5, R6, R8, R9), §4, §7.

## Behaviors to implement (in order)

1. **Managed-files manifest.** `MANAGED_FILES.manifest` at the fork root: an
   explicit list of paths the launcher materializes/updates inside a project.
   Nothing outside the manifest is ever touched.
2. **`run --project <path> --pack <branch>`:** materializes the manifest
   files from the pack branch, writes `.craft-harness-version` (fork commit),
   installs `hooks/pre-commit`, verifies `task.md` exists (when the pack
   requires it), and refuses with a clear message if `doctor` detects an
   in-flight session.
3. **`upgrade --project <path>`:** staging in a temp directory → validation →
   atomic swap of manifest paths ONLY → version update. If interrupted
   mid-way (simulate with kill in tests), the project remains on the old
   version intact — never hybrid. Refuses while a session is in flight.
4. **`doctor --project <path>`:** reports installed vs. fork version,
   managed-file integrity (hashes), and session state (queues with
   unconsumed handoffs, orphan worktrees, half-finished phases).
5. **`hooks/pre-commit`:** rejects commits touching `task.md`, constitution
   articles, `adapters/` or `.git/hooks` (R9 blacklist), and commits outside
   the enforced working branch. With tests: a forbidden commit MUST fail.

## Exit criteria (all automated under `test/`)
- A fork change → `upgrade` propagates it to the toy project.
- An interrupted `upgrade` leaves no hybrid state (kill-mid-way test).
- An agent commit to a forbidden path: rejected by the hook.
- `run` over an in-flight session: refuses.
- `doctor` distinguishes: healthy / outdated / session in flight / managed
  file locally modified.

## Out of scope for milestone 1
CLI adapters (m2), packs (m3–4), language wrappers (m5–6), R10
timeouts/breakers (they arrive with the first agent run, m3).