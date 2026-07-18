# Milestone 2 — Adapter contract (`adapters/`)

Goal: a documented, executable adapter contract over the canonical scenario
**edit → test → commit → handoff → wake-up**, certified against Claude Code
(primary) and Codex (genericity proof). Full reference:
`docs/design-v2.2.md` §1 (R1, R10), §7.2. Branch: `m2-adapters`.

## Owner conditions (kickoff, 2026-07-18)

- `adapters/CONTRACT.md` documents **both** invocation modes:
  1. **Headless** — `invoke` / `info` (defined below). Certified this
     milestone by the scenario runner.
  2. **Interactive window line** — the upstream `swarmforge.conf` shape
     `window <role> <agent> <worktree> [task|batch] [extra-cli-args...]`,
     with the per-CLI flag equivalences (e.g. Claude Code
     `--append-system-prompt-file`/`--permission-mode` vs Codex `-C`).
     Documented now; certified de facto in m3 when the packs run
     interactive windows.
- The wake-up interpretation is recorded as `docs/decisions.md` D7.
- **R10 pull-forward:** per-phase timeout and the run manifest (JSON
  provenance: fork commit, adapter + CLI version, starting commit, per-phase
  durations, outcome) arrive in this milestone, not m3 — the first real
  agent run is here. Caps on mutants/workers/wake-ups stay m3+.

## Cost rule

Agent scenario runs cost money. The automated suite exercises everything
through **fake agents** (deterministic bash scripts implementing the same
contract) — zero paid runs in `bb test`. Exactly **two** real CLI runs
happen in this milestone (B7), and only on the owner's explicit go-ahead.

## The contract (headless mode)

An adapter is a directory `adapters/<name>/` with two executables:

- `invoke --workdir <dir> --prompt-file <file>` — one shot: run the CLI on
  that prompt confined to the workdir; exit 0 when the turn completes.
  Confinement flags (permission mode, sandbox) are the adapter's
  responsibility (decisions.md D2: CLI permissions are the containment
  layer).
- `info` — print the CLI name/version for the run manifest.

Wake-up = a fresh `invoke` whose prompt is the upstream wake message
(decisions.md D7).

## Behaviors to implement (in order)

1. **Deterministic scenario asserts.** `adapters/scenario-asserts
   <phase> --workdir <dir> ...`: pure functions of repo/git state, one per
   phase — edit applied; `./test.sh` exits 0 (the runner runs it, it does
   not trust the agent); a new commit on the enforced branch touching no
   blacklisted path (same regex as `hooks/pre-commit`); a well-formed
   handoff in `.swarmforge/handoffs/outbox/` (headers per
   `swarm_handoff.bb`); after wake-up, `inbox/new/` empty and the handoff
   dequeued (`in_process`/`completed`, `dequeued_at` set). Tests fabricate
   good AND bad states for every assert (negative verification built in).
2. **Per-phase timeout with process containment.** Each phase runs in its
   own process group; on timeout the whole group is killed, the runner
   exits non-zero with a distinct code, the manifest records the hung
   phase, and the workdir is left as evidence. Tested with a fake stuck
   agent that spawns a child sleeper: both processes must be gone.
3. **Adapter resolution + `CONTRACT.md`.** `--adapter <name>` resolves
   `adapters/<name>/invoke`; unknown/non-executable adapters die with a
   clear message. The contract doc covers both modes (owner condition) and
   the "new adapter must cost < 1 day" checklist.
4. **Scenario runner orchestration.** `adapters/run-scenario --adapter
   <name> [--phase-timeout <sec>]` builds a throwaway toy repo (broken
   `sut.sh`, deterministic `test.sh`, committed `task.md`), drives the five
   phases (one `invoke` each), plays the daemon's delivery role
   (outbox → recipient `inbox/new/`, no tmux), runs the asserts after each
   phase, writes the run manifest. Tested end-to-end with fake agents:
   happy (full pass), silent (fails AT the handoff phase — failure
   attribution, not just pass/fail).
5. **Confinement negative verification.** A fake naughty agent commits to
   `task.md` with `--no-verify` (hook bypassed, per D2 the hook is a
   tripwire): the commit-phase assert must still fail the scenario.
6. **Real adapters, tested for free.** `adapters/claude-code/` and
   `adapters/codex/` build the actual CLI command lines; unit-tested with a
   stub binary first on `PATH` that records its argv — no network, no cost.
7. **Two paid certification runs** (owner-gated). `run-scenario` once per
   real adapter under WSL (D4); logs + manifests committed under
   `docs/evidence/m2/`.

## Exit criteria (all automated under `test/` except the last)

- Every assert passes on a fabricated good state and fails on each
  fabricated bad state.
- A stuck agent: runner returns within the timeout bound, no orphan
  processes, manifest names the hung phase.
- Fake happy agent: five phases green, complete manifest.
- Fake silent/naughty agents: scenario fails at the right phase, even with
  the pre-commit hook bypassed.
- Real adapters' command construction verified via argv-recording stub.
- Contract documented (both modes) + two real scenario logs committed (B7,
  owner go-ahead).

## Out of scope for milestone 2

Packs (m3–4), language wrappers (m5–6), interactive-mode certification
(de facto in m3), R10 caps on mutants/workers/wake-ups, OpenCode and any
third adapter (on demand via the contract, < 1 day).
