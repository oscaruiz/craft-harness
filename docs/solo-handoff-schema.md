# Solo-pack structured handoff schema (milestone 4, B1)

The solo-pack is sequential: N phases, each a fresh headless CLI process with no
inherited transcript (design §5, decisions.md D7). What one phase learned is
carried to the next **only** through a structured handoff written to durable
state — the direct heir of the original harness's `progress/` files. There is no
tmux, no daemon, no shared memory: the handoff is the contract.

`bin/handoff-validate.bb` is the single validator, reused by `bin/run-solo`
(which validates each phase's handoff before starting the next) and by
`bin/inspect-run` (post-run audit). A phase whose handoff fails validation stops
the run with attribution — the missing/malformed field is named.

## Format

A header block, one blank line, then five mandatory markdown sections:

```
id: <stable-id>
from: <phase that wrote this>
to: <phase that consumes it>
phase: <same as from>
created_at: <ISO-8601 UTC>
commit: <10-hex candidate commit>        # optional; present when the phase committed

# done
- what this phase actually accomplished

# decisions
- choices made and why (MUST be non-empty when the phase produced changes)

# assumptions
- what the phase took for granted

# open items
- unresolved questions for a later phase (may be "- none")

# commands executed
- the commands the phase ran (audit trail)
```

## Rules the validator enforces

- **Header shape.** Every header line is `key: value`; a bare line is a
  malformed header.
- **Required header fields.** `id`, `from`, `to`, `phase`, `created_at` must all
  be present and non-blank. `commit` is optional.
- **Required sections.** All five of `done`, `decisions`, `assumptions`,
  `open items`, `commands executed` must be present (section names are
  case-insensitive). A missing section fails, naming it.
- **Decisions with changes.** When the phase produced changes — signalled by
  `--changed` to the validator, or a non-empty `commit:` header — the
  `decisions` section must contain at least one non-blank line. A phase that
  changed the tree without recording a decision is rejected. Sections may
  otherwise be empty (e.g. `open items` when there are none).

Exit codes: `0` valid · `1` invalid (reason on stderr) · `2` usage.
