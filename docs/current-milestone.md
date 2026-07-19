# Milestone 4.7 — Project-generic code/verify prompts + kill the fixtures-mirror-the-toy blindness (D22)

Goal: the `code` and `verify` phases must target **the project's own declared
test command**, not the harness's toy `./test.sh`. Surfaced by the myCQRS value
checkpoint: with R6 correctly gating the approved QueryInterceptor task (D21
fixed), the seeded `code`/`verify` prompts still said *"change the code so
./test.sh passes"* and *"Run ./test.sh"* — toy assumptions baked into the
`solo-pack` role prompts. myCQRS has no `./test.sh` (it is Maven/JUnit), so the
implement + verify phases would have chased a nonexistent harness. Reference:
D22, and the recurring root it names (see below).

## The root this milestone attacks

D22 is the **third** defect (D20, D21, D22) with the same cause: the automated
fixtures ARE the toy project, so the suite is structurally blind to real-project
divergence — **"fixtures-mirror-the-toy blindness."** This milestone does not
just fix the prompts; it adds a deliberately **non-toy** fixture so the suite can
finally see this class. Every test added here asks: *would this pass against a
non-toy fixture?*

## Behaviors (TDD, in order)

1. **Non-toy fixture + failing tests (red).** A Maven-shaped project stub
   (`pom.xml`, `src/core/**`, `project.prompt` declaring `test: mvn -q -pl
   src/core test`, approved `task.md`) plus a generic fake agent that discovers
   the test command ONLY from the injected prompt line and runs it, backed by a
   fake `mvn`. Tests: the seeded `code`/`verify` prompts carry the declared
   command and contain **no** `./test.sh`/`sut.sh`; the pipeline drives green via
   that command. Both red before the fix.
2. **Runner injects the declared test command.** `run-solo` reads a strict
   `test:` line from `project.prompt` (single command; absent ⇒ default
   `./test.sh`, backward compatible) and injects it literally as `TEST_CMD:` into
   the `code` and `verify` prompts — the D21 pattern (never rely on agent-side
   parsing of `project.prompt`).
3. **Project-generic role prompts (`solo-pack` branch).** Rewrite
   `swarmforge/roles/{code,verify}.prompt` to reference the declared test command
   (`TEST_CMD` below) instead of `./test.sh`/`sut.sh`.
4. **Wire the real consumer.** Add the structured `test:` line to myCQRS's
   `project.prompt` (it currently carries the command only as prose) so the
   parked checkpoint uses `mvn` on its next run. myCQRS-side change; owner pushes.

## Exit criteria

- `bb test` green (full suite, no regressions).
- The non-toy Maven-shaped fixture drives green through the real pack's
  `code`/`verify` prompts using its declared command; the prompt-content test
  proves no `./test.sh`/`sut.sh` remains. Both were red before the fix.
- `docs/decisions.md` records **D22**, naming the fixtures-mirror-the-toy pattern.
- Harness merged to `main`. Owner pushes (harness `main`, the `solo-pack` branch
  commit, and the myCQRS `project.prompt` change) from Windows.
- The real myCQRS checkpoint is NOT re-run until this lands.

## Out of scope
Re-running the value checkpoint (owner's call, after this lands) · changing what
`specify` does (already project-generic) · the adapter permission model ·
CommandBus/QueryBus code in myCQRS itself (the parked task, untouched).
