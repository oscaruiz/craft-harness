# m7 — the six-pack: full domain pipeline with executable Gherkin (v0.2)

v0.1 is complete and sealed (D28–D31): `solo-pack` is the one supported light
path, project gates are runner-owned (the runner executes the declared `test:`
and ordered `quality:` commands itself in a fresh candidate worktree, authenticates
`commands.tsv`, and prints `SUCCESS` only after `inspect-run` passes), and the
honest boundaries are documented — no semantic-coverage promise (D29), no
malicious-forgery-proof claim (D30). The prior milestone doc is preserved in git
history; this file now tracks the active milestone, m7.

## Goal

Deliver the `six-pack`: a fuller domain pipeline with four **runner-enforced**
roles and the defining new control — **executable Gherkin**. Where solo-pack's
Gherkin is *documental* (a verifier reads `.feature` files and asserts each `@ID`
is "satisfied" — advisory by design, D29), six-pack's Gherkin must **actually run**
and the runner must **verify it ran**: every human-approved scenario ID must appear
**passed** in a machine-readable acceptance report the runner produces itself. This
is the one place traceability stops being advisory and becomes an executable gate.

The build is governed by the project's most productive defect class,
**"fixtures-mirror-the-toy blindness"** (D20/D21/D22/D23/D27): the non-toy,
multi-module fixture is built **first, in red**, and every gate is proven against
it. A planted **unimplemented scenario** MUST turn the run red.

## Phases and runner-enforced exit criteria

Sequence `specify → code → harden → qa` (asserted against the pack conf, exactly
as `run-solo` asserts its solo sequence).

- **specify** (specifier): writes spec + executable `@ID`-tagged `.feature` files,
  then pauses at the R6 human token gate. Runner-enforced: features parse and yield
  ≥1 tagged scenario; the approved scenario IDs are snapshotted at the gate as the
  executable-traceability contract.
- **code** (coder): implements the impl + acceptance features + step handlers and
  commits the candidate on the enforced branch. Runner-enforced: candidate ≠
  baseline, descends baseline, on branch, owned-scope clean; the runner runs the
  declared `test:` command green.
- **harden** (hardener): quality/cleanup within owns (may advance the candidate;
  cleaner is folded in here). Runner-enforced: re-scoped + `test:` still green; the
  runner runs the ordered `quality:` commands green — including the **architecture**
  command (the architect role's runner-enforced criterion).
- **qa** (QA): independent verifier in a clean worktree at the final candidate.
  Runner-enforced: the worktree is unmutated, and the runner runs the declared
  `accept:` command **itself** and enforces that every approved scenario ID appears
  `passed` in the report (the executable-Gherkin gate).

## Honest boundaries (carried from D29/D30, not re-litigated)

- The claim is "the approved scenarios executed and passed against the candidate",
  never semantic coverage of the domain (D29).
- The runner executes `accept:`/`test:`/`quality:` itself and acts on the live
  process exit status (D30's one surviving adversarial guarantee). Retained report
  and command evidence is accidental-tamper-evident only, not forgery-proof against
  a malicious same-user agent (D30).

## Documented degradation (design §5/§7.7/§8 already carve these out)

- **No gherkin-mutator** (§5 "No Gherkin mutation in v0.1", carried into m7).
- **No Playwright-UI automation**; `accept:` is language/UI-agnostic and a TS
  project can later declare a Playwright-emitting `accept:` command.
- Single primary candidate: harden may advance it; architect/cleaner are not
  separate committing phases. Scenario *text* fidelity stays advisory (D29);
  scenario *execution + pass of approved IDs* is enforced.

## Exit criteria

- The non-toy multi-module fixture is built first and every gate is proven against
  it; no six-pack test could pass against the sut.sh/42 toy.
- A planted **unimplemented scenario** (approved `@ID`, no step handler) turns the
  run **red** at the accept gate, attributed and naming the scenario ID; a
  **dropped** approved scenario (absent from the report) turns it red too.
- A planted **architecture violation** turns the run red at the harden gate;
  out-of-scope commits and a verifier that mutates its worktree turn it red.
- The happy path runs `specify → gate → code → harden → qa → runner gates →
  inspect → SUCCESS`, every approved scenario ID `passed` in the authenticated
  report, `inspect-run` PASS.
- Injection-contract tests assert the **exact** `TEST_CMD:` / `ACCEPT_CMD:` /
  `HANDOFF_PATH:` lines (not substrings); each fake declared tool asserts its exact
  args.
- The fork `six-pack` branch is registered in `PACKS` and satisfies
  `MANAGED_FILES.manifest` (`pack-manifest-test` covers it, D20).
- Full `bb test` is green, including the sealed-v0.1 suites (regression guard for
  any shared-helper extraction), apart from the named upstream flaky test.

## After code-complete

m7 goes to a fresh **external** (Codex) adversarial audit against its own criteria
— not self-review — exactly the separation that made v0.1 sound.
