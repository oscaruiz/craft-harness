# D28 remediation — executable real-project gates

The D27 external audit found live fixtures-mirror-the-toy blindness. D28 closes
the implementation gaps without treating agent prose or TUI capture as a
verdict.

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

## Exit criteria

- Strict-contract parser negatives and exact ordered output are green.
- A verifier that only claims success cannot bypass a failing runner-owned test.
- Declared quality commands pass/fail in the runner and are inspected exactly.
- Retired two-pack invocation cannot pass or mutate a project.
- Full `bb test` is green, apart from any separately documented environmental
  tmux restriction or named upstream flaky test.
