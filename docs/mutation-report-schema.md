# Mutation report schema (m7, D35 — the opt-in mutation gate)

The six-pack's mutation gate is **opt-in per project** and **runner-owned**: when
`project.prompt` declares a `mutation:` block, `run-six` executes the declared
mutation command **itself** in a fresh candidate worktree, then reads the **real
mutation score** from a machine-readable report and fails the run if the score is
below the **project-declared** threshold. The project owns the standard; the
harness owns execution and the verdict. `run-solo` ignores the block entirely
(proportionality — daily work does not pay the mutation cost).

This is an **executable gate, not a semantic-adequacy guarantee** (D29 still holds):
a high score means the tests killed *these* mutants, never that the code is fully
correct. See D35.

## The contract block

```text
mutation:
  tool: pitest
  threshold: 80
  command: mvn -q -pl src/core org.pitest:pitest-maven:mutationCoverage
```

Parsed by [`bin/parse-project`](../bin/parse-project) into a single
`MUTATION<TAB>tool<TAB>threshold<TAB>command` record. Fail-closed rules (all stop
the run before any agent starts):

- The block is a line `mutation:` at column 0, followed by exactly the three
  indented entries `tool:`, `threshold:`, and `command:` (order-independent, each
  required exactly once).
- `threshold` must be an integer `0`–`100`. A universal harness-fixed threshold is
  exactly the toy-CRAP-threshold-6 mistake D27/D28 removed — the **project** sets it.
- Duplicate blocks, duplicate/unknown keys, a missing key, an inline value after
  `mutation:`, a non-token `tool`, a tab in the command, or an out-of-range /
  non-numeric `threshold` all fail closed.
- Only `tool: pitest` is supported today (`run-six` rejects any other tool). Stryker
  for TypeScript is a documented later extension; the record already carries the
  tool so a second engine slots in without a grammar change.

## The report (PITest `mutations.xml`)

The mutation command must produce PIT's standard XML report at the path in
`$CRAFT_MUTATION_REPORT` (set by `run-six`, pointed at a runner-owned path **outside**
the candidate worktree — so producing it is not a worktree mutation and it survives
teardown). One `<mutation>` element per generated mutant:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<mutations partial="false">
  <mutation detected='true'  status='KILLED'      numberOfTestsRun='2'>...</mutation>
  <mutation detected='false' status='SURVIVED'    numberOfTestsRun='3'>...</mutation>
  <mutation detected='false' status='NO_COVERAGE' numberOfTestsRun='0'>...</mutation>
</mutations>
```

[`bin/parse-mutation-report.bb`](../bin/parse-mutation-report.bb) parses it
**structurally as XML — never by substring** (the D22/D27 "passed for the wrong
reason" trap) — and prints `<killed><TAB><total>`:

- `killed` = mutations with the authoritative `detected='true'` attribute
  (`KILLED`/`TIMED_OUT`/`MEMORY_ERROR`/`RUN_ERROR`).
- `total` = all mutations **except** `NON_VIABLE` (uncompilable) ones, which PIT
  itself excludes from the denominator.
- A report with **zero scorable mutations**, a missing/invalid `detected`, a wrong
  root element, or malformed XML fails closed — a gate over no mutants proves nothing.

## How the runner uses it

`run-six` runs the command (a **reporter**: it exits 0 once the report is written —
PIT can exit 0 with a poor score, so the exit code is **not** the gate) and then
enforces, with **exact integer arithmetic** so no float rounding nudges a run over
its threshold:

> `killed * 100 >= threshold * total`

A score below the declared threshold turns the run **red at the mutation gate**,
attributed and naming the score, the killed/total counts, and the threshold. The
report is folded into the runner's authenticated command evidence (D29/D35) and
re-verified independently by `inspect-run` — accidental-tamper-evident only, not
forgery-proof against a malicious same-user agent (D30).

## Wiring a real PIT project

PIT writes `mutations.xml` under a timestamped `target/pit-reports/<ts>/` directory,
so the declared command runs PIT and then copies that XML to `$CRAFT_MUTATION_REPORT`:

```text
mutation:
  tool: pitest
  threshold: 80
  command: mvn -q -pl src/core org.pitest:pitest-maven:mutationCoverage -DoutputFormats=XML && cp "$(find src/core/target/pit-reports -name mutations.xml | sort | tail -1)" "$CRAFT_MUTATION_REPORT"
```

Do **not** set PIT's own `mutationThreshold` to fail the Maven build — let PIT exit
0 and report, so the **harness** owns the verdict from the parsed score (uniformly
with `test:`/`quality:`/`accept:`). Every path in the command's outputs must stay
inside `owns:` if the command commits anything; normally it only reads and reports.

The gate consumes the **schema**, not the engine. The `bb test` fixture ships a
genuine, dependency-free miniature (`tools/mutation-run.sh`) that really mutates a
module, runs the project's tests against each mutant, and emits this exact XML — so
the suite stays hermetic (no JVM/Maven) while the score is genuinely computed, never
hard-coded.
