#!/usr/bin/env bash
# Creates the patch with the kiff that install-kiff.sh put on the runner, and reports it in the
# step's outputs and the job summary.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=action/lib.sh
. "$here/../action/lib.sh"

kiff=${KIFF:?}
kiff_used=${KIFF_USED:?}
base=${BASE:?}
target=${TARGET:?}
output=${OUTPUT:?}

# $1 as an argument that a command reads as a path, even when it starts with -.
path_arg() {
  case $1 in
    -*) printf './%s' "$1" ;;
    *) printf '%s' "$1" ;;
  esac
}

# The size of the file $1, in bytes.
size_of() {
  wc -c < "$1" | tr -d '[:space:]'
}

# $1 bytes as kiff writes a size, such as 4.6 MiB, cut rather than rounded to one place.
readable() {
  awk -v bytes="$1" 'BEGIN {
    split("B KiB MiB GiB", units, " ")
    unit = 1
    while (bytes >= 1024 && unit < 4) {
      bytes /= 1024
      unit++
    }
    if (unit == 1) printf "%d %s", bytes, units[unit]
    else printf "%.1f %s", int(bytes * 10) / 10, units[unit]
  }'
}

# $1 made safe for a cell of a Markdown table.
cell() {
  printf '%s' "${1//|/\\|}"
}

parent=$(dirname "$(path_arg "$output")")
if ! mkdir -p "$parent"; then
  fail "Could not create $parent, the directory output is in."
fi

echo "Creating $output with kiff $kiff_used"
if ! "$kiff" create "$(path_arg "$base")" "$(path_arg "$target")" "$(path_arg "$output")"; then
  fail "kiff $kiff_used could not create a patch from $base to $target."
fi

size=$(size_of "$output")
base_size=$(size_of "$base")
target_size=$(size_of "$target")
# As the release action measures a patch: rounded to four places, and 0 for an empty target.
ratio=$(jq -n --argjson patch "$size" --argjson target "$target_size" \
  'if $target > 0 then ($patch / $target * 10000 | round) / 10000 else 0 end')

set_output patch "$output"
set_output size "$size"
set_output ratio "$ratio"
set_output kiff-version "$kiff_used"

if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then
  share=''
  if ((target_size > 0)); then
    share=" ($(awk -v patch="$size" -v target="$target_size" \
      'BEGIN { printf "%.2f%%", int(patch * 10000 / target) / 100 }') of target)"
  fi
  {
    echo "### Kiff patch"
    echo
    echo "Created with kiff $kiff_used."
    echo
    echo '| File | Path | Size |'
    echo '| --- | --- | ---: |'
    echo "| Base | \`$(cell "$base")\` | $(readable "$base_size") |"
    echo "| Target | \`$(cell "$target")\` | $(readable "$target_size") |"
    echo "| Patch | \`$(cell "$output")\` | $(readable "$size")$share |"
  } >> "$GITHUB_STEP_SUMMARY"
fi
