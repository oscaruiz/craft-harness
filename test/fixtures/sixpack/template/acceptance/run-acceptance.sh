#!/usr/bin/env bash
# Genuine, dependency-free executable-Gherkin runtime (m7). This is the project's
# declared `accept:` entrypoint. It really parses features/*.feature, dispatches
# each step to the project's step handlers (acceptance/steps.sh, written by the
# coder), executes them, and writes a machine-readable per-scenario report to
# $CRAFT_ACCEPT_REPORT (docs/acceptance-report-schema.md).
#
# It is a REPORTER: it exits 0 once it has run the scenarios and written the
# report. The pass/fail VERDICT is the runner's (run-six), derived structurally
# from the scenario statuses — never from this command's exit code or a substring.
# A scenario whose step has no matching handler is reported "undefined" (an
# unimplemented scenario); a step that asserts false makes the scenario "failed".
#
# The gate consumes only the report SCHEMA, so a real project can swap this for a
# cucumber/APS adapter emitting the same NDJSON and the identical runner gate holds.
set -uo pipefail

report="${CRAFT_ACCEPT_REPORT:?CRAFT_ACCEPT_REPORT must be set by the runner}"
: > "$report"

have_steps=0
if [[ -f acceptance/steps.sh ]]; then
  # shellcheck source=/dev/null
  source acceptance/steps.sh 2>/dev/null || true
  declare -F run_step >/dev/null && have_steps=1
fi

emit() { printf '{"scenario":"%s","status":"%s"}\n' "$1" "$2" >> "$report"; }
# The scenario ID is the tag WITHOUT its '@' marker (schema: "SUT-1", not "@SUT-1"),
# matching the runner's approved-scenario snapshot.
id_of() { grep -oE '@[A-Z][A-Z0-9]*-[0-9]+' <<<"$1" | head -1 | sed 's/^@//'; }

cur_id=""; declare -a STEPS=()
run_current() {
  [[ -n "$cur_id" ]] || { STEPS=(); return; }
  local status=passed step rc
  if (( have_steps == 0 )); then
    emit "$cur_id" undefined; cur_id=""; STEPS=(); return
  fi
  for step in "${STEPS[@]:-}"; do
    [[ -n "$step" ]] || continue
    run_step "$step"; rc=$?
    if (( rc == 2 )); then status=undefined; break; fi
    (( rc == 0 )) || status=failed
  done
  emit "$cur_id" "$status"
  cur_id=""; STEPS=()
}

shopt -s nullglob
pending=""
for f in features/*.feature; do
  while IFS= read -r line || [[ -n "$line" ]]; do
    t="${line#"${line%%[![:space:]]*}"}"   # left-trim leading whitespace
    case "$t" in
      @*)                                 pending="$(id_of "$t")" ;;
      "Feature:"*)                        run_current ;;
      "Scenario:"*|"Scenario Outline:"*)  run_current; cur_id="$pending"; pending="" ;;
      "Given "*|"When "*|"Then "*|"And "*|"But "*) [[ -n "$cur_id" ]] && STEPS+=("$t") ;;
      *) : ;;
    esac
  done < "$f"
  run_current
done
exit 0
