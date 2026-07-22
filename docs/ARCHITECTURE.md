# craft-harness architecture

## Purpose and lineage

craft-harness is an experimental, local harness for driving coding-agent workflows with executable gates, durable evidence, timeouts, and provenance. It is a fork of `unclebob/swarm-forge`: the Git configuration names `https://github.com/unclebob/swarm-forge.git` as `upstream`, and the repository retains upstream's tmux launcher, handoff daemon, queue helpers, terminal adapters, constitution articles, and tests under `swarmforge/` and `test/swarmforge/` ([`.git/config`](../.git/config), [`swarmforge/scripts/swarmforge.bb`](../swarmforge/scripts/swarmforge.bb), [`swarmforge/scripts/handoffd.bb`](../swarmforge/scripts/handoffd.bb), [`swarmforge/handoff-protocol.md`](../swarmforge/handoff-protocol.md)).

The fork's policy is to leave upstream scripts and shared articles alone and put harness behavior in new `bin/`, `adapters/`, `hooks/`, `tools/`, `docs/`, and `test/craft_harness/` files, or in fork-owned pack branches ([`CLAUDE.md`](../CLAUDE.md)). The one recorded exception is an LF-only normalization needed to make Unix shebangs work; D5 says upstream content remained identical apart from carriage returns ([`docs/decisions.md`](decisions.md)). Comparing history is still the authoritative way to classify a particular line. In broad terms:

- Upstream: `swarmforge/scripts/`, the shared articles currently on `main`, `swarmforge/handoff-protocol.md`, `close-swarm`, and `test/swarmforge/` ([`README.md`](../README.md), [`CLAUDE.md`](../CLAUDE.md)).
- craft-harness: `bin/`, `adapters/`, `hooks/`, `tools/toy/`, design/evidence documents, `test/craft_harness/`, and the changed role/config files on `two-pack-lite` ([`MANAGED_FILES.manifest`](../MANAGED_FILES.manifest), [`bb.edn`](../bb.edn), branch `two-pack-lite`).

The design calls this a bounded Java/Maven and TypeScript/Vitest/Next harness. That language support is a target, not current code: only language-free toy CRAP/DRY wrappers exist today ([`docs/design-v2.2.md`](design-v2.2.md), [`tools/toy/crap.sh`](../tools/toy/crap.sh), [`tools/toy/dry.sh`](../tools/toy/dry.sh)).

## Component map

| Component | Responsibility | Evidence |
|---|---|---|
| Upstream plumbing | tmux sessions, role worktrees, durable file handoffs, wake notifications, terminal surfaces | [`swarmforge/scripts/swarmforge.bb`](../swarmforge/scripts/swarmforge.bb), [`swarmforge/scripts/handoffd.bb`](../swarmforge/scripts/handoffd.bb), [`swarmforge/scripts/handoff_lib.bb`](../swarmforge/scripts/handoff_lib.bb) |
| Launcher | materialize managed files, record version/pack/branch, upgrade atomically, diagnose state/integrity | [`bin/craft-harness`](../bin/craft-harness), [`MANAGED_FILES.manifest`](../MANAGED_FILES.manifest), [`bin/atomic_rename.bb`](../bin/atomic_rename.bb) |
| Adapter layer | normalize one-shot CLI invocation and version reporting; certify edit/test/commit/handoff/wake-up | [`adapters/CONTRACT.md`](../adapters/CONTRACT.md), [`adapters/run-scenario`](../adapters/run-scenario), [`adapters/scenario-asserts`](../adapters/scenario-asserts) |
| two-pack driver | run coder and cleaner in tmux, relay handoffs, apply session breakers, collect evidence | [`bin/run-pack`](../bin/run-pack), branch `two-pack-lite` |
| solo driver | sequential specify/code/verify processes, approval gate, structured handoffs, isolated verifier | [`bin/run-solo`](../bin/run-solo), [`docs/solo-handoff-schema.md`](solo-handoff-schema.md) |
| Inspector | deterministic post-run checks, including negative assertions | [`bin/inspect-run`](../bin/inspect-run), [`bin/handoff-validate.bb`](../bin/handoff-validate.bb) |
| Test suite | upstream tests plus launcher, adapters, drivers, inspectors, wrappers and fault cases | [`bb.edn`](../bb.edn), [`test/craft_harness/`](../test/craft_harness/) |

## Launcher: `run`, `upgrade`, `doctor`

