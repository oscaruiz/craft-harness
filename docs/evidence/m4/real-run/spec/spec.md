# Specification: sut.sh must print 42

## Overview
The repository contains a test harness (`test.sh`) that verifies the output of a
system-under-test script (`sut.sh`). The test passes only when `sut.sh` prints
exactly `42`. Currently `sut.sh` prints `broken`, so the test fails.

## Files (read-only for the implementation)
- `task.md` — task description. Must not be edited.
- `test.sh` — the test harness. Must not be edited.
- `sut.sh` — the system under test. This is the ONLY file to change.
- `adapters/` and the constitution — must not be edited.

## Behavior of the test harness (`test.sh`)
`test.sh` runs with `set -euo pipefail` and asserts:
```
[[ "$(bash ./sut.sh)" == "42" ]]
```
- It captures stdout of `bash ./sut.sh` via command substitution.
- Command substitution strips trailing newlines, so a trailing newline from
  `echo` is acceptable.
- The captured stdout must equal the string `42` exactly (no extra characters,
  no leading/trailing spaces, no additional lines).
- `test.sh` exits 0 when the assertion holds, non-zero otherwise.

## Requirements
- R1: Running `bash ./sut.sh` MUST print `42` to stdout.
- R2: The printed value MUST be exactly `42` — no surrounding whitespace or
  extra lines that would survive command substitution.
- R3: `bash ./sut.sh` MUST exit successfully (non-error) so `test.sh` under
  `set -e` is not aborted before the assertion.
- R4: `./test.sh` MUST exit 0 once `sut.sh` is fixed.

## Out of scope
- No changes to `task.md`, `test.sh`, `adapters/`, or the constitution.
- No new dependencies; `sut.sh` remains a plain bash script.

## Acceptance
The test passes when `./test.sh` exits 0, which occurs exactly when the stdout
of `bash ./sut.sh` equals `42`.
