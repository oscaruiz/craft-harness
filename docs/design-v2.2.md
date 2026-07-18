# craft-harness — Design v2.2 (frozen after review round 2)

Changes vs v2.1: second validation round (2 independent judges + defender-classifier + triage over raw verdicts + verifications executed against the swarm-forge source). Design owner's approval recorded, including her ruling on R1. Triage log in §9.

---

## 1. Requirements (v2.2)

- **R1 — Agent-CLI generic (reworded).** The harness depends on no single tool: a documented, executable adapter contract (scenario: edit → test → commit → handoff → wake-up). Primary verified backend: **Claude Code**. Genericity proof: the same scenario passing with a **second backend** (Codex, natively supported upstream). Other CLIs (OpenCode, etc.) are added through the contract when a real need exists; design criterion: a new adapter must cost < 1 day.
- **R2 — Bounded multi-language:** Java/Maven and TypeScript/Vitest/Next. No abstractions for hypothetical languages.
- **R3 — Proportionality:** a light path and a disciplined path. v0.1 trials two light-path candidates (two-pack, solo-pack) with an explicit checkpoint to keep exactly one (§5).
- **R4 — Independent judgment:** verification without inherited transcript, over immutable state, with explicit inputs. Mechanisms: one git worktree per role (parallel) or a fresh process per phase (sequential).
- **R5 — Safe propagation:** `upgrade` operates on an **explicit manifest of managed files**, with **atomic** replacement (staging + swap), the **installed version recorded** in each project, and a **refusal to upgrade while a session is in flight**.
- **R6 — Enforceable human gate (reworded).** In packs with a specifier: human approval of the Gherkin/spec before any code. In the two-pack: the launcher **refuses to start without `task.md`**, and `task.md` is on the pre-commit blacklist — agents cannot create or edit it. Evidence forgeable by the audited subject is not evidence; if enforceability is not implemented, the header is removed.
- **R7 — Mutation ≠ E2E, and scoped:** mutation runs only against **unit** tests (acceptance joins only after being measured fast, deterministic and isolated). Physical separation via a dedicated config + **negative verification**: an assert that Stryker's selected set contains no Playwright imports and no `*.e2e.*` files.
- **R8 — Recovery (new).** Queues and handoffs live in persistent state, not ephemeral state. `doctor` detects an in-flight session (partial phase, orphan worktree, unconsumed handoff); `run` refuses or resumes — it never clobbers.
- **R9 — Agent limits (new).** Pre-commit hook with a path blacklist (`task.md`, constitution articles, adapters, `.git/hooks`), a working branch enforced by the launcher, `push` forbidden, secrets kept out of the agents' environment.
- **R10 — Circuit breakers and provenance (new).** Per-phase timeout, caps on mutants/workers/wake-ups, reliable cancellation. A run manifest (JSON) recording fork, CLI and tool versions, starting commit, duration and outcome. Minimal reproducibility; full telemetry: no.

## 2. Architecture

```
craft-harness/  (fork of unclebob/swarm-forge)
├── main
│   ├── swarmforge/scripts/          (upstream, UNTOUCHED: handoff plumbing only — verified)
│   ├── swarmforge/constitution/articles/  (upstream UNTOUCHED + our lang-java.prompt, lang-ts.prompt)
│   ├── bin/craft-harness            (launcher: run | upgrade | doctor)
│   │     · MANAGED_FILES.manifest   (inventory of managed paths)
│   │     · .craft-harness-version   (written into each project)
│   ├── adapters/                    (adapter contract + claude-code, codex; others on demand)
│   ├── hooks/pre-commit             (R9 blacklist, installed by the launcher)
│   └── tools/java/ ts/              (wrappers: normalize score/threshold/offenders — nothing more)
├── two-pack-lite   (OUR branch: our OWN cleaner.prompt without mutation — role prompts
│                    are per-branch in the upstream design, so this is not an override
│                    fighting Bob's prompt: it is our workflow)
├── six-pack        (upstream adapted; executable Gherkin; APS/gherkin-mutator OUT of v0.1)
└── solo-pack       (ours: sequential, fresh process per phase, structured handoff)
```

Verified against the source: mutation/CRAP/DRY invocation lives in the role *prompts*, not in the scripts, and the constitution defines no precedence between conflicting articles. Design consequence: critical controls are not implemented as conflicting instructions but as (a) our own role prompts in our own branches and (b) executable constraints (launcher, wrappers, pre-commit). Every critical prohibition carries **negative verification** in its exit criterion.

Per-project footprint: `project.prompt` + `task.md` (versioned) + `.craft-harness-version` + persistent queue state. Supported platforms: macOS/Linux/WSL.

## 3. Languages (closed decisions)

| | Java | TypeScript |
|---|---|---|
| Mutation | **PIT, sole tool.** `mutate4java` archived (verified: re-runs `mvn test` per mutant; no PIT shortcoming identified, so evaluating it is scope without cause) | **Stryker over unit tests exclusively** (dedicated config; acceptance only after measurement) + anti-Playwright assert |
| Quality | `crap4java` with threshold **6 enforced by the wrapper** (verified: the tool hard-codes a >8.0 cutoff while the cleaner prompt declares ≤6 — the wrapper parses scores and enforces 6; a single source of truth) | **Two separate gates:** max complexity (ESLint) + min coverage on touched code (Vitest). Aggregated CRAP for TS: only if the two gates prove insufficient (correlating complexity/coverage per function via source maps is fragility without proven benefit in v0.1) |
| DRY | `dry4java` | jscpd |

