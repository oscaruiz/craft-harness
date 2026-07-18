# Decisions log

Corrections and clarifications to `docs/design-v2.2.md`, per CLAUDE.md: when
design and reality clash, the design is corrected here — never silently
ignored.

## D1 — `.git/hooks` integrity belongs to `doctor`, not to the pre-commit blacklist (2026-07-18, m1)

Git never stages paths under `.git/`, so a pre-commit hook that inspects
staged paths can never see `.git/hooks`. The R9 blacklist keeps `task.md`,
constitution articles and `adapters/`; integrity of the **installed hook
itself** is verified by `doctor` via content hash, exactly like any other
managed file.

## D2 — The pre-commit hook is a tripwire, not a wall (2026-07-18, m1)

Agents can bypass any git hook with `git commit --no-verify`. The hook exists
to stop accidental violations and to leave evidence of deliberate ones; real
containment of the agent is the CLI's permission configuration (adapter-level,
milestone 2+). R9 is therefore layered: hook (tripwire) + doctor hashes
(detection) + CLI permissions (containment). The hook alone is not claimed as
enforcement.

## D3 — Deterministic fault injection implements the design's kill test (2026-07-18, m1)

The interrupted-`upgrade` exit criterion ("simulate with kill in tests",
design §7.1) is implemented with `CRAFT_HARNESS_TEST_DIE_AT=<step>`: the
launcher `kill -9`s itself at the named step, and the test iterates every
step (post-staging, pre-swap, mid-swap). Same guarantee as a wall-clock kill,
without the flakiness of racing a timer against the script.

## D5 — One-time LF normalization of the whole tree, upstream files included (2026-07-18, m1)

The fork's committed blobs contained CRLF line endings, which break every
shebang under Linux — the suite could not run at all on the reference
platform (see D4). `.gitattributes` now forces `* text=auto eol=lf` and the
tree was renormalized in a single dedicated commit, verified EOL-only with
`git diff --ignore-cr-at-eol` (empty over upstream paths). This is the one
sanctioned touch of `swarmforge/scripts/`: content is byte-identical modulo
`\r`. For future upstream merges set `merge.renormalize=true` (repo-local
config, not versioned — re-set it after a fresh clone).

**Owner ratification (2026-07-18):** ratified, with a process note — per
CLAUDE.md, stop-and-report must precede action on any design/reality clash;
here the normalization was applied before the ruling. Next clash: pause first.

## D6 — "Session in flight" is defined by the upstream plumbing's structures (2026-07-18, m1)

`doctor` (and the run/upgrade refusals built on it) detects an in-flight
session by probing the state the packs will actually leave behind — the
upstream swarm-forge handoff plumbing, verified in source
(`handoff_lib.bb`, `swarmforge.bb`, `ready_for_next_task.bb`):

- agent worktrees under `.worktrees/`
- unconsumed handoffs in `.swarmforge/handoffs/inbox/new/`
- half-processed handoffs in `.swarmforge/handoffs/inbox/in_process/`
- unsent handoffs in `.swarmforge/handoffs/outbox/`

`.craft-harness/` is launcher-only metadata (installed store, `current`
pointer, recorded pack and branch) and is NOT a session-state relocation:
R8's persistent queues are exactly the upstream directories above. The
solo-pack's sequential runner (m4) will define its own session state and
extend doctor's probes then — m1 deliberately freezes only the upstream
probes, so no m3–4 contract is constrained by an m1 test.

## D4 — Reference platform for the suite and the demo is WSL (2026-07-18, m1)

The dev machine is Windows; supported platforms are macOS/Linux/WSL. The test
suite and the milestone demos run under WSL (Ubuntu), not Git Bash: Git Bash
results are considered provisional only.
