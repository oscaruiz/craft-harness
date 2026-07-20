---
human-approved: true
---
# Task: implement the core + web modules and their acceptance scenarios

Provide a `core` module returning `CORE-OK` through a public entrypoint
`core/api.sh` (private implementation in `core/lib.sh`), and a `web/app.sh`
that composes it as `WEB[CORE-OK]`, reaching core ONLY through its public API.

Make the declared unit tests (`run-unit.sh`) and every human-approved acceptance
scenario pass. Respect the architecture and duplication gates.
