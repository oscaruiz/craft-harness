# v0.1 complete — executable real-project gates with honest boundaries

The D27 external audit found live fixtures-mirror-the-toy blindness. D28 closes
the implementation gaps without treating agent prose or TUI capture as a
verdict. The m6 follow-up makes retained command evidence tamper-evident against
non-malicious alteration; D29 and D30 state the semantic and threat-model limits
without presenting them as executable guarantees.

**Status: v0.1 complete.** D28, m6, D29 and D30 are delivered. D8 is closed with
the recorded external-environment caveat: Claude Code is fully adapter-scenario
certified; Codex has demonstrated real repository operation through three
adversarial audits but its formal scenario cannot initialize in this WSL
environment, including outside the harness.

## Delivered behavior

- `solo-pack` is the only registered light path; `two-pack-lite` is historical
  and `run-pack` refuses execution.
- `project.prompt` requires a strict non-empty `owns:` block and exact `test:`
  command; optional named `quality:` commands run in declaration order.
- `run-solo` rejects dirty tracked baselines, branch switching, non-descendant
  or out-of-scope candidates, and verifier worktree mutations.
- After agent phases, the runner executes the declared commands in a fresh
  candidate worktree with a per-command timeout and durable exact evidence.
- `run-solo` invokes `inspect-run` and reports success only after inspection.
- The inspector makes no mutation, toy-CRAP/DRY, or substring-traceability
  claims; real quality gates exist only when the project declares real commands.
- The exact guarantee is that declared commands ran and succeeded, not that they
  semantically cover every changed module or scenario; scenario traceability is
  advisory by design (D29).
- Retained command evidence detects accidental/non-malicious tampering. The
  same-user agent model does not defend against malicious forgery; OS isolation
  is the explicitly deferred v2 security path (D30).

## Exit criteria

- Strict-contract parser negatives and exact ordered output are green.
- A verifier that only claims success cannot bypass a failing runner-owned test.
- Declared quality commands pass/fail in the runner and are inspected exactly.
- Retired two-pack invocation cannot pass or mutate a project.
- Full `bb test` is green, apart from any separately documented environmental
  tmux restriction or named upstream flaky test.
- R1 is architecturally generic with Claude Code formally certified and Codex
  demonstrated in real audits; Codex formal scenario certification carries the
  external WSL initialization caveat recorded in D8.

## v0.1 verdict

The release is a sound, externally audited harness with executable project
gates and explicitly documented boundaries. No known fixtures-mirror-the-toy
defect remains represented as an enforced control.
