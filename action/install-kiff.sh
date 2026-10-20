#!/usr/bin/env bash
# Puts the kiff CLI of the action's own version on the runner, so the scripts and the CLI always
# come from one commit. A released version downloads the zip its release attaches and checks it
# against the checksum beside it. A snapshot, or a version with no such release, is built from the
# action's own source.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=action/lib.sh
. "$here/lib.sh"

root=${GITHUB_ACTION_PATH:?}
version=$(sed -n -E 's/^version[[:space:]]*=[[:space:]]*//p' "$root/gradle.properties" \
  | tail -n 1 | tr -d '[:space:]')
if [[ -z $version ]]; then
  fail "$root/gradle.properties has no version."
fi

kiff=''
if [[ $version != *-SNAPSHOT ]]; then
  dir=${RUNNER_TEMP:?}/kiff-cli-$version
  zip=kiff-$version.zip
  url=https://github.com/linroid/Kiff/releases/download/v$version/$zip
  if [[ -x $dir/kiff-$version/bin/kiff ]]; then
    # An earlier run of the action in this job already installed it.
    kiff=$dir/kiff-$version/bin/kiff
  else
    mkdir -p "$dir"
    if curl -fsSL --retry 3 -o "$dir/$zip" "$url" \
      && curl -fsSL --retry 3 -o "$dir/$zip.sha256" "$url.sha256"; then
      read -r expected _ < "$dir/$zip.sha256" || true
      actual=$(sha256_of "$dir/$zip")
      if [[ $actual != "$expected" ]]; then
        fail "$zip from $url has sha256 $actual, but its release's $zip.sha256 says \
${expected:-nothing}."
      fi
      unzip -q -o "$dir/$zip" -d "$dir"
      kiff=$dir/kiff-$version/bin/kiff
      echo "Installed kiff $version from $url"
    else
      notice "Kiff v$version has no CLI to download, so the action builds it from source."
    fi
  fi
fi

if [[ -z $kiff ]]; then
  echo "Building kiff $version from $root"
  "$root/gradlew" -p "$root" --console=plain :cli:installDist
  kiff=$root/cli/build/install/kiff/bin/kiff
fi

if [[ ! -x $kiff ]]; then
  fail "No kiff at $kiff."
fi
set_output kiff "$kiff"
