#!/usr/bin/env bash
# Puts the kiff CLI that makes the patches on the runner.
#
# With KIFF_VERSION set, that is exactly that release of Kiff: the zip its GitHub release
# attaches, checked against the checksum beside it, or a failure when either is missing. Otherwise
# it is the action's own version, so the scripts and the CLI come from one commit: that release's
# zip when there is one, or a build of the action's own source for a snapshot or a version whose
# zip cannot be downloaded.
#
# KIFF_DOWNLOAD_URL replaces https://github.com/linroid/Kiff/releases/download, as it does for
# kiff --use, with a mirror that serves vVERSION/kiff-VERSION.zip and its .sha256.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=action/lib.sh
. "$here/lib.sh"

root=${GITHUB_ACTION_PATH:?}
releases=${KIFF_DOWNLOAD_URL:-https://github.com/linroid/Kiff/releases/download}
releases=${releases%/}

# Installs release $1 and sets kiff to its launcher, or sets kiff to nothing and missing to the URL
# that could not be downloaded. A zip that is not the one its checksum names fails the run.
install_release() {
  local version=$1 dir zip url expected actual
  dir=${RUNNER_TEMP:?}/kiff-cli-$version
  zip=kiff-$version.zip
  url=$releases/v$version/$zip
  kiff=$dir/kiff-$version/bin/kiff
  if [[ -x $kiff ]]; then
    # An earlier run of the action in this job already installed it.
    return 0
  fi
  mkdir -p "$dir"
  if ! curl -fsSL --retry 3 -o "$dir/$zip.sha256" "$url.sha256"; then
    kiff=''
    missing=$url.sha256
    return 0
  fi
  if ! curl -fsSL --retry 3 -o "$dir/$zip" "$url"; then
    kiff=''
    missing=$url
    return 0
  fi
  read -r expected _ < "$dir/$zip.sha256" || true
  actual=$(sha256_of "$dir/$zip")
  if [[ $actual != "$expected" ]]; then
    fail "$url has sha256 $actual, but $url.sha256 says ${expected:-nothing}."
  fi
  unzip -q -o "$dir/$zip" -d "$dir"
  if [[ ! -x $kiff ]]; then
    fail "$url holds no kiff-$version/bin/kiff."
  fi
  echo "Installed kiff $version from $url"
}

kiff=''
missing=''
if [[ -n ${KIFF_VERSION:-} ]]; then
  version=$KIFF_VERSION
  install_release "$version"
  if [[ -z $kiff ]]; then
    fail "Could not download kiff $version from $missing. kiff-version takes a published Kiff \
release, and a draft has nothing to download until it is published."
  fi
else
  version=$(sed -n -E 's/^version[[:space:]]*=[[:space:]]*//p' "$root/gradle.properties" \
    | tail -n 1 | tr -d '[:space:]')
  if [[ -z $version ]]; then
    fail "$root/gradle.properties has no version."
  fi
  if [[ $version != *-SNAPSHOT ]]; then
    install_release "$version"
    if [[ -z $kiff ]]; then
      notice "Kiff v$version has no CLI at $missing, so the action builds it from source."
    fi
  fi
  if [[ -z $kiff ]]; then
    echo "Building kiff $version from $root"
    "$root/gradlew" -p "$root" --console=plain :cli:installDist
    kiff=$root/cli/build/install/kiff/bin/kiff
  fi
fi

if [[ ! -x $kiff ]]; then
  fail "No kiff at $kiff."
fi
set_output kiff "$kiff"
set_output version "$version"
