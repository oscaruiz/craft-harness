# m8 — cheap security hardening (v0.2-lite)

m7 is complete (D32–D37: executable Gherkin, fail-closed acceptance/mutation
evidence, external audit remediations); its milestone doc is preserved in git
history. This milestone is the proportional response to the REAL threat model
the external Codex audit identified — after the full OS-sandbox tier was ruled
over-engineering for solo use. Three bounded controls, recorded as **D38**:
each is either cheaply enforceable (then built TDD with a negative test proving
the control) or honestly declined (documented, never half-built — a security
theater control is worse than an honest "not enforced here").

## The three controls

1. **Task input is untrusted (prompt injection — the highest real risk).**
   The human R6 gate is the injection firewall and must be provably immune to
   injected "approval" text: the token is minted after the specify turn, never
   enters a phase prompt/environment, a planted `APPROVED` is discarded at gate
   time, and the negative test drives a specifier that OBEYED an injected
   approval payload (self-consistent `APPROVED`+`token.sha256` pair) — the run
   still pauses and only the genuine human path proceeds. Prompt-side
   data-not-instructions contract lines are defense-in-depth only, asserted
   exact-line (D22 coda), never counted as a control (CLAUDE.md hard rule).

2. **Scoped permission allowlist replaces `--dangerously-skip-permissions`.**
   Without breaking real runs: skip-all existed because headless turns cannot
   answer permission prompts (D23; the D11/D12/D13 hang class). The adapter now
   allowlists exactly Edit/Write, `Bash(git:*)`, and the declared
   `test:`/`quality:`/`accept:`/`mutation:` commands (structurally parsed from
   the workdir contract; no blanket first-token rules), grants the runner
   session dir via `--add-dir` for worktree phases, and denies
   WebFetch/WebSearch. Validated against the installed CLI: real turn completes,
   non-allowlisted commands are denied cleanly (no hang), allowlisted ones run.

3. **Network egress restricted to the enforceable slice.** Investigated on the
   real WSL host: unprivileged `unshare -rn` works; selective per-host
   allowlisting does not exist below the declined OS-isolation tier — so it is
   declined, not faked. Enforced instead: phase egress at the adapter tool
   layer (control 2), and an opt-in `network: none` contract key that makes
   `run-six` execute every runner-owned gate command inside a no-network
   namespace, fail-closed on hosts that cannot create one.

## Exit criteria (met)

- Injected approval text in `task.md` cannot approve: negative e2e proves the
  planted-pair worst case pauses, refuses to resume, and the human path still
  works. Exact-line prompt-contract test across all four phases.
- Adapter tests prove: no skip-all flag, exact declared-command allowlist
  entries, no blanket rules, explicit WebFetch/WebSearch denial, `--add-dir`
  only under runner env. Real-CLI smoke: flags accepted, clean denial, no hang.
- `network: none`: green run proves gates executed with egress genuinely
  blocked (a gate that fails when the network is reachable); a network-needing
  gate turns the run red attributed; a host without namespaces refuses
  fail-closed; the parser rejects every other `network:` value.
- D38 records what was hardened and what was declined-as-too-costly.
- Full `bb test` green in WSL; `run-solo` and all CLOSED/sealed gates
  byte-for-byte untouched.
