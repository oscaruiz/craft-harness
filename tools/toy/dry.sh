#!/usr/bin/env bash
#
# craft-harness toy DRY wrapper (milestone 3, behavior B3).
#
# A STUB standing in for the real dry4java/jscpd wrappers (design §3), sharing
# the CRAP wrapper's normalized, self-identified output contract so the cleaner
# treats both gates the same way and a downstream reader never confuses the two
# thresholds:
#
#     dry: score=<n>          # meaningful duplicated blocks
#     dry: threshold=0        # no meaningful duplication tolerated in the toy pack
#     dry: offenders=<none | file:count ...>
#     dry: result=<pass | fail>
#
# A score above the threshold is a non-zero exit — a gate the cleaner must fix.
#
# Score: `DRY_TOY_DUPES` overrides it (to simulate duplication in tests);
# otherwise a deterministic, language-free proxy — the number of non-blank,
# non-comment lines that appear more than once across the files (an exact
# duplicate-line count).
#
# Usage: dry.sh [file...]   (no files → every *.sh in the current directory)
set -euo pipefail

readonly THRESHOLD=0

files=("$@")
if [[ ${#files[@]} -eq 0 ]]; then
  shopt -s nullglob
  files=(*.sh)
fi

count_duplicate_lines() { # duplicated non-blank, non-comment lines across files
  local f present=()
  for f in "${files[@]}"; do [[ -f "$f" ]] && present+=("$f"); done
  (( ${#present[@]} > 0 )) || { echo 0; return; }
  # Strip blank and comment lines, collapse leading/trailing space, then count
  # how many normalized lines occur more than once.
  grep -hEv '^\s*(#|$)' "${present[@]}" 2>/dev/null \
    | sed -E 's/^\s+//; s/\s+$//' \
    | sort | uniq -d | wc -l | tr -d ' '
}

score=0
offenders=()
if [[ -n "${DRY_TOY_DUPES:-}" ]]; then
  score="$DRY_TOY_DUPES"
  [[ "$score" =~ ^[0-9]+$ ]] || { echo "dry.sh: DRY_TOY_DUPES must be a non-negative integer" >&2; exit 2; }
  if (( score > THRESHOLD )); then
    for f in "${files[@]}"; do offenders+=("$f:$score"); done
  fi
else
  score="$(count_duplicate_lines)"
  (( score > THRESHOLD )) && for f in "${files[@]}"; do [[ -f "$f" ]] && offenders+=("$f"); done
fi

result=pass
rc=0
if (( score > THRESHOLD )); then
  result=fail
  rc=1
fi

echo "dry: score=$score"
echo "dry: threshold=$THRESHOLD"
if (( ${#offenders[@]} > 0 )); then
  echo "dry: offenders=${offenders[*]}"
else
  echo "dry: offenders=none"
fi
echo "dry: result=$result"
exit "$rc"
