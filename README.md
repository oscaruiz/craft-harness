<p align="center" style="color: red; font-weight: bold; font-size: 2em; font-style: italic; text-decoration: underline;">
Do not spend any money on a bankrbot SWARM token.
</p>

# SwarmForge

**A disciplined tmux-based agent orchestration platform that turns swarms of AI agents into reliable, professional software engineers.**

## Intent

This `main` branch is documentary: it explains the system and carries the shared operational scripts and default constitution articles. The runnable workflow branches carry the project-facing configurations, role prompts, and local constitution articles that define specific workflows.

SwarmForge is an agent coordination system that facilitates communication between agents working in different git worktrees.

It provides a shared structure for role-specific prompts, worktree assignment, tmux sessions, and message passing so multiple agents can collaborate on the same project without stepping on each other.

## Branches

The runnable SwarmForge configurations live on dedicated branches. Each branch contains the `swarmforge/swarmforge.conf`, local constitution articles, and role prompts for one workflow. At startup, its `./swarm` wrapper copies the shared operational scripts and shared constitution articles from `main` when they are not already present, then launches that branch's local configuration.

### `four-pack`

`four-pack` is the compact workflow. It keeps the swarm small while preserving a complete delivery path:

- `specifier` turns user intent into precise Gherkin acceptance specifications and asks for approval before handoff.
- `coder` implements approved behavior slices with TDD, unit tests, and generated acceptance tests.
- `refactorer` performs behavior-preserving cleanup, coverage improvement, CRAP and DRY review, mutation-site scans, and property-test support.
- `architect` owns high-level structure, dependency direction, mutation hardening, DRY review, soft Gherkin mutation, and final completion notification.

The normal flow is `specifier` -> `coder` -> `refactorer` -> `architect` -> `specifier`. Use this branch when you want disciplined development without splitting cleanup, architecture, hardening, and QA into separate agents.

### `six-pack`

`six-pack` is the full workflow. It separates each major quality gate into its own role:

- `specifier` turns user intent into accepted Gherkin specifications and end-to-end QA procedures.
- `coder` implements approved behavior slices with TDD, unit tests, and generated acceptance tests.
- `cleaner` performs local behavior-preserving cleanup, coverage improvement, CRAP and DRY review, and mutation-site scans.
- `architect` reviews module structure, boundaries, dependency direction, and property-test coverage.
- `hardender` performs mutation hardening, language mutation, CRAP and DRY verification, and soft Gherkin mutation.
- `QA` converts the specifier's QA procedures into executable scripts, runs final user-interface verification, checks handoff consistency, and sends completion notifications.

The normal flow is `specifier` -> `coder` -> `cleaner` -> `architect` -> `hardender` -> `QA` -> completion. Use this branch when you want each review and verification concern owned by a separate agent.

## Prerequisites

SwarmForge runs locally. Before starting a runnable branch, make sure the target machine has:

- `zsh`
- `git`
- `tmux`
- At least one configured agent backend, such as `codex`, `claude`, `copilot`, or `grok`

## Getting Started

In the directory where you want to use SwarmForge, choose a runnable branch and pull its contents without creating a Git remote:

```sh
BRANCH=four-pack
curl -L "https://github.com/unclebob/swarm-forge/archive/refs/heads/${BRANCH}.tar.gz" | tar -xz --strip-components=1
```

Use `BRANCH=six-pack` instead when you want the six-agent workflow. Do not use `main` for this command; `main` is documentary and stores the shared operational scripts, while the runnable branches provide the configurations and prompts intended for projects.

After copying a runnable branch, start the swarm from the target project:

```sh
./swarm
```

The `./swarm` wrapper keeps the runnable branch small. On first use, if `swarmforge/scripts/` is missing, it downloads the `main` branch archive, copies the shared operational scripts from `swarmforge/scripts/`, stages shared constitution articles from `swarmforge/constitution/articles/`, and then launches `swarmforge/scripts/swarmforge.sh`. Later runs reuse the existing local scripts directory instead of overwriting it.

The windows should open automatically.

To stop the swarm, close the first window listed in `swarmforge/swarmforge.conf`. That cleanup window shuts down the tmux sessions and closes the remaining tracked windows.

## What SwarmForge Does

SwarmForge is a lightweight, tmux-based orchestration layer that:

