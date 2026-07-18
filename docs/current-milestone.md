# Milestone 3 — two-pack-lite (first real agent pipeline)

Goal: our light-path branch running end-to-end with real agents in upstream's
tmux mode: coder → cleaner with our own `cleaner.prompt` (no mutation),
`task.md` gate enforced, and the two star negative asserts proven on real
evidence. Reference: `docs/design-v2.2.md` §1 (R3, R6, R9, R10), §5, §7.3.

## Standing constraints
- D8: the codex certification run must land before this milestone's PR
  merges. It does not block starting.
- First time agents run inside tmux with upstream zsh scripts — the mode m2
  documented but did not certify. Expect environment friction; that is what
  behavior 1 is for.

## Behaviors to implement (in order)

1. **tmux/zsh smoke** (`bin/smoke-tmux`). Opens a throwaway tmux session,
   runs a trivial upstream zsh helper in a window, sends a keystroke wake-up,
   asserts the window received it, tears down. Run it under WSL before
   anything else; its output is the first artifact of `docs/evidence/m3/`.
2. **Branch `two-pack-lite`.** Created from upstream `two-pack`. Our own
   `cleaner.prompt`: mutation hardening removed; CRAP ≤ 6 and DRY kept; the
   prompt invokes quality via our wrapper interface (never a tool directly).
   `swarmforge.conf` uses the adapter window-line shapes from
   `adapters/CONTRACT.md` (claude-code for both roles initially). No upstream
   file edited: role prompts are per-branch by upstream design.
3. **Stub toolchain** (`tools/toy/crap.sh`, `tools/toy/dry.sh`). Implements
   the normalized wrapper output contract (score / threshold / offenders)
   deterministically for the toy task's language-free code, with env knobs to
   simulate a failing score. This seeds the wrapper contract before m5–6 and
   makes the cleaner's gate behavior testable without real language tools.
   The executed threshold (6) must appear in the wrapper's log output.
4. **Post-run inspector** (`bin/inspect-run <session-dir> <project>`). Pure
   deterministic checks over logs + repo state, usable by tests and by the
   real run alike: (a) NO mutation invocation anywhere in the session logs
   (no PIT/Stryker/mutate wrapper calls — the star negative assert); (b) the
   executed CRAP threshold was 6 (from wrapper logs, not from the prompt);
   (c) commits only on the enforced branch, touching no blacklisted path;
   (d) handoffs well-formed and consumed; (e) wake-up/iteration count within
   the R10 cap. Tested in the bb suite against fabricated good and bad
   session dirs (fake-driven), including a planted mutation call that MUST
   turn the inspector red.
5. **Session driver with breakers** (`bin/run-pack`). Boots the tmux session
   for a pack on a project, enforces R6 (refuses without `task.md` — add the
   negative test), applies a wake-up cap and a per-session timeout (R10),
   collects logs into a session dir, and hands off to `inspect-run` at the
   end. Suite-tested with fake agents in the windows.
6. **The real run.** One toy task through two-pack-lite with claude-code in
   both windows, under WSL. Evidence into `docs/evidence/m3/`: session logs,
   run manifest (adapter + CLI versions, fork commit, durations), and the
   inspector's green report. Ask before firing; one run, stop-and-report on
   environment failure, no retries.

## Exit criteria
- `smoke-tmux` green under WSL (evidence committed).
- bb suite green, including: inspector catches a planted mutation call;
  wrong threshold in wrapper logs turns it red; `run-pack` refuses without
  `task.md`; breaker kills a session exceeding the wake-up cap.
- Real-run evidence in `docs/evidence/m3/` with the inspector green:
  mutation never invoked, threshold 6 executed, R6 exercised.
- ~~D8 cleared (codex log in `docs/evidence/m2/`) before merge.~~ **Amended
  (see `docs/decisions.md` D8 amendment):** D8 is downgraded to a v0.1 exit
  item and no longer blocks the m3 merge. R1 genericity stays OPEN until the
  Codex run lands.

## Out of scope for milestone 3
solo-pack (m4) · real language toolchains (m5–6) · six-pack · long-running
daemon behaviors beyond what one toy task needs · any upstream edit.