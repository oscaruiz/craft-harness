#!/usr/bin/env bash
# Genuine multi-module unit test (the project's declared `test:` entrypoint).
# Exercises both modules' public behavior — fails loudly if either is missing or
# wrong. Deliberately NOT ./test.sh: a real project ships its own test command.
set -euo pipefail
core="$(bash core/api.sh)"
[[ "$core" == "CORE-OK" ]] || { echo "unit: core/api.sh => '$core' (want CORE-OK)"; exit 1; }
web="$(bash web/app.sh)"
[[ "$web" == "WEB[CORE-OK]" ]] || { echo "unit: web/app.sh => '$web' (want WEB[CORE-OK])"; exit 1; }
echo "unit: ok (core + web)"
