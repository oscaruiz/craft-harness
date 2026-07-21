#!/usr/bin/env bash
# Genuine miniature mutation-testing runtime for the m7 mutation-gate fixture (D35).
# This is the project's declared `mutation:` command. It stands in for PIT the same
# way acceptance/run-acceptance.sh stands in for a real Gherkin engine: it does the
# real work and emits the real report SCHEMA (a PITest-shaped mutations.xml), so the
# suite stays hermetic (no JVM/Maven, honoring "no deps beyond git/bash/bb") while
# the mutation score is GENUINELY COMPUTED, never a hard-coded number.
#
# For each mutant it: applies the mutation to a copy of the module under test
# (core/rules.sh), runs the project's own tests against the mutated copy
# (core/rules-test.sh, RULES_SRC-injected), and records KILLED (detected='true')
# iff the tests FAIL on the mutant, SURVIVED (detected='false') iff they still pass.
# The score therefore reflects the ACTUAL strength of the candidate's tests:
# thorough tests kill the mutants (high score, gate green); a weak suite lets them
# survive (low score, gate RED) -- the same "genuine execution, not rubber-stamp"
# bar the executable-Gherkin gate meets.
#
# It is a REPORTER: it exits 0 once it has run the mutants and written the report to
# $CRAFT_MUTATION_REPORT. The pass/fail VERDICT is the runner's (run-six), derived
# structurally from the score by bin/parse-mutation-report.bb -- never from this
# command's exit code (PIT itself can exit 0 with a poor score). A real project
# swaps this for `mvn ... pitest:mutationCoverage` + a copy of PIT's own
# mutations.xml to $CRAFT_MUTATION_REPORT; see docs/mutation-report-schema.md.
set -uo pipefail

report="${CRAFT_MUTATION_REPORT:?CRAFT_MUTATION_REPORT must be set by the runner}"
src="core/rules.sh"
tst="core/rules-test.sh"

# Mutant table: id | PIT mutator class | sed expression applied to the source.
# Boundary mutations on each branch condition and return-value mutations on each
# arm -- exactly the mutation operators PIT's ConditionalsBoundary/ReturnVals apply.
mutants=(
  "M1|ConditionalsBoundaryMutator|s/n >= 90/n > 90/"
  "M2|ConditionalsBoundaryMutator|s/n >= 80/n > 80/"
  "M3|ConditionalsBoundaryMutator|s/n >= 70/n > 70/"
  "M4|BooleanTrueReturnValsMutator|s/echo A/echo F/"
  "M5|BooleanTrueReturnValsMutator|s/echo B/echo F/"
  "M6|BooleanTrueReturnValsMutator|s/echo C/echo F/"
  "M7|BooleanTrueReturnValsMutator|s/echo F/echo A/"
)

{
  echo '<?xml version="1.0" encoding="UTF-8"?>'
  echo '<mutations partial="false">'
} > "$report"

# Nothing to mutate: emit an empty (zero-mutant) report. parse-mutation-report.bb
# fails closed on it, so an absent target can never silently pass the gate.
if [[ -f "$src" && -f "$tst" ]]; then
  tmp="$(mktemp)"
  for entry in "${mutants[@]}"; do
    IFS='|' read -r id mutator expr <<< "$entry"
    sed "$expr" "$src" > "$tmp"
    if cmp -s "$src" "$tmp"; then
      status=NO_COVERAGE; detected=false          # pattern absent -> mutant not applicable
    elif RULES_SRC="$tmp" bash "$tst" >/dev/null 2>&1; then
      status=SURVIVED;    detected=false          # tests PASSED on the mutant -> not caught
    else
      status=KILLED;      detected=true           # tests FAILED on the mutant -> caught
    fi
    printf "<mutation detected='%s' status='%s' numberOfTestsRun='1'><sourceFile>rules.sh</sourceFile><mutatedClass>core.rules</mutatedClass><mutatedMethod>grade</mutatedMethod><mutator>org.pitest.mutationtest.engine.gregor.mutators.%s</mutator><description>%s</description></mutation>\n" \
      "$detected" "$status" "$mutator" "$id" >> "$report"
  done
  rm -f "$tmp"
fi

echo '</mutations>' >> "$report"
exit 0
