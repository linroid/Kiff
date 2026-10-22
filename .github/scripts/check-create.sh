#!/usr/bin/env bash
# Checks a patch the create action made, from its outputs in PATCH, SIZE, RATIO and KIFF_VERSION:
# that the patch is where it was expected, made by the Kiff expected, that SIZE and RATIO measure
# it, and that the kiff the action installed in RUNNER_TEMP restores TARGET from BASE with it.
#
# Usage: check-create.sh BASE TARGET EXPECTED_PATCH EXPECTED_VERSION
set -euo pipefail

# shellcheck source=action/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/../../action/lib.sh"

base=$1
target=$2
expected_patch=$3
expected_version=$4

# $1, which must equal $2, or the output named $3 is wrong.
check() {
  if [[ $1 != "$2" ]]; then
    echo "::error::The $3 output is '$1', not '$2'."
    exit 1
  fi
}

check "${PATCH:-}" "$expected_patch" patch
check "${KIFF_VERSION:-}" "$expected_version" kiff-version
if [[ ! -f $PATCH ]]; then
  echo "::error::There is no patch at $PATCH."
  exit 1
fi
size=$(wc -c < "$PATCH" | tr -d '[:space:]')
check "${SIZE:-}" "$size" size
target_size=$(wc -c < "$target" | tr -d '[:space:]')
if ! awk -v ratio="${RATIO:-}" -v patch="$size" -v target="$target_size" \
  'BEGIN { off = ratio - patch / target; exit !(ratio != "" && off < 0.00005 && off > -0.00005) }'
then
  echo "::error::The ratio output is '${RATIO:-}', but the patch is $size of $target_size bytes."
  exit 1
fi

# Where install-kiff.sh leaves a release's executable, from its archive or bare, or its JVM zip.
version=$KIFF_VERSION
dir=${RUNNER_TEMP:?}/kiff-cli-$version
platform=$(runner_platform)
kiff=''
for candidate in "$dir/kiff-$version-$platform/kiff" "$dir/kiff-$version-$platform" \
  "$dir/kiff-$version/bin/kiff"; do
  if [[ -f $candidate && -x $candidate ]]; then
    kiff=$candidate
    break
  fi
done
if [[ -z $kiff ]]; then
  echo "::error::The action installed no kiff $version in $dir."
  exit 1
fi

restored=$(mktemp -d "$RUNNER_TEMP/restored.XXXXXX")/target
"$kiff" apply "$base" "$PATCH" "$restored"
if ! cmp "$target" "$restored"; then
  echo "::error::kiff $version restored something other than $target from $PATCH."
  exit 1
fi
echo "kiff $version restores $target from $base with $PATCH, of $size bytes"
