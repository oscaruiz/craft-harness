#!/usr/bin/env bash
#
# craft-harness toy CRAP wrapper (milestone 3, behavior B3).
#
# A STUB standing in for the real crap4java/CRAP-TS wrappers (design §3) so
# the two-pack-lite cleaner's quality gate is exercisable on the toy task's
# language-free code, before any real language toolchain exists (m5–6).
#
# It implements the normalized wrapper output contract the cleaner prompt
# depends on. Every line is self-identified with the tool name so a downstream
# reader (bin/inspect-run, B4) can attribute a threshold to CRAP specifically —
# the DRY wrapper prints its own `dry: threshold=0` and the two must not be
# confused:
#
#     crap: score=<n>
#     crap: threshold=6
#     crap: offenders=<none | file:count ...>
#     crap: result=<pass | fail>
#
# Two invariants the milestone leans on:
#   1. THE WRAPPER IS THE SINGLE SOURCE OF TRUTH FOR THE THRESHOLD. It always
#      prints `threshold: 6`; no flag or env changes it. The number never
#      comes from the prompt (design §3, decisions triage round 2). bin/inspect-run
#      reads the executed threshold from THIS log, not from the prompt.
#   2. A score above the threshold is a non-zero exit — a gate the cleaner must
#      fix, not advice it may ignore.
#
# Score: `CRAP_TOY_SCORE` overrides it (to simulate a failing gate in tests);
# otherwise a deterministic, language-free complexity proxy — the number of
# control-flow constructs (if/elif/for/while/case, && , ||) across the files.
#
# Usage: crap.sh [file...]   (no files → every *.sh in the current directory)
set -euo pipefail

readonly THRESHOLD=6

files=("$@")
if [[ ${#files[@]} -eq 0 ]]; then
  shopt -s nullglob
  files=(*.sh)
fi

count_complexity() { # <file>: control-flow constructs, 0 if unreadable
  [[ -f "$1" ]] || { echo 0; return; }
  # grep -c prints the count AND exits 1 when it is zero; keep exactly one
  # integer on stdout and never let the non-zero exit trip set -e.
  local n
  n="$(grep -Ec '\b(if|elif|for|while|case)\b|&&|\|\|' "$1" 2>/dev/null || true)"
  echo "${n:-0}"
}

score=0
offenders=()
if [[ -n "${CRAP_TOY_SCORE:-}" ]]; then
  # Forced score: attribute it to the named files so the report still points
  # somewhere actionable.
  score="$CRAP_TOY_SCORE"
  [[ "$score" =~ ^[0-9]+$ ]] || { echo "crap.sh: CRAP_TOY_SCORE must be a non-negative integer" >&2; exit 2; }
  if (( score > THRESHOLD )); then
    for f in "${files[@]}"; do offenders+=("$f:$score"); done
  fi
else
  for f in "${files[@]}"; do
    c="$(count_complexity "$f")"
    score=$(( score + c ))
    (( c > 0 )) && offenders+=("$f:$c")
  done
fi

result=pass
rc=0
if (( score > THRESHOLD )); then
  result=fail
  rc=1
fi

offenders_line="crap: offenders=none"
(( ${#offenders[@]} > 0 )) && offenders_line="crap: offenders=${offenders[*]}"
report="$(printf 'crap: score=%s\ncrap: threshold=%s\n%s\ncrap: result=%s' \
  "$score" "$THRESHOLD" "$offenders_line" "$result")"

printf '%s\n' "$report"
# Durable evidence: a real agent CLI (e.g. Claude Code) collapses tool output in
# its TUI, so the pane capture cannot be trusted to hold this line. When run-pack
# points CRAFT_WRAPPER_LOG at the session's log dir, append the report there so
# bin/inspect-run reads the executed threshold from the wrapper itself, not from
# the agent's collapsed transcript.
if [[ -n "${CRAFT_WRAPPER_LOG:-}" ]]; then
  printf '%s\n' "$report" >> "$CRAFT_WRAPPER_LOG" 2>/dev/null || true
fi
exit "$rc"
