# craft-harness — Instructions for Claude Code

This repo is a fork of `unclebob/swarm-forge` evolving into the harness
described in `docs/design-v2.2.md`. **Read that document before touching anything.**

## Current state
- Active milestone: see `docs/current-milestone.md`. One milestone per
  session/PR. Do not start the next one until the current exit criteria are
  met and demonstrated.

## Hard rules (from design v2.2)
- ❌ Do NOT modify `swarmforge/scripts/` or upstream constitution articles.
  Everything of ours lives in new files (`bin/`, `adapters/`, `tools/`,
  `hooks/`, `lang-*.prompt` articles) or in our own branches.
- ❌ Do NOT implement critical controls as prompt text alone: they are either
  executable constraints (launcher, wrapper, pre-commit) or they carry
  automated negative verification in the tests.
- ✅ TDD at behavior granularity: a block of tests defining the behavior →
  implementation → green → refactor. The suite is the spec.
- ✅ New scripts: portable bash (macOS/Linux/WSL), `set -euo pipefail`, no
  dependencies beyond git, bash and babashka (already required upstream).
- ✅ Every launcher subcommand fails with a clear message and a non-zero exit
  code; it never leaves hybrid state (staging + atomic swap).

## Conventions
- Small commits per behavior, messages in English, milestone prefix (`m1:`).
  Never push unless asked.
- If the design and reality clash, STOP and report it: the design gets
  corrected with a note in `docs/decisions.md` — it is never silently ignored.