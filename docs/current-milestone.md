# Milestone 4.8 — Inherit-env: let a real Maven run complete (closes D23 in code)

Goal: the smallest change that lets `code`+`verify` complete against a real
project, by **inheriting the developer's environment** — not by building an
isolated provisioned sandbox. Owner decision (recorded in D23/D24): the sandbox
is better architecture but a project in itself; prove the value first. This
milestone lands the harness fix as far as fakes can verify it; the one thing it
cannot do is the completing real run — that is environment-bound (a WSL/Windows
toolchain split, see D24), not harness-bound.

## Behaviors (TDD, in order)

1. **Non-toy fixture without identity + failing tests (red).** The Maven-shaped
   fixture (`make-maven-project!`) now carries **no repo-local git identity** (the
   baseline is committed with an ephemeral `-c` identity that isn't stored) and
   runs under a clean, identity-free `HOME` — the real myCQRS condition. New
   tests: a no-identity project's candidate commit succeeds and is authored by
   the seeded identity; an absent test tool makes **verify** fail *attributed*
   (exact `phase 'verify'`, after code produced a candidate — not a bare word
   match, per the D22 coda); a present tool is invoked through the inherited PATH.
2. **Commit-identity seeding** (`bin/run-solo`). `ensure_commit_identity` runs
   before the code phase: if the project resolves no `user.name`/`user.email` in
   any scope, seed `craft-harness <noreply@craft-harness.local>` repo-locally.
   Executable, never a prompt. Existing identity (local or global) is left alone.
3. **Toolchain reachability.** run-solo already passes the inherited PATH through
   to each phase (`PATH="$FORK_ROOT/tools/toy:$PATH"`); m4.8 *pins* the behavior
   (tool present → invoked; tool absent → verify fails attributed). No provision,
   no install — inherit what's present.
4. **Adapter permission relax** (`adapters/claude-code/invoke`). Swap
   `--permission-mode acceptEdits` (which prompts for every Bash command and, in a
   headless turn, denied `mvn`/`git commit`/wrappers — the D23 block) for
   `--dangerously-skip-permissions`. Containment stays the R9 pre-commit hook +
   the `owns:` allowlist + run-solo's owned-scope check (D2/D19), not the CLI
   prompt. Not exercisable by the fake suite; validated by the optional real run.

## Exit criteria

- `bb test` green including the non-toy identity+build fixture (both tool-present
  and tool-absent paths). ✅ 150 tests.
- Harness fix complete and merged to `main`; owner pushes.
- **D23 CLOSED-IN-CODE** — the harness change is done; the completing run is
  environment-bound, not harness-bound.
- **D24** recorded — the value-checkpoint verdict, honest in both directions.
- The completing real myCQRS run is **not** attempted from CI/WSL (no reachable
  toolchain); an optional command is handed to the owner to try from a real
  toolchain context.

## Out of scope (and staying that way)
Any isolated/provisioned sandbox (possible v0.2, not now) · installing or
provisioning a toolchain · m5/m6/anything beyond m4.8 · the staged
QueryInterceptor work and the failed session in myCQRS (left as reference,
untouched).