`bin/craft-harness` treats `MANAGED_FILES.manifest` as the only inventory it may materialize. It rejects absolute or `..` paths, reads each file from a selected Git commit, and constructs an immutable store at `.craft-harness/store/<commit>.<pid>/`. Stable project paths are symlinks through `.craft-harness/current`; `.craft-harness-version` points to that store's `VERSION` ([`bin/craft-harness`](../bin/craft-harness), [`MANAGED_FILES.manifest`](../MANAGED_FILES.manifest)).

`run` requires a Git project, `task.md`, an idle session, and a resolvable pack ref. It builds the store, records the selected pack and current project branch, creates the stable links, copies the pre-commit hook into `.git/hooks/pre-commit`, and adds launcher-owned paths to `.git/info/exclude`. It installs infrastructure; it does **not** call `run-pack` or `run-solo` ([`bin/craft-harness`](../bin/craft-harness)).

`upgrade` resolves the recorded pack's current tip, stages a complete replacement store, validates that every managed file is non-empty, creates a temporary `current` link, and replaces the live link with one atomic filesystem move. Only after the swap does it refresh links/hook/excludes and prune old stores. Deterministic kill points after staging, before swap, and between link creation and rename test that interruption cannot expose a mixed install ([`bin/craft-harness`](../bin/craft-harness), [`bin/atomic_rename.bb`](../bin/atomic_rename.bb), [`test/craft_harness/launcher_test.clj`](../test/craft_harness/launcher_test.clj)).

`doctor` returns 30 for an in-flight session, 20 for modified managed files or hook, 10 for an installed commit behind the recorded pack tip, and 0 for a healthy current install. It detects upstream worktrees and pending/in-process/outbound queues, plus any solo state whose status is not `done` ([`bin/craft-harness`](../bin/craft-harness)). It does not repair state.

Current integration limitation: `MANAGED_FILES.manifest` includes `hooks/pre-commit` and three shared articles, but the current `two-pack-lite` branch lacks the hook and `handoffs.prompt`. Therefore direct `craft-harness run --pack two-pack-lite` cannot currently complete `install_tree`; the milestone-3 evidence used a prepared toy project and `run-pack` ([`MANAGED_FILES.manifest`](../MANAGED_FILES.manifest), branch `two-pack-lite`, [`docs/evidence/m3/real-run/README.md`](evidence/m3/real-run/README.md)). This is a verified gap, not an inference.

## Adapter contract and invocation modes

An adapter directory contains executable `invoke` and `info` files. `invoke --workdir DIR --prompt-file FILE` starts one non-interactive, fresh process and returns its CLI status; `info` prints a one-line name/version for the manifest. Durable files are the only continuity between invocations ([`adapters/CONTRACT.md`](../adapters/CONTRACT.md)). Claude uses `claude -p --permission-mode acceptEdits` from the requested directory; Codex uses `codex exec -C ... --sandbox workspace-write` ([`adapters/claude-code/invoke`](../adapters/claude-code/invoke), [`adapters/codex/invoke`](../adapters/codex/invoke)).

The canonical headless certification performs five fresh turns: edit, test, commit, create a Git handoff, then consume the delivered handoff after the standard wake message. Each turn has a process-group timeout, and state assertions—not the agent's narrative—decide whether it passed ([`adapters/run-scenario`](../adapters/run-scenario), [`adapters/run-with-timeout`](../adapters/run-with-timeout), [`adapters/scenario-asserts`](../adapters/scenario-asserts)).

Interactive mode follows upstream `swarmforge.conf` window lines. Upstream recognizes agent tokens such as `claude` and `codex`; Claude receives its role prompt as an appended system prompt, while Codex receives the prompt as its user input ([`adapters/CONTRACT.md`](../adapters/CONTRACT.md), [`swarmforge/scripts/swarmforge.bb`](../swarmforge/scripts/swarmforge.bb)). The m3 driver implements only the Claude launch shape. It creates tmux windows with their default shell, then sends the launch command; this avoids an interactive-zsh first-run prompt swallowing keystrokes ([`bin/run-pack`](../bin/run-pack), [`bin/smoke-tmux`](../bin/smoke-tmux), D9 in [`docs/decisions.md`](decisions.md)).

## Packs

### two-pack-lite