- Launches a **config-driven swarm** from a project-local `swarmforge/swarmforge.conf`
- Creates one tmux session per configured role and opens a terminal surface for each role when the selected backend supports it
- Reads behavior from project-local `swarmforge/roles/<role>.prompt` files plus a layered `swarmforge/constitution.prompt`
- Supports per-role backends such as `claude`, `codex`, `copilot`, or `grok`
- Puts the shared `swarmforge/scripts/` directory on each agent's `PATH`, including handoff helpers for active swarm communication
- Creates git worktrees under `.worktrees/` for roles assigned to dedicated worktree names
- Initializes a git repository in a new working directory when needed
- Keeps all swarm state local to the working directory in `.swarmforge/`

## Core Features

- **Config-Driven Topology** — The swarm shape comes from `swarmforge/swarmforge.conf`, not hardcoded shell variables.
- **Project-Local Roles** — Each role is defined by `swarmforge/roles/<role>.prompt` in the working tree being orchestrated.
- **Layered Constitution** — `swarmforge/constitution.prompt` directs agents to read article files under `swarmforge/constitution/articles/`.
- **Backend Selection Per Role** — A role can launch `claude`, `codex`, `copilot`, or `grok`.
- **Observable Swarm** — Open one Terminal window per role and watch the sessions in real time.
- **Self-Hosted & Lightweight** — Runs locally in tmux and Terminal with minimal machinery.

## Constitution Structure

Each runnable branch contains a `swarmforge/` directory with this general layout:

```text
swarmforge/
  swarmforge.conf
  constitution.prompt
  constitution/
    articles/
      project.prompt
      local-engineering.prompt
      local-workflow.prompt
      ...
  roles/
    <role>.prompt
    ...
```

`constitution.prompt` is the entry point. Runnable branches normally use it to tell agents to read every file in `swarmforge/constitution/articles/`.

Shared default articles live on `main` under:

```text
swarmforge/constitution/articles/
  engineering.prompt
  handoffs.prompt
  workflow.prompt
```

At startup, SwarmForge installs missing shared articles into the runnable branch's `swarmforge/constitution/articles/` directory before creating role worktrees. It also installs missing shared articles into each role worktree during script synchronization. Existing local files are skipped, so a runnable branch can override a shared article by committing an article with the same filename.

Pack-specific additions and exceptions should use explicit local filenames rather than editing shared articles. Current conventions are:

- `project.prompt` for the workflow's project shape and local topology.
- `local-engineering.prompt` for workflow-specific engineering rules.
- `local-workflow.prompt` for workflow-specific flow rules.

The `local-*.prompt` naming convention means "add to or specialize the shared default article for this runnable branch." Use it when the shared article remains valid and the branch only needs extra requirements, exceptions, or narrower instructions. Do not use `local-*.prompt` for a full replacement; use the shared filename instead when the branch intentionally overrides the shared article.

For example, `main` can provide a shared `workflow.prompt`, while `six-pack` can add `local-workflow.prompt` for QA-specific handoff behavior. If a branch needs to replace the shared workflow article completely, it can commit its own `workflow.prompt`; startup will treat that local file as an override and will not copy the shared one over it.

## Roles

Each role in `swarmforge/swarmforge.conf` maps to a corresponding `swarmforge/roles/<role>.prompt` file.

## How It Works

In a runnable branch:

1. SwarmForge reads `swarmforge/swarmforge.conf`.
2. The root `./swarm` wrapper copies shared helper scripts, terminal adapters, and shared constitution articles from the `main` branch when they are not already present.
3. Startup installs missing shared constitution articles into `swarmforge/constitution/articles/`, skipping any local article file that already exists.
4. Startup validates the configured role prompts, helper scripts, and terminal adapters.
5. If the target directory is not already a git repository, startup initializes one and creates the first commit.
6. Startup creates one git worktree per configured role under `.worktrees/`, unless the role is assigned to `master` or `none`.
7. Startup syncs `swarmforge/scripts/` and missing shared constitution articles into each role worktree and puts that local scripts directory on each agent's `PATH`, so agents use `swarm-handoff` without reaching back into the master checkout.
8. SwarmForge creates tmux sessions, opens terminal windows, and launches each configured backend in its assigned worktree.
9. Roles communicate through sequenced handoff files. Agents write `.swarmforge/notify/request` and run `swarm-handoff`; the helper assigns message ids and sequence numbers, archives sent messages, records logbook entries, validates receive ordering, and requests resends when gaps are detected.

## Handoff Helpers

