#!/usr/bin/env bash
# Architecture gate — the architect role's runner-enforced criterion (a declared
# `quality:` command). Real structural rule: web/ must reach core ONLY through its
# public API (core/api.sh); reaching into the private impl (core/lib.sh) is a
# boundary violation. Genuine — a planted cross-module reference makes it exit 1.
set -euo pipefail
if grep -rnE 'core/lib\.sh' web/ 2>/dev/null; then
  echo "arch: FAIL — web/ reaches into core/lib.sh; depend on core only via core/api.sh"
  exit 1
fi
echo "arch: ok (web depends on core only through its public API)"
