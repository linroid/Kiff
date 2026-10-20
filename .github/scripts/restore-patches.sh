#!/usr/bin/env bash
# Restores every patch in the action's manifest as an updater would: downloads the file it starts
# from, checks that file's hash, applies the patch with kiff, and holds the result to the hash the
# manifest gives the new file. Also checks the directory holds the listed patches and nothing else.
#
# Usage: restore-patches.sh KIFF MANIFEST REPOSITORY
set -euo pipefail

kiff=$1
manifest=$2
repo=$3
directory=$(dirname "$manifest")
work=$(mktemp -d "${RUNNER_TEMP:-/tmp}/restore.XXXXXX")

sha256_of() {
  local line
  if command -v sha256sum > /dev/null; then
    line=$(sha256sum "$1")
  else
    line=$(shasum -a 256 "$1")
  fi
  printf '%s' "${line%% *}"
}

# $1, which must equal $2, or the check named $3 fails.
check() {
  if [[ $1 != "$2" ]]; then
    echo "::error::$3: expected $2, got $1"
    exit 1
  fi
}

expected_files=$(jq -r --arg manifest "$(basename "$manifest")" \
  '[$manifest, (.files[].patches[].name)] | sort[]' "$manifest")
check "$(cd "$directory" && find . -type f | sed 's|^\./||' | LC_ALL=C sort)" \
  "$(LC_ALL=C sort <<< "$expected_files")" "Files in $directory"

restored=0
while IFS=$'\t' read -r file sha256 from source source_sha256 patch patch_sha256; do
  dir=$work/$from
  if [[ ! -f $dir/$source ]]; then
    gh release download "$from" --repo "$repo" --pattern "$source" --dir "$dir"
  fi
  check "$(sha256_of "$dir/$source")" "$source_sha256" "sha256 of $source in $from"
  check "$(sha256_of "$directory/$patch")" "$patch_sha256" "sha256 of $patch"
  "$kiff" apply "$dir/$source" "$directory/$patch" "$work/restored"
  check "$(sha256_of "$work/restored")" "$sha256" "sha256 of $file restored from $from"
  rm "$work/restored"
  restored=$((restored + 1))
done < <(jq -r '
  .files[] as $file | $file.patches[]
  | [$file.name, $file.sha256, .from, .source.name, .source.sha256, .name, .sha256] | @tsv
' "$manifest")

echo "Restored $restored patches, each to the file the manifest describes"