Startup syncs the shared helper scripts into every role worktree under `swarmforge/scripts/` and puts that local directory on the agent's `PATH`. Agents should use the request-file form of `swarm-handoff` rather than running helper scripts from another worktree.

The agent-facing command is stable:

```sh
swarm-handoff
```

Before running it, write `.swarmforge/notify/request` in the assigned worktree. To send a normal handoff:

```text
command: send
target: <target-role>
file: ./tmp/<target-role>-handoff.txt
```

Priority handoffs add a priority field:

```text
command: send
target: <target-role>
file: ./tmp/<target-role>-handoff.txt
priority: 00
```

To receive a saved incoming message:

```text
command: receive
file: ./tmp/incoming-handoff.txt
```

To complete an accepted queue file:

```text
command: complete
file: ./.swarmforge/handoffs/queue/accepted/<queue-file>.txt
```

The shared script directory also contains implementation helpers:

- `swarm-handoff` is the public entry point and low-level tmux transport.
- `send-handoff.sh` builds sequenced protocol messages, archives outbound handoffs, sends them, and logs successful sends.
- `receive-handoff.sh` normalizes incoming captures, validates protocol messages, records received or queued entries, queues accepted handoffs for role work, and generates resend requests when ordering gaps appear.
- `resend-handoff.sh` replays archived outbound handoffs in response to resend requests.
- `handoff-lib.sh` contains shared parsing, id generation, sequence, archive, and logbook functions.

Agents normally call only `swarm-handoff` with no arguments after writing `.swarmforge/notify/request`. The explicit subcommands and other scripts are kept separate so the transport, sequencing, receive validation, and replay behavior are easy to inspect, test, and use manually when needed.

## Avoiding Escalation During Handoffs

Handoff commands must stay inside the agent's sandbox as much as possible. The two recurring escalation triggers are direct access to the tmux socket and direct deletion of accepted queue files.

When an agent sends, receives, or completes a handoff, it should write `.swarmforge/notify/request` and run:

```sh
swarm-handoff
```

This stable command shape is intentional. Some command approval systems approve future commands by their literal command prefix. If every handoff uses a different command line, each target, file path, priority, or queue filename can create a new approval prompt. The request file moves those variable arguments into local project state, so a single approval for `swarm-handoff` covers normal handoff send, receive, resend, and completion operations.

Do not have agents run `tmux -S <socket> ...` directly. The `swarm-handoff` transport detects when it is already running inside a tmux pane and uses the inherited tmux client context:

```sh
tmux send-keys -t "$TARGET_SESSION" ...
```

That avoids naming or opening the tmux socket from the agent process. Some agent command runners sanitize the environment and remove `TMUX` even though the agent itself was launched inside tmux. To handle that, `swarmforge.sh` writes the active tmux client value to `.swarmforge/tmux-env` after creating the swarm sessions and syncs that file into each role worktree. If `swarm-handoff` starts without `TMUX`, it restores `TMUX` from `.swarmforge/tmux-env` and still uses plain `tmux send-keys`.

The explicit `tmux -S <socket>` path is kept only as a fallback for manual helper use outside tmux.

Do not ask agents to remove queue files with `rm`, `rm -f`, or ad hoc cleanup commands. The completion helper moves the accepted queue file to `.swarmforge/handoffs/queue/completed/`, which keeps cleanup predictable and avoids destructive-command escalation.

The operational rule is simple: agents write `.swarmforge/notify/request`, run `swarm-handoff`, do not call `tmux` directly, and do not delete queue files directly.

## Communication Protocol

Agents communicate by file-based messages sent through tmux. A sender writes only the role-specific handoff body, then writes `.swarmforge/notify/request`:

```text
command: send
target: <target-role>
file: ./tmp/<target-role>-handoff.txt
```

Then it runs `swarm-handoff`. Priority handoffs add `priority: NN` to the request file; normal handoffs default to priority `50`.

The send helper wraps that body with protocol fields:

```text
message type: handoff
message id: YYYYMMDD-HHMMSS-XXXXXX
sender role: sender
target role: target
message sequence: NNNNNN
message priority: 50
branch name: sender-branch
commit hash: 1234567890
```

The `message id` timestamp is human-readable and roughly sortable. Message type, sender, target, and sequence are separate fields so the id does not duplicate protocol data. Sequence numbers are per sender-target stream. For example, `coder-cleaner` has its own sequence, and `cleaner-coder` has a separate reverse sequence. The six-character suffix prevents id collisions when two messages are created in the same second.