This fork-owned branch modifies upstream `two-pack`'s cleaner prompt and config. Its config is `coder` on `master` followed by `cleaner` in batch receive mode, both using Claude (`two-pack-lite:swarmforge/swarmforge.conf`). The coder owns a focused TDD behavior slice, runs unit tests, commits, and sends a priority-50 Git handoff. It explicitly does not own acceptance/Gherkin, CRAP, DRY, or mutation work (`two-pack-lite:swarmforge/roles/coder.prompt`). The cleaner preserves behavior, runs wrapper-mediated coverage/CRAP/DRY and architecture review, and must not run mutation or invoke language tools directly; it sends a priority-00 handoff only if cleanup changes need returning (`two-pack-lite:swarmforge/roles/cleaner.prompt`). These `branch:path` citations refer to blobs that can be opened with `git show <branch>:<path>`; the files are intentionally not present on `main`.

For the m3 implementation, `run-pack` deliberately maps both roles into one project working directory and wakes them sequentially. It acts as the daemon by moving outbox messages into the inbox and sending the wake string. This is not upstream's worktree-per-role topology and does not provide an isolated cleaner; D11 explicitly defers faithful per-role worktrees to a workflow that needs them ([`bin/run-pack`](../bin/run-pack), D11 in [`docs/decisions.md`](decisions.md)).

### solo-pack

There is no separate `solo-pack` Git branch in the current repository; `m4-solo-pack` points at `main`, and the pack is implemented by `bin/run-solo`. Thus “solo-pack” currently means the runner and its generated phase prompts, not a materializable branch layout ([`bin/run-solo`](../bin/run-solo), [`docs/current-milestone.md`](current-milestone.md)).

The flow is:

1. `specify`: read `task.md`, write `spec.md` and tagged Gherkin, then emit the five handoff body sections.
2. Pause at an owner-only approval gate.
3. `code`: implement the approved spec, run only the absolute-path CRAP/DRY wrappers, commit on the current branch, and hand off the candidate commit.
4. `verify`: create a clean detached worktree at that candidate, run a fresh agent with the approved spec, diff, and prior handoffs as explicit inputs, test and trace every scenario ID, then remove the worktree.

Each phase is a fresh headless adapter process. The runner owns routing headers; the agent owns `done`, `decisions`, `assumptions`, `open items`, and `commands executed`. Validation occurs before advancing ([`bin/run-solo`](../bin/run-solo), [`bin/handoff-validate.bb`](../bin/handoff-validate.bb), [`docs/solo-handoff-schema.md`](solo-handoff-schema.md)).

## Inspector and negative assertions

`inspect-run` reads a finished session's manifest, repository, queue, handoffs, and durable wrapper logs. Common checks are: no mutation-tool invocation signature; at least one CRAP wrapper execution and no threshold other than 6; current branch equals the recorded branch and the baseline-to-HEAD diff contains no blacklisted paths ([`bin/inspect-run`](../bin/inspect-run)). These are negative or externally observable assertions: an agent saying “I did not mutate” or “tests passed” is not accepted as proof.

For two-pack sessions it additionally validates handoff headers/commits, requires no handoff left in `inbox/new`, requires `dequeued_at` on consumed messages, and checks wake count against the manifest cap. For solo it validates all three structured handoffs and requires every Gherkin scenario ID to appear in a test or verify evidence ([`bin/inspect-run`](../bin/inspect-run), [`test/craft_harness/inspect_run_test.clj`](../test/craft_harness/inspect_run_test.clj), [`test/craft_harness/inspect_solo_test.clj`](../test/craft_harness/inspect_solo_test.clj)). The inspector does not prove semantic correctness beyond these checks.

## Requirements R1–R10: implementation status

