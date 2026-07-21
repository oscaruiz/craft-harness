# Using craft-harness

craft-harness drives a bounded coding task through a generated specification,
an explicit human approval, agent implementation, an independent verifier, and
runner-owned commands against the committed candidate. `solo-pack` is the light
path with documental Gherkin. `six-pack` is the domain path with executable
Gherkin and an additional hardening phase.

The launcher installs managed infrastructure; it does not start a workflow.
Run `bin/craft-harness run` once to install a pack, then invoke `bin/run-solo` or
`bin/run-six` from this harness checkout.

## Prerequisites

Run the harness in **WSL Ubuntu**, not native Windows and not Git Bash. The
repository's authoritative suite and demonstrations use WSL (D4). The target
project may live on a mounted Windows drive, but every command declared in
`project.prompt` runs inside WSL.

Install in WSL:

- Bash, Git, and [Babashka](https://babashka.org/) (`bb`).
- One authenticated agent CLI with an adapter under `adapters/`.
- The target project's complete Linux toolchain.

For Maven work, `java` and `mvn` must be executable in WSL. A JDK installed only
on Windows does not satisfy this requirement. D23 found exactly that failure;
the completing run required Linux JDK 21 and Maven installed inside WSL
([decisions.md](decisions.md#d23--the-solo-pipeline-cannot-complete-on-a-real-maven-project-commit-identity-lockout--toolchain-absence-2026-07-19-value-checkpoint)).

The target must be a Git repository with an initial commit, a checked-out
branch, and no tracked working-tree changes. The runner can seed a repository-
local commit identity if none resolves, but it does not provision project build
tools ([run-solo](../bin/run-solo), [run-six](../bin/run-six)).

The adapter contract is CLI-neutral, but certification is not interchangeable:
Claude Code is the only backend that completed the formal canonical scenario.
Codex has a real adapter and has operated on this repository, but its formal
WSL scenario remains blocked by the recorded Codex/WSL initialization failure
(D8). The Claude adapter uses `--dangerously-skip-permissions`; read the D30
boundary below before using it.

Run this repository's suite from WSL:

```bash
cd /mnt/d/Dev/craft-harness
bb test
```

## Prepare the project contract

Before starting, create and commit both `task.md` and `project.prompt` in the
target repository. `task.md` is the human-authored task. `project.prompt` is the
machine-readable execution and scope contract.

This is a realistic Maven-shaped example; replace module paths and commands
with commands that are correct for the project:

```text
# project.prompt
owns:
  src/core/src/main/**
  src/core/src/test/**
  features/**
  acceptance/**

test: mvn -q -pl src/core test

quality:
  architecture: mvn -q -pl src/core -Dtest=*ArchitectureTest test
  duplication: ./tools/check-duplication.sh

accept: ./acceptance/run.sh

mutation:
  tool: pitest
  threshold: 80
  command: mvn -q -pl src/core org.pitest:pitest-maven:mutationCoverage -DoutputFormats=XML && cp "$(find src/core/target/pit-reports -name mutations.xml | sort | tail -1)" "$CRAFT_MUTATION_REPORT"
```

`accept:` must write newline-delimited JSON to the path in
`$CRAFT_ACCEPT_REPORT`, using the schema in
[acceptance-report-schema.md](acceptance-report-schema.md). For example, a
passing record is `{"scenario":"SUT-1","status":"passed"}`. The command may
wrap Cucumber, APS, or another real acceptance engine; the runner consumes the
report schema, not a specific engine.

`mutation:` is an **opt-in** gate (six-pack only; `run-solo` ignores it). When
declared, `run-six` runs the `command` itself at the candidate, reads the **real**
mutation score from the PITest `mutations.xml` the command writes to
`$CRAFT_MUTATION_REPORT`, and fails the run if the score is below the
project-declared `threshold` (0–100). The command is a **reporter** — let PIT exit
0 and report; the harness owns the verdict (PIT can exit 0 with a poor score). Only
`tool: pitest` is supported today. See
[mutation-report-schema.md](mutation-report-schema.md).

### Strict fail-closed rules

[`bin/parse-project`](../bin/parse-project) is authoritative:

- `project.prompt` is required.
- Exactly one non-empty `owns:` block is required. Every entry is one relative,
  whitespace-free glob with no `..`; duplicate entries and malformed blocks
  fail before an agent starts.
- Exactly one non-empty `test:` command is required.
- `quality:` is optional. If present, it must be non-empty and contain unique
  entries in the form `<lowercase-name>: <command>`. Commands execute in the
  declared order.
- `accept:` is optional for `solo-pack` and required for `six-pack`. Duplicate
  or empty declarations fail.
- `mutation:` is optional (six-pack only). If present, it is a block declaring
  exactly `tool:`, `threshold:` (integer 0–100), and `command:`. Duplicate blocks,
  a missing/unknown/duplicate key, an out-of-range or non-numeric threshold, or an
  unsupported tool fail closed.
- Tabs are not allowed in command values because the durable command record is
  tab-separated.

The parser ignores ordinary prose outside these fields. The harness does not
infer commands from Maven, Gradle, npm, or the source tree.

The `owns:` set must include every path the candidate is allowed to add or
change, including tests, executable features, and step handlers. The runner
rejects the complete baseline-to-candidate diff before verification if any path
falls outside that set.

## Install a supported pack

From WSL, install the selected branch's manifest-managed files:

```bash
HARNESS=/mnt/d/Dev/craft-harness
PROJECT=/mnt/d/Dev/my-project

"$HARNESS/bin/craft-harness" run \
  --project "$PROJECT" \
  --pack solo-pack
```

Use `--pack six-pack` for the full path. Both branches are registered in
[`PACKS`](../PACKS) and must satisfy [`MANAGED_FILES.manifest`](../MANAGED_FILES.manifest).
Installation creates an immutable store under `.craft-harness/store/`, stable
managed links, `.craft-harness-version`, and the pre-commit tripwire. It does
not invoke an agent or execute the selected runner.

## Run solo-pack

Solo is `specify -> human gate -> code -> verify -> runner gates -> inspect`.
Its Gherkin is documental review material; scenario-to-test linkage is not an
executable guarantee.

Start the specification phase:

```bash
"$HARNESS/bin/run-solo" \
  --project "$PROJECT" \
  --adapter claude-code \
  --phase-timeout 900 \
  --retry-cap 1 \
  --run-timeout 3600 \
  --command-timeout 900
```

The first invocation:

1. Runs a fresh specifier process.
2. Writes `spec.md` and tagged documental feature files under
   `.craft-harness/solo/current/spec/`.
3. Validates the specifier handoff.
4. Generates an approval token, stores its hash in project session state, keeps
   the same token in runner-private state outside the project, records
   `awaiting_approval`, and exits without running code.

Review the generated `spec.md` and every feature file. Approval remains a human
decision; the convenience command only records it:

```bash
"$HARNESS/bin/craft-harness" approve --project "$PROJECT"
```

`approve` refuses when no gate is pending. It detects the pending solo or six
session, verifies the runner-private token against the existing session hash,
writes the existing `approval/APPROVED` file, and removes the retained plaintext
copy. No token value or private path is passed to a phase prompt or environment.

Resume with the same runner command. The runner then:

1. Verifies the approval hash.
2. Runs a fresh coder in the project checkout and requires a new committed
   candidate on the enforced branch.
3. Rejects any candidate path outside `owns:`.
4. Runs a fresh verifier in a detached worktree at the candidate and rejects
   verifier modifications.
5. Creates another clean candidate worktree and itself executes `test:` followed
   by every ordered `quality:` command. A non-zero exit or timeout fails.
6. Runs `inspect-run` automatically. `SUCCESS` is printed only after inspection
   passes.

Evidence is retained under `.craft-harness/solo/current/`, including prompts,
handoffs, logs, `commands.tsv`, the candidate diff, manifest, and inspector
report.

## Run six-pack

Six-pack is `specify -> human gate -> code -> harden -> qa -> runner gates ->
inspect`. It requires `accept:` and makes approved scenario execution a gate.

Install `six-pack` as shown above, then start:

```bash
"$HARNESS/bin/run-six" \
  --project "$PROJECT" \
  --adapter claude-code \
  --phase-timeout 1200 \
  --retry-cap 1 \
  --run-timeout 7200 \
  --command-timeout 1200
```

The first invocation runs the specifier, requires at least one `@ID` scenario,
snapshots all approved scenario IDs, and pauses before code. Review the spec and
features, then run:

```bash
"$HARNESS/bin/craft-harness" approve --project "$PROJECT"
```

Resume with the same `run-six` command. The runner:

1. Runs code in the project checkout and enforces candidate ancestry, branch,
   and owned scope.
2. Runs harden in the project checkout. If harden commits, the final candidate
   is rechecked for ancestry and scope.
3. Runs fresh QA in a detached worktree at the final candidate and rejects QA
   modifications.
4. Creates a clean gate worktree and executes `test:`, ordered `quality:`, and
   `accept:` itself, plus the `mutation:` command when one is declared.
5. Structurally parses the acceptance report and requires every scenario ID
   snapshotted at the human gate to occur exactly once with status `passed`.
6. When `mutation:` is declared, structurally parses the PITest report, computes
   the real score, and fails the run if it is below the declared `threshold`.
7. Runs `inspect-run` automatically and prints `SUCCESS` only after all checks
   pass.

Evidence is under `.craft-harness/six/current/`. A failed, undefined, missing,
duplicate, or malformed approved scenario record turns the run red, as does a
mutation score below the declared threshold.

## Doctor and upgrade

```bash
"$HARNESS/bin/craft-harness" doctor --project "$PROJECT"
"$HARNESS/bin/craft-harness" upgrade --project "$PROJECT"
```

`doctor` reports healthy (0), outdated (10), modified managed content (20), or
in-flight state (30). `upgrade` refuses in-flight work and atomically replaces
the installed managed tree; it does not update the external CLI, project
dependencies, or project source.

## Honest guarantees and boundaries

The harness guarantees only what its runner can observe:

- The exact declared commands ran against the committed candidate and returned
  zero within their timeouts.
- The complete candidate diff stayed inside `owns:`.
- Verification ran in a fresh detached candidate worktree and did not modify it.
- In six-pack, every human-approved scenario ID was structurally reported
  `passed` by the declared acceptance command.
- When a `mutation:` gate is declared, the declared mutation command ran and its
  real PITest score (killed / viable mutants) met the project-declared threshold.
  This is an executable gate, **not** a correctness proof: a high score means the
  declared tests killed *those* mutants, never that the code is fully correct (D29).

It does **not** guarantee the following:

- **Semantic adequacy (D29).** The harness cannot know whether `test:`,
  `quality:`, or `accept:` reaches the changed behavior. `test: true` is a valid
  but worthless contract. Choosing meaningful commands and thresholds is real
  project work, not something the language-agnostic runner can infer.
- **A mandatory DRY gate (D26).** DRY is advisory unless the project declares a
  real, meaningful duplication command under `quality:`. The toy wrappers are
  test fixtures, not Java or TypeScript quality evidence.
- **Hostile-agent containment (D30).** Phase agents run as the same OS user as
  the runner. A malicious agent can seek runner state or escape the intended
  workflow. The private approval token prevents accidental disclosure through
  prompts and environments; it is not an OS security boundary against a
  malicious same-user process. Container/separate-user isolation is deferred
  v0.2 work.
- **Sandboxed implementation.** Code and harden run in the target project
  checkout. Only verifier/QA and final command execution use detached worktrees.
  Clean-tree checks, the hook, branch checks, and `owns:` constrain and audit the
  resulting commit; they do not sandbox the implementation process.
- **Windows toolchain bridging.** Windows-installed Java, Maven, Node, or other
  tools do not automatically become usable WSL tools. Provision the toolchain
  where the runner executes.
- **Backend interchangeability.** The adapter architecture supports additional
  CLIs, but only Claude Code is formally scenario-certified today (D8).

The process cost is intentional but not universally worthwhile. It is a good
fit for bounded, contract-reviewable changes where independent verification and
repeatable executable gates justify several agent turns: domain behavior,
infrastructure rules, or a scoped Maven feature such as an interceptor. Use a
bare agent for tiny mechanical edits, exploratory debugging, broad migrations,
or work whose scope and executable contract are not yet known.

Failed or interrupted sessions remain visible under `.craft-harness/*/current`.
The approval pause is resumable; arbitrary failed phases are not automatically
replayed. Inspect and preserve evidence before manually removing failed state.
