# Project status

Status as of 2026-07-18: milestones m1–m4 are merged on `main` and have automated tests; m3 and m4 also contain committed real-Claude evidence against a toy shell project. This is a prototype with demonstrated mechanics, not a production-ready Java/TypeScript harness ([`docs/current-milestone.md`](current-milestone.md), [`docs/evidence/m3/real-run/README.md`](evidence/m3/real-run/README.md), [`docs/evidence/m4/real-run/README.md`](evidence/m4/real-run/README.md)).

## Completed milestones

| Milestone | What shipped | Evidence |
|---|---|---|
| m1 — launcher | Manifest-scoped install, immutable versioned stores, atomic `current` swap, installed-version pointer, hook install, branch/path tripwire, `doctor`, in-flight refusal, and deterministic interrupted-upgrade tests | [`bin/craft-harness`](../bin/craft-harness), [`MANAGED_FILES.manifest`](../MANAGED_FILES.manifest), [`hooks/pre-commit`](../hooks/pre-commit), [`test/craft_harness/launcher_test.clj`](../test/craft_harness/launcher_test.clj) |
| m2 — adapters | Two-mode contract; Claude and Codex headless adapters; canonical edit→test→commit→handoff→wake scenario; pure state assertions; process-group timeout; manifest provenance; fake and argv-level tests | [`adapters/CONTRACT.md`](../adapters/CONTRACT.md), [`adapters/run-scenario`](../adapters/run-scenario), [`test/craft_harness/runner_test.clj`](../test/craft_harness/runner_test.clj), [`test/craft_harness/real_adapters_test.clj`](../test/craft_harness/real_adapters_test.clj) |
| m3 — two-pack-lite | Fork-owned no-mutation cleaner prompt/config, faithful default-shell tmux smoke, toy CRAP/DRY gates, two-role session driver with wake/session caps, post-run inspector, and a green real-Claude toy run | branch `two-pack-lite`; [`bin/smoke-tmux`](../bin/smoke-tmux), [`bin/run-pack`](../bin/run-pack), [`bin/inspect-run`](../bin/inspect-run), [`docs/evidence/m3/real-run/`](evidence/m3/real-run/) |
| m4 — solo-pack | Structured handoff schema/validator, fresh-process specify→approval→code→verify runner, candidate-commit verifier worktree, phase/retry/run breakers, solo inspector traceability, and a green owner-approved real-Claude toy run | [`bin/run-solo`](../bin/run-solo), [`docs/solo-handoff-schema.md`](solo-handoff-schema.md), [`test/craft_harness/run_solo_test.clj`](../test/craft_harness/run_solo_test.clj), [`docs/evidence/m4/real-run/`](evidence/m4/real-run/) |

“Complete” here means the scoped milestone exit behavior landed. It does not erase later-discovered integration gaps or promote planned capabilities to implemented ones.

## Open v0.1 exit items

### D8: Codex certification and R1 genericity

The Codex adapter's argv and sandbox shape are tested offline, but the canonical scenario has not passed with a native Linux Codex CLI. The only available reference-environment executable was a Windows npm shim, which D4 disqualifies. The owner deferred the paid run to the v0.1 exit. Consequently the project has a generic adapter design but has **not demonstrated backend genericity**; R1 remains OPEN ([`adapters/codex/invoke`](../adapters/codex/invoke), [`test/craft_harness/real_adapters_test.clj`](../test/craft_harness/real_adapters_test.clj), D8 in [`docs/decisions.md`](decisions.md)).

### Packaging/integration gap

This is not named in the frozen exit list, but it is present in the current tree: `two-pack-lite` lacks `hooks/pre-commit` and `handoffs.prompt`, both required by `MANAGED_FILES.manifest`, so the launcher cannot directly install that branch. `solo-pack` is a runner on `main`, not a separate branch. The demonstrations are real evidence for the runners, but not evidence for an end-user one-command install-and-execute path ([`MANAGED_FILES.manifest`](../MANAGED_FILES.manifest), branch `two-pack-lite`, [`bin/craft-harness`](../bin/craft-harness)).

## Built versus stubbed or planned

Built now:

- Durable upstream handoff plumbing and tmux orchestration inherited from SwarmForge ([`swarmforge/scripts/`](../swarmforge/scripts/)).
- Manifest/atomic/version/doctor launcher mechanics and hook integrity checks ([`bin/craft-harness`](../bin/craft-harness)).
- Claude and Codex adapter executables, with real certification only for Claude ([`adapters/`](../adapters/)).
- A Claude-specific interactive two-pack toy driver and a headless adapter-based solo driver ([`bin/run-pack`](../bin/run-pack), [`bin/run-solo`](../bin/run-solo)).
- Negative inspection for mutation invocation, threshold 6, commit confinement, queues/handoffs, wake count, and solo scenario traceability ([`bin/inspect-run`](../bin/inspect-run)).
- Language-free toy wrappers that emit normalized, durable CRAP/DRY-shaped reports and enforce thresholds 6 and 0 ([`tools/toy/crap.sh`](../tools/toy/crap.sh), [`tools/toy/dry.sh`](../tools/toy/dry.sh)).

Still stubbed/planned:

