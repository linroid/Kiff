#!/usr/bin/env bash
# Checks the action's patches output, in PATCHES, against the lines on stdin: "FILE FROM" for each
# patch, in the order the action lists them.
set -euo pipefail

expected=$(cat)
actual=$(jq -r '.[] | "\(.file) \(.from)"' <<< "${PATCHES:?}")
if [[ $actual != "$expected" ]]; then
  echo "::error::The action made other patches than expected."
  diff <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") || true
  exit 1
fi
count=$(jq length <<< "$PATCHES")
if [[ ${COUNT:-$count} != "$count" ]]; then
  echo "::error::The count output is ${COUNT:-}, but the patches output holds $count."
  exit 1
fi
echo "$count patches, as expected"