The helper reads `branch name` and the 10-character `commit hash` from the sender's current git worktree at send time. Agents should commit the state being handed off, send the handoff immediately, and avoid making another commit until `swarm-handoff` completes successfully. The generated branch and commit fields are the authoritative state for the receiver to merge.

The sender archives each outbound message under:

```text
.swarmforge/handoffs/sent/<sender-target>/<sequence>.txt
```

After the low-level tmux send succeeds, the sender appends a `sent` entry to `logbook.jsonl`. If the tmux notification fails, the message remains archived for possible manual recovery, but no `sent` logbook entry is written.

When an agent receives a message, it saves the complete incoming text to a file and runs:

```text
command: receive
file: ./tmp/incoming-handoff.txt
```

Then it runs `swarm-handoff`.

The receive helper ignores any leading terminal noise before the first valid `message type: handoff` or `message type: resend-request` header. It archives and queues only the normalized protocol message, not the noisy capture.

The receive helper checks `message type`, `message id`, sender, target, sequence, and priority. If a handoff is valid and in order, it archives the message, appends a `received` entry to `logbook.jsonl`, updates the last processed sequence for that sender-target stream, copies the handoff into the accepted queue, and prints the queued file path:

```text
.swarmforge/handoffs/queue/accepted/<priority>-<timestamp>-<sender-target>-<sequence>.txt
```

Agents process only accepted queue files and do not rerun `swarm-handoff receive` on them. After the corresponding role work is complete, agents complete accepted queue files with:

```text
command: complete
file: ./.swarmforge/handoffs/queue/accepted/<queue-file>.txt
```

Then they run `swarm-handoff`. The completion helper moves the queue file into `.swarmforge/handoffs/queue/completed/`.

`swarm-handoff` also keeps explicit command forms for manual helper implementation and diagnostics:

```sh
swarm-handoff send <target-role> --file ./tmp/<target-role>-handoff.txt
swarm-handoff receive --file ./tmp/incoming-handoff.txt
swarm-handoff complete --file ./.swarmforge/handoffs/queue/accepted/<queue-file>.txt
swarm-handoff <target-role-or-index> --file <message-file>
```

Agents should not use the low-level target/file transport form for normal handoffs because it bypasses sequencing, archiving, resend recovery, and logbook handling. Agents should prefer the no-argument request-file form over explicit subcommands to keep command approvals stable.

## Recovery Strategy

The protocol is designed for eventual correction rather than tmux-pane sniffing.

If `swarm-handoff receive` sees the next expected handoff sequence, it queues the handoff for role work. If it sees a sequence gap, it archives the out-of-order message, appends a queued logbook entry, sends a `resend-request` back to the sender, and prints `DO NOT PROCESS`.

The resend request is itself a sequenced message in the reverse sender-target stream:

```text
message type: resend-request
message id: YYYYMMDD-HHMMSS-XXXXXX
sender role: receiver
target role: original-sender
message sequence: NNNNNN
message priority: 50
branch name: receiver-branch
commit hash: 1234567890
resend stream: original-sender-receiver
resend sequences: 000003-000005
```

The missing range includes the out-of-order message that exposed the gap. That keeps recovery simple: the original sender replays one contiguous range, and the receiver processes messages only when they arrive in sequence.

When a sender receives a `resend-request`, `swarm-handoff receive` calls `resend-handoff.sh`, which reads archived messages from `.swarmforge/handoffs/sent/` and resends each requested sequence. Resent messages are logged as sent only after the low-level notification succeeds.

Duplicate or stale messages are archived and logged as queued, but the helper prints `DO NOT PROCESS`. Agents should not merge, apply, or otherwise act on a handoff unless it appears in the accepted queue.

## The `swarmforge.conf` File

`swarmforge/swarmforge.conf` defines the swarm window-by-window. Each line has this form:

```conf
window <role> <agent> <worktree>
```

You can define as many windows as your project needs. Each `role` maps to a corresponding prompt file at `swarmforge/roles/<role>.prompt`, so a config containing `architect`, `coder`, `reviewer`, `research`, and `release` windows would expect:

- `swarmforge/roles/architect.prompt`
- `swarmforge/roles/coder.prompt`
- `swarmforge/roles/reviewer.prompt`
- `swarmforge/roles/research.prompt`
- `swarmforge/roles/release.prompt`