- m5 real Java gates: incremental PIT, `crap4java` with wrapper-enforced threshold 6, and `dry4java` on myCQRS. No `tools/java/` directory exists today ([`docs/design-v2.2.md`](design-v2.2.md), [`tools/toy/`](../tools/toy/)).
- m6 real TypeScript gates: unit-only Stryker, a negative check excluding Playwright/`*.e2e.*`, ESLint complexity, touched-code Vitest coverage, and jscpd on F1 Hub. No `tools/ts/` or Stryker configuration exists today ([`docs/design-v2.2.md`](design-v2.2.md)).
- m7 six-pack harness integration and a domain-scale vertical slice. The upstream `six-pack` branch exists, but the current harness has not executed the planned m7 workflow ([`docs/design-v2.2.md`](design-v2.2.md), upstream branch described in [`README.md`](../README.md)).
- Mutant/worker-specific caps, real incremental mutation scope, automatic arbitrary-crash recovery, and a technically universal no-push/no-secrets envelope are not present. Current code provides time/wake/retry caps, state detection/refusal, and CLI sandbox flags ([`bin/run-pack`](../bin/run-pack), [`bin/run-solo`](../bin/run-solo), [`adapters/*/invoke`](../adapters/)).

## Known flaky upstream test

`stop-handoff-daemon-stops-running-process-and-removes-pid-file` in [`test/swarmforge/handoff_test.clj`](../test/swarmforge/handoff_test.clj) is recorded as known-flaky. It races daemon shutdown against PID-file removal; an unchanged rerun may pass. It is upstream code, so the fork records rather than silently edits it ([`docs/decisions.md`](decisions.md)). A different failure should not be dismissed under this label.

## Not yet proven

- **Real-project value.** The required value checkpoint—a small real myCQRS feature using the better light path—has not happened. The green evidence projects are intentionally tiny shell toys. Therefore there is no evidence yet that the harness improves throughput, defect rate, review quality, or operator load on production code ([`docs/current-milestone.md`](current-milestone.md), [`docs/evidence/m3/real-run/README.md`](evidence/m3/real-run/README.md), [`docs/evidence/m4/real-run/README.md`](evidence/m4/real-run/README.md)).
- **Which light path survives.** Both candidates ran, but the owner has not selected one. Maintaining both permanently is explicitly rejected as structural duplication ([`docs/design-v2.2.md`](design-v2.2.md), [`docs/current-milestone.md`](current-milestone.md)).
- **Cross-backend behavior.** Codex has not passed the paid/native scenario; other CLIs have no adapter here. “Less than one day” for a new adapter is a design criterion, not measured evidence ([`adapters/CONTRACT.md`](../adapters/CONTRACT.md)).
- **Real Java and TS quality gates.** Threshold normalization works only in toy wrappers. Mutation scoping, PIT performance, Stryker's unit-only selection, anti-Playwright verification, source-map coverage/complexity behavior, and real DRY findings remain untested ([`tools/toy/`](../tools/toy/), [`docs/design-v2.2.md`](design-v2.2.md)).
- **Strong security.** The repository proves hooks, hashes, sandbox flags, and selected negative audits. It does not prove that an agent cannot bypass hooks, push through available credentials, read every host secret, tamper with evidence outside its sandbox, or observe the approval console (D2/D12 in [`docs/decisions.md`](decisions.md)).
- **Faithful multi-worktree two-pack operation.** m3 intentionally used a shared root and a runner-owned daemon stand-in. Independent worktree judgment is proven only for solo verification ([`bin/run-pack`](../bin/run-pack), [`bin/run-solo`](../bin/run-solo), D11 in [`docs/decisions.md`](decisions.md)).
- **Upgrade in a fully packaged real pack.** Atomicity is strongly fault-tested against fixtures, but the current two-pack branch/manifest mismatch prevents the intended direct installation route from demonstrating it end-to-end ([`test/craft_harness/launcher_test.clj`](../test/craft_harness/launcher_test.clj), [`MANAGED_FILES.manifest`](../MANAGED_FILES.manifest)).

## Remaining roadmap

1. Close the immediate v0.1 gaps: certify Codex natively, keep R1 open until the scenario passes, and reconcile pack branches with the launcher's manifest/install model ([D8](decisions.md), [`bin/craft-harness`](../bin/craft-harness)).
2. Run the owner-controlled **value checkpoint** on a small real myCQRS feature. If the harness does not improve the work, stop and redesign before expanding it ([`docs/current-milestone.md`](current-milestone.md)).
3. Run the **survival checkpoint** and retain exactly one of two-pack-lite or solo-pack; archive the other ([`docs/design-v2.2.md`](design-v2.2.md)).
4. m5: exercise the surviving path on myCQRS with incremental PIT, real `crap4java` threshold-6 enforcement, and `dry4java` ([`docs/design-v2.2.md`](design-v2.2.md)).
5. m6: exercise F1 Hub with Stryker restricted to unit tests, anti-Playwright/anti-E2E negative verification, ESLint complexity, Vitest touched-code coverage, and jscpd ([`docs/design-v2.2.md`](design-v2.2.md)).
6. m7: use the six-pack only for a domain feature that warrants the additional roles; deliver a complete vertical slice or document the degradation ([`docs/design-v2.2.md`](design-v2.2.md)).

