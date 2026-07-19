# Adapter contract (design v2.2, R1)

The harness depends on no single agent CLI. An **adapter** is a directory
`adapters/<name>/` that makes one CLI drivable by the harness. The contract
has two invocation modes:

1. **Headless** (`invoke` / `info`) — used by the scenario runner and by the
   solo-pack's sequential phases. **Certified executable in m2** by
   `adapters/run-scenario`.
2. **Interactive window line** — the upstream swarm-forge launch mode
   (tmux window per role). Retained as upstream documentation; craft-harness's
   `two-pack-lite` Mode-2 runner was retired by D28 and is not a supported path.

A new adapter must cost **less than one day** (checklist at the end).

---

## Mode 1 — headless

Two executables in the adapter directory:

### `invoke --workdir <dir> --prompt-file <file>`

One agent turn:

- Run the CLI non-interactively (no TTY) on the prompt in `<file>`,
  operating inside `<dir>`.
- Exit 0 when the turn completed; non-zero on CLI failure.
- **Fresh process, no inherited transcript.** Consecutive `invoke`s share
  nothing but the durable state in the workdir (R4; decisions.md D7).
- **Confinement is the adapter's job** (decisions.md D2: the pre-commit
  hook is a tripwire; the CLI's permission configuration is the containment
  layer). The adapter passes whatever flags confine writes to the workdir
  and keep the agent inside its permission envelope.

For solo-pack, adapter completion is not a test verdict. After all agent turns,
the runner independently executes the required `test:` command and every
declared `quality:` command in a clean candidate worktree, then runs the
inspector. Only that combined result may report success (D28).

### `info`

Print a one-line `<cli-name> <version>` to stdout. Recorded in the run
manifest (R10 provenance); re-certifying when it changes is a convention,
not a gate (design §8).

### Wake-up semantics (decisions.md D7)

Upstream wake-ups are lossy tmux keystrokes; the durable truth is the file
inbox. Headless mode reproduces that: the runner delivers the handoff to
`.swarmforge/handoffs/inbox/new/` (playing the daemon's role) and issues a
fresh `invoke` whose prompt is the upstream wake message:

> You have new handoff mail. If idle, run ready_for_next.sh.

Consumption is proven only through the queue (`ready_for_next` semantics:
the file leaves `inbox/new/`, gains `dequeued_at`), never through the
transcript.

---

## Mode 2 — interactive window line

The upstream launcher (`swarmforge/scripts/swarmforge.bb`) starts one tmux
window per role from a `swarmforge.conf` line:

```
window <role> <agent> <worktree> [task|batch] [extra-cli-args...]
```

`<agent>` selects the CLI (upstream supports `claude`, `codex`, `copilot`,
`grok`). The launch commands upstream builds, verbatim:

- **claude** — `claude --append-system-prompt-file <role.prompt>
  --permission-mode acceptEdits -n "SwarmForge <display>"
  [extra-cli-args] "$(cat <role.prompt>)"`
- **codex** — `codex -C <worktree> [extra-cli-args] "$(cat <role.prompt>)"`

### Flag equivalences per concern

| Concern | Claude Code | Codex |
|---|---|---|
| Headless one-shot turn | `claude -p "<prompt>"` | `codex exec "<prompt>"` |
| Role/system prompt | `--append-system-prompt-file <file>` | no system-prompt flag upstream: the role prompt is passed as the user prompt (and `AGENTS.md` in the worktree is read natively) |
| Working directory | process cwd (`invoke` cd's into the workdir) | `-C <dir>` (or cwd) |
| Permissions / containment | `--permission-mode acceptEdits` | `--sandbox workspace-write` |
| Session naming (interactive) | `-n "SwarmForge <display>"` | n/a |
| Extra per-role args | window-line `extra-cli-args` | window-line `extra-cli-args` |

Exact headless flags are validated against the installed CLIs during the
paid certification runs (m2 B7); this table records intent and equivalence.

---

## The canonical certification scenario

`adapters/run-scenario --adapter <name> --out <dir> [--phase-timeout <sec>]`
builds a throwaway toy repo (committed `task.md`, broken `sut.sh`,
deterministic `test.sh`, baseline commit on `main`) and drives five phases,
each one `invoke` wrapped in `adapters/run-with-timeout` (whole process
group killed on timeout, exit 124 — R10):

1. **edit** — apply the fix `task.md` describes
2. **test** — make `./test.sh` pass
3. **commit** — commit the work on the enforced branch
4. **handoff** — queue a handoff in `.swarmforge/handoffs/outbox/`
5. **wake-up** — the runner delivers the handoff to the inbox and sends the
   wake message; the agent consumes it from the durable queue

Phase prompts begin with a stable `PHASE: <name>` first line (that is how
the deterministic fake agents in the test suite dispatch; real CLIs simply
read past it). After each phase the runner executes
`adapters/scenario-asserts <phase>` — pure functions of repo/git state; the
agent's word is never evidence. The run produces per-phase logs and a
manifest JSON (fork commit, adapter `info` output, baseline commit,
per-phase durations and outcomes) under `--out`.

Certification evidence lives in `docs/evidence/m2/<adapter>/`.

---

## New-adapter checklist (< 1 day)

1. `mkdir adapters/<name>` and write `invoke` (headless one-shot + the
   CLI's confinement flags) and `info`. Both executable, portable bash.
2. Add the CLI's row to the flag-equivalence table above; if upstream
   supports it as a window-line `<agent>`, note the interactive command.
3. Dry-run the plumbing for free: point `run-scenario --adapter` at the
   fake agents in `test/fixtures/fake-agent/` to sanity-check your harness
   environment, then run the real certification:
   `adapters/run-scenario --adapter <name> --out docs/evidence/m2/<name>`.
4. Commit the evidence (logs + manifest). Do not touch
   `swarmforge/scripts/` or the constitution articles.