This lets each project choose its own swarm shape instead of being locked to a fixed set of roles.

Example config:

```conf
window coordinator codex master
window coder codex coder
window refactorer codex refactorer
window architect codex architect
```

In the example above, the agents run in these worktrees:

- `coordinator` -> main working directory on `master`, and is the cleanup window because it is listed first
- `coder` -> `.worktrees/coder`
- `refactorer` -> `.worktrees/refactorer`
- `architect` -> `.worktrees/architect`

If a window uses `master` as its worktree name, SwarmForge does not create `.worktrees/master`; that role runs in the main working directory on the `master` branch.

## tmux Behavior

SwarmForge uses a project-specific tmux socket recorded in `.swarmforge/tmux-socket`, so each project swarm is isolated from other tmux sessions. It also honors tmux `base-index` and `pane-base-index` settings when launching agents and sending notifications, so configurations that number windows or panes from `1` work without requiring users to change their tmux preferences.

## Terminal Behavior

SwarmForge opens trackable terminal windows or tabs through a small terminal backend adapter.

Default detection:

- If AppleScript is available, SwarmForge opens macOS Terminal.app windows.
- Otherwise, if `wt.exe` is available, SwarmForge opens Windows Terminal windows.
- Otherwise, SwarmForge attaches the cleanup tmux session in the current shell.

After copying a runnable branch, set `SWARMFORGE_TERMINAL` to override detection:

```sh
SWARMFORGE_TERMINAL=ghostty ./swarm
SWARMFORGE_TERMINAL=terminal-app ./swarm
SWARMFORGE_TERMINAL=windows-terminal ./swarm
SWARMFORGE_TERMINAL=none ./swarm
```

Use `ghostty` when you want SwarmForge to open Ghostty tabs instead of the default Terminal.app windows. Use `windows-terminal` when you want SwarmForge to open Windows Terminal windows from WSL. Use `none` when you want SwarmForge to skip terminal automation and attach the cleanup tmux session in the current shell.

### Adding A Terminal Backend

The shared terminal backends are carried on `main` under `swarmforge/scripts/terminal-adapters/`. Runnable branches copy those scripts at startup. To add a new backend, update `main` by creating one file named after the backend:

```text
swarmforge/scripts/terminal-adapters/wezterm.sh
```

The file must define this small contract:

```sh
terminal_backend_label() {
  echo "WezTerm"
}

terminal_backend_can_open_sessions() {
  return 0
}

terminal_backend_tracks_windows() {
  return 0
}

terminal_open_session() {
  local session="$1"
  local title="$2"
  local sibling_id="${3:-}"

  # Open a terminal surface that runs:
  # cd "$WORKING_DIR" && exec tmux -S "$TMUX_SOCKET" attach-session -t "$session"
  #
  # Print a stable window/tab id to stdout.
}

terminal_window_exists() {
  local window_id="$1"

  # Return 0 if the id from terminal_open_session still exists.
  # Return nonzero otherwise.
}

terminal_close_window() {
  local window_id="$1"

  # Close the id from terminal_open_session.
}
```

If the terminal can open sessions but cannot return stable ids for open/check/close, keep `terminal_backend_can_open_sessions` as `return 0` and set `terminal_backend_tracks_windows` to `return 1`. SwarmForge will open one surface per session and skip the watchdog for that backend. `swarmforge/scripts/terminal-adapters/windows-terminal.sh` is an example of this launch-only style.

If the backend cannot open sessions at all, set both capability functions to `return 1`; SwarmForge will attach the cleanup tmux session in the current shell. Only edit `swarmforge/scripts/swarm-terminal-adapter.sh` when adding aliases or changing default auto-detection.

## Window Behavior

Each visible agent window is attached to a tmux session. That means terminal selection, copy, and paste may follow tmux and terminal-emulator rules rather than ordinary text-field behavior. If copy or paste feels unusual, check whether tmux copy mode is active before assuming the agent is stuck.

The first window in `swarmforge.conf` is the cleanup window. Closing that top configured window is the intentional shutdown path: SwarmForge tears down the tmux sessions, closes the remaining tracked windows, and shuts down the swarm.

Closing any other tracked window is non-destructive. The watchdog reopens that window and attaches it back to the same tmux session, so the agent state and terminal history remain intact. This is often the simplest way to recover a window that has landed in an unfamiliar tmux mode or otherwise feels stuck.
