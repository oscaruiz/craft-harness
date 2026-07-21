# Acceptance report schema (m7, executable Gherkin)

The six-pack's executable-Gherkin gate is runner-owned: `run-six` executes the
project's declared `accept:` command **itself** in a fresh candidate worktree, and
that command must produce a **machine-readable per-scenario report** so the runner
can enforce scenario-level coverage *structurally* (never by substring — that is
the D22/D27 "passed for the wrong reason" trap).

## Format

The report is **NDJSON**: one JSON object per line, one line per executed
scenario. Each object has exactly two required string fields:

```json
{"scenario":"SUT-1","status":"passed"}
{"scenario":"SUT-2","status":"passed"}
{"scenario":"SUT-3","status":"undefined"}
```

- `scenario` — the scenario's ID, matching the `@ID` tag the specifier wrote on
  the scenario. IDs use the token form `@[A-Z][A-Z0-9]*-[0-9]+` (e.g. `@SUT-1`,
  `@ACC-12`). This is the same token `run-six` snapshots from the approved feature
  files at the R6 gate, so the two sets are comparable.
- `status` — one of `passed`, `failed`, `undefined`, `skipped`. `undefined` means
  a step had no matching step handler (an unimplemented scenario); `failed` means a
  step ran and asserted false.

Each scenario ID may occur only once in a report. Duplicate records are malformed
and fail closed, including duplicates with contradictory statuses; neither
`run-six` nor `inspect-run` may treat any one duplicate `passed` record as enough.

## Where it is written

`run-six` sets `CRAFT_ACCEPT_REPORT` in the environment when it runs `accept:`,
pointing at a runner-owned path **outside** the candidate worktree (so producing
the report is not a worktree mutation and the file survives worktree teardown).
The acceptance command MUST write the report there. The command is a *reporter*:
it exits `0` once it has run the scenarios and written the report — the pass/fail
**verdict is the runner's**, derived from the scenario statuses, not from the
command's exit code.

## How the runner uses it

`run-six` parses the report with `bin/parse-accept-report.bb` (structural, not
substring) and enforces:

> every approved scenario ID (the R6-gate snapshot) MUST appear in the report with
> `status == passed`.

Any approved ID that is **missing**, `failed`, `undefined`, or `skipped` turns the
run **red** at the accept gate, naming the offending scenario ID(s). The report
plus the approved-scenario snapshot are folded into the runner's authenticated
command evidence (D29) and re-verified by `inspect-run` — accidental-tamper-evident
only, not forgery-proof against a malicious same-user agent (D30).

## Adapting a real engine

The gate consumes the *schema*, not any particular engine. A real project wires
its acceptance engine to emit this schema — e.g. a thin adapter over cucumber's
`--format json`, or the Acceptance-Pipeline-Specification `gherkin-parser` +
generated runtime — and the identical runner gate applies. The `bb test` fixture
ships a genuine, dependency-free runtime that emits this schema directly, so the
suite stays hermetic (no external repo, no network) while the scenarios really run.