Wrappers with auto-detection (pom.xml/package.json) + a flag override for ambiguous repos. No new configuration format.

## 4. Launcher

`run`: materializes conf/roles/constitution for the pack, installs the pre-commit hook, writes the version, verifies `task.md`, refuses if `doctor` detects an in-flight session. `upgrade`: staging + validation + atomic swap of manifest-listed paths only; refuses while a session is in flight. `doctor`: session state, versions, managed-file integrity. The recorded lesson stands: YAGNI applies to the size of the piece, not to the existence of the requirement.

## 5. Packs

- **two-pack-lite (daily-driver candidate):** coder → cleaner with our own `cleaner.prompt` (no mutation). `task.md` contract enforced by the launcher, protected by pre-commit.
- **solo-pack (daily-driver candidate, sequential):** N phases, each a fresh CLI process with no inherited transcript. **Mandatory structured handoff** (done / decisions / assumptions / open items / commands executed) — direct heir of the original harness's `progress/` files. The verifier runs in a clean worktree checked out from the **candidate commit**. A sequential runner of our own (no daemon/tmux); its size will be known when it exists.
- **Survival checkpoint:** after trying both on real tasks (milestones 3–4), ONE light path is kept and the other archived. Two permanent light paths is structural duplication.
- **six-pack (core domain):** full pipeline, specifier's human gate, executable Gherkin, QA-Playwright for TS. No Gherkin mutation in v0.1.

## 6. Live risks

Prompt obedience as the only control → mitigated with negative verifications and executable constraints (§2), validated in milestones 3–4. Our branches drifting from upstream → simulate 2–3 upstream merges during v0.1 (no restructuring into manifests: diverging from upstream's model makes the very merges it claims to cheapen more expensive). Mutation cost → incremental scope (touched module/files; Stryker `--since`) + R10 breakers. Sufficiency of the structured handoff → measured on the solo-pack's first real run.

## 7. Roadmap v0.1

1. **Full launcher** (manifest, atomicity, version, doctor, R9 pre-commit) against a toy repo. Exit: a fork change propagated via `upgrade`; an interrupted `upgrade` leaves no hybrid state; an agent commit to a forbidden path is rejected.
2. **Adapter contract** with Claude Code (primary) + Codex (genericity proof). Exit: two scenario logs + documented contract. OpenCode: off the critical path.
3. **two-pack-lite** end-to-end with a toy task. Exit: task completed **+ assert that mutation did NOT run + assert that the executed threshold was 6**.
4. **solo-pack** with the same task. Exit: valid structured handoffs, verifier on the candidate commit, no tmux.
   - **Value checkpoint:** first small real feature in myCQRS using whichever light path performed better. If the harness does not improve real work here, stop and rethink before building more.
   - **Survival checkpoint:** pick the single light path.
5. **Java in full on myCQRS:** incremental PIT, crap4java wrapper (threshold 6), dry4java. Exit: a real feature with all gates passed.
6. **TS on F1 Hub:** Stryker unit-only with anti-Playwright assert, complexity and coverage gates, jscpd. Exit: R7 demonstrated with negative verification.
7. **six-pack** on the first domain feature that deserves it. Exit: full vertical or documented degradation.

## 8. Out of v0.1 (rejected with cause)

Full security program (network isolation, exhaustive allowlists) beyond R9 · full telemetry · dry-run/backup/multi-version upgrade testing · packs as manifests instead of branches · deleting two-pack or solo-pack without usage data · continuous adapter re-certification (the run manifest records versions; re-running the contract when they change is a convention, not a gate) · mutate4java evaluation · APS/gherkin-mutator · aggregated CRAP for TS · public extraction of our own tooling (a portfolio decision, outside the harness).

## 9. Triage log — round 2

**Verifications executed during the cycle:** mutation/CRAP/DRY live in prompts, not scripts (fixes F4/F7/F8 do not collapse, but they demand negative verification) · no defined precedence between conflicting articles (→ our own per-branch role prompts instead of overrides) · `crap4java` hard-codes a >8.0 cutoff while the two-pack cleaner declares ≤6 (→ threshold 6 via wrapper) · `mutate4java` re-runs `mvn test` per mutant (→ PIT sole tool).

**Accepted:** new R8, R9, R10 (independent agreement of both judges) · R6 enforceable or removed · launcher with manifest/atomicity/version/in-flight checks · F4 via our own branch + assert · threshold 6 closed · TS mutation unit-only + anti-Playwright assert · CRAP-TS → two gates · structured handoff + candidate commit · value and survival checkpoints · wording cleanup (size estimates and "identical prompts" removed; auto-detection with override).

**Owner's ruling (R1):** generic by design toward any agent CLI; Claude Code primary; genericity proven with a second backend; OpenCode and others on demand via the contract.

**Rejected:** see §8.