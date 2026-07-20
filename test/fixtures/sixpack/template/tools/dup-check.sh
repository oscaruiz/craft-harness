#!/usr/bin/env bash
# Duplication gate (a second declared `quality:` command, proving ordered
# execution). Genuine: fails if any two owned source files are byte-identical
# copies — a real duplication a stub could never detect.
set -euo pipefail
mapfile -t files < <(find core web acceptance -type f -name '*.sh' 2>/dev/null | sort)
n=${#files[@]}
for ((i=0; i<n; i++)); do
  for ((j=i+1; j<n; j++)); do
    if cmp -s "${files[$i]}" "${files[$j]}"; then
      echo "dup: FAIL — ${files[$i]} is byte-identical to ${files[$j]}"
      exit 1
    fi
  done
done
echo "dup: ok (no duplicated source files)"