| Requirement | Enforcement/evidence | Status |
|---|---|---|
| R1 agent-CLI generic | Executable adapter contract, Claude/Codex argv adapters, canonical scenario and state asserts ([`adapters/CONTRACT.md`](../adapters/CONTRACT.md), [`adapters/run-scenario`](../adapters/run-scenario), [`test/craft_harness/real_adapters_test.clj`](../test/craft_harness/real_adapters_test.clj)) | **Open**: real Claude certified; native-Linux Codex certification is still owed under D8. |
| R2 bounded Java + TS | Tool choices are specified in the frozen design ([`docs/design-v2.2.md`](design-v2.2.md)) | **Aspirational**: no Java/TS production wrappers yet; only toy shell proxies. |
| R3 proportional light/discipled paths | two-pack-lite and solo runner exist and have toy evidence ([`bin/run-pack`](../bin/run-pack), [`bin/run-solo`](../bin/run-solo), [`docs/evidence/m3/`](evidence/m3/), [`docs/evidence/m4/`](evidence/m4/)) | **Partly proven**: both light paths ran; survival choice and six-pack harness integration remain. |
| R4 independent judgment | Fresh headless processes and solo verifier detached worktree at candidate; planted wrong-commit test ([`bin/run-solo`](../bin/run-solo), [`test/craft_harness/run_solo_test.clj`](../test/craft_harness/run_solo_test.clj)) | **Enforced for solo verify**; not for m3's shared-workdir cleaner. |
| R5 safe propagation | Explicit manifest, staged immutable store, atomic current-link rename, version pointer, in-flight refusal and kill-point tests ([`bin/craft-harness`](../bin/craft-harness), [`test/craft_harness/launcher_test.clj`](../test/craft_harness/launcher_test.clj)) | **Implemented**, subject to the pack-content integration gap above. |
| R6 human gate | All runners require `task.md`; hook blacklists it. Solo pauses after specification, stores only a random token hash, and resumes only when approval hashes match ([`bin/craft-harness`](../bin/craft-harness), [`bin/run-pack`](../bin/run-pack), [`bin/run-solo`](../bin/run-solo), [`hooks/pre-commit`](../hooks/pre-commit)) | **Implemented for solo specification**; two-pack has the pre-authored-task gate, not a specifier phase. |
| R7 mutation is not E2E | Current inspectors reject mutation invocation in light paths ([`bin/inspect-run`](../bin/inspect-run)) | **Future full implementation**: no Stryker config, selected-test audit, or Playwright-negative assert exists yet. |
| R8 recovery | Durable upstream queues/worktrees and persistent solo status; `run`/`upgrade` refuse in-flight state and `doctor` reports it ([`bin/craft-harness`](../bin/craft-harness), [`bin/run-solo`](../bin/run-solo)) | **Detection/refusal implemented**; automatic resume exists only for solo's approval pause, not arbitrary crashed phases. |
| R9 agent limits | Hook branch/path tripwire, doctor hash checks, adapter sandbox/permission flags, inspector commit audit ([`hooks/pre-commit`](../hooks/pre-commit), [`bin/craft-harness`](../bin/craft-harness), [`adapters/*/invoke`](../adapters/), [`bin/inspect-run`](../bin/inspect-run)) | **Partial/layered**: hooks are bypassable; source does not implement a universal push blocker or prove secrets removal. |
| R10 breakers/provenance | Adapter phase timeout, two-pack wake/session caps, solo phase/retry/run caps and verify-worktree cleanup, JSON manifests ([`adapters/run-with-timeout`](../adapters/run-with-timeout), [`bin/run-pack`](../bin/run-pack), [`bin/run-solo`](../bin/run-solo), [`adapters/scenario_manifest.bb`](../adapters/scenario_manifest.bb)) | **Implemented for current toy paths**; mutant/worker caps await real toolchains. |

## Decision log D1–D15

The full rationale and amendments remain authoritative in [`docs/decisions.md`](decisions.md). One-line consequences:

| Decision | Consequence |
|---|---|
| D1 | `doctor`, not pre-commit, hashes `.git/hooks/pre-commit`, because `.git` paths cannot be staged. |
| D2 | The hook is an accidental-change tripwire; real containment must come from CLI permissions plus post-run detection. |
| D3 | Upgrade interruption tests use named deterministic `kill -9` points instead of timing races. |
| D4 | WSL Ubuntu is the Windows-host reference environment; Git Bash evidence is provisional. |
| D5 | The entire fork was normalized to LF once; upstream script changes are sanctioned only as EOL normalization. |
| D6 | In-flight means upstream worktrees/queues or non-done solo state; launcher metadata itself is not a relocated queue. |
| D7 | A headless wake-up is a fresh `invoke` with the standard message; consumption is proven from durable inbox state. |
| D8 | Native-Linux Codex certification moved to the v0.1 exit; R1 genericity remains open until it passes. |
| D9 | tmux windows start their default shell; zsh helpers run as shebang subprocesses so first-run zsh UI cannot consume wake keys. |
| D10 | Git author identity is `oscaruiz <oscarruizf@gmail.com>` in repository-local config. |
| D11 | The m3 toy two-pack uses one shared workdir and a runner-owned queue relay; faithful per-role worktrees are deferred. |
| D12 | Unattended Claude window runs require a scoped tool allowlist in addition to `acceptEdits`; blanket bypass was rejected as less faithful. |
| D13 | Wrappers write durable logs because TUI capture collapses output, and inspector handoff discovery recurses for batch nesting. |
| D14 | `run-solo` constructs authoritative handoff routing headers; agents supply only the five content sections. |
| D15 | Headless tools are passed as absolute paths in environment variables because Claude's shell can reset `PATH`. |
