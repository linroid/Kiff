#!/usr/bin/env bash
# Puts the kiff CLI that makes the patches on the runner, in two steps around the one that sets up
# Java, which only the second needs.
#
#   install-kiff.sh native  decides which Kiff to use and, when its release has an executable for
#                           this runner, installs that: no Java is needed then.
#   install-kiff.sh jvm     otherwise installs that release's JVM zip, or builds the action's own
#                           source when there is no release to install, and both need Java.
#
# With KIFF_VERSION set, the Kiff is exactly that release: its executable or its zip, checked
# against the checksum beside it, or a failure when neither is there. Otherwise it is the action's
# own version, so the scripts and the CLI come from one commit: the release tagged at the commit the
# action was loaded from, or a build of the action's own source when no release is, as with a
# branch or uses: ./, or when its files cannot be downloaded.
#
# KIFF_DOWNLOAD_URL replaces https://github.com/linroid/kiff/releases/download, as it does for
# kiff --use, with a mirror that serves vVERSION/ the files a release attaches.
#
# The create action runs it too, always with KIFF_VERSION set, and KIFF_VERSION_INPUT naming the
# input that set it, which is kiff-version here.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=action/lib.sh
. "$here/lib.sh"

root=${GITHUB_ACTION_PATH:?}
releases=${KIFF_DOWNLOAD_URL:-https://github.com/linroid/kiff/releases/download}
releases=${releases%/}

# Downloads $1 to $2 and its checksum beside it, and fails unless they agree. Returns 1, leaving
# missing set to what could not be downloaded, when either is not there.
download() {
  local url=$1 file=$2 expected actual
  if ! curl -fsSL --retry 3 -o "$file.sha256" "$url.sha256"; then
    missing=$url.sha256
    return 1
  fi
  if ! curl -fsSL --retry 3 -o "$file" "$url"; then
    missing=$url
    return 1
  fi
  read -r expected _ < "$file.sha256" || true
  actual=$(sha256_of "$file")
  if [[ $actual != "$expected" ]]; then
    fail "$url has sha256 $actual, but $url.sha256 says ${expected:-nothing}."
  fi
}

# Unpacks the archive $1, downloaded from $3, into the directory $4, refusing it unless everything
# in it is in its directory $2.
unpack() {
  local archive=$1 root=$2 url=$3 dir=$4 entries outside
  if [[ $archive == *.zip ]]; then
    entries=$(unzip -Z1 "$archive") || fail "$url is not a zip unzip can read."
  else
    entries=$(tar -tzf "$archive") || fail "$url is not an archive tar can read."
  fi
  outside=$(entry_outside "$root" <<< "$entries")
  if [[ -n $outside ]]; then
    fail "$url holds $outside, which is outside its $root directory."
  fi
  if [[ $archive == *.zip ]]; then
    unzip -q -o "$archive" -d "$dir"
  else
    tar -xzf "$archive" -C "$dir"
  fi
}

# Installs the executable of release $1 for this runner and sets kiff to it, or sets kiff to
# nothing when the release has none. A release attaches it in an archive, and 0.2.0 attached it
# bare.
install_native() {
  local version=$1 platform name exe='' archive dir url installed
  platform=$(runner_platform)
  if [[ -z $platform ]]; then
    echo "Kiff releases have no executable for ${RUNNER_OS:-}/${RUNNER_ARCH:-}."
    kiff=''
    return 0
  fi
  name=kiff-$version-$platform
  archive=$name.tar.gz
  if [[ $platform == windows-* ]]; then
    exe=.exe
    archive=$name.zip
  fi
  dir=${RUNNER_TEMP:?}/kiff-cli-$version
  # An earlier run of the action in this job already installed it, from either.
  for installed in "$dir/$name/kiff$exe" "$dir/$name$exe"; do
    if [[ -f $installed && -x $installed ]]; then
      kiff=$installed
      return 0
    fi
  done
  mkdir -p "$dir"
  url=$releases/v$version/$archive
  if download "$url" "$dir/$archive"; then
    unpack "$dir/$archive" "$name" "$url" "$dir"
    kiff=$dir/$name/kiff$exe
    if [[ ! -f $kiff || -L $kiff ]]; then
      fail "$url holds no $name/kiff$exe."
    fi
  else
    url=$releases/v$version/$name$exe
    kiff=$dir/$name$exe
    if ! download "$url" "$kiff"; then
      echo "Kiff $version has no executable for $platform, in an archive or alone, at \
$releases/v$version."
      kiff=''
      return 0
    fi
  fi
  chmod +x "$kiff"
  echo "Installed kiff $version from $url"
}

# Installs the JVM zip of release $1 and sets kiff to its launcher, or sets kiff to nothing and
# missing to the URL that could not be downloaded.
install_zip() {
  local version=$1 dir zip
  dir=${RUNNER_TEMP:?}/kiff-cli-$version
  zip=kiff-$version.zip
  kiff=$dir/kiff-$version/bin/kiff
  if [[ -x $kiff ]]; then
    return 0
  fi
  mkdir -p "$dir"
  if ! download "$releases/v$version/$zip" "$dir/$zip"; then
    kiff=''
    return 0
  fi
  unzip -q -o "$dir/$zip" -d "$dir"
  if [[ ! -x $kiff ]]; then
    fail "$releases/v$version/$zip holds no kiff-$version/bin/kiff."
  fi
  echo "Installed kiff $version from $releases/v$version/$zip"
}

# The release of Kiff to use: KIFF_VERSION, or the one tagged where the action was loaded from, or
# nothing when there is none and the action's own source is built.
resolve_version() {
  local ref tags
  if [[ -n ${KIFF_VERSION:-} ]]; then
    printf '%s' "$KIFF_VERSION"
    return 0
  fi
  # Nothing in the source says which release it is: only a tag does.
  ref=$(action_ref "${KIFF_ACTION_REF:-}" "$root")
  if [[ -z $ref ]]; then
    echo "The action was not loaded from a ref of linroid/kiff." >&2
  elif ! tags=$(GIT_TERMINAL_PROMPT=0 git ls-remote --tags https://github.com/linroid/kiff); then
    notice "Could not list Kiff's tags to find the release at $ref, so the action builds Kiff \
from source." >&2
  else
    release_at "$ref" <<< "$tags"
  fi
}

kiff=''
missing=''
case ${1:-} in
  native)
    version=$(resolve_version)
    if [[ -n $version ]]; then
      install_native "$version"
    elif [[ -z ${KIFF_VERSION:-} ]]; then
      echo "No Kiff release is tagged where the action was loaded from."
    fi
    set_output version "$version"
    ;;
  jvm)
    # The native step's answer, so the tags are listed once.
    version=${KIFF_RESOLVED:-}
    kiff=${KIFF_NATIVE:-}
    if [[ -z $kiff && -n $version ]]; then
      install_zip "$version"
      if [[ -z $kiff && -n ${KIFF_VERSION:-} ]]; then
        fail "Could not download kiff $version from $missing. ${KIFF_VERSION_INPUT:-kiff-version} \
takes a published Kiff release, and a draft has nothing to download until it is published."
      elif [[ -z $kiff ]]; then
        notice "Kiff v$version has no CLI at $missing, so the action builds it from source."
      fi
    fi
    if [[ -z $kiff ]]; then
      # The source of a release's tag is that release. Anything else is a snapshot.
      version=${version:-0.0.0-SNAPSHOT}
      echo "Building kiff $version from $root"
      "$root/gradlew" -p "$root" --console=plain "-Pversion=$version" :cli:installDist
      kiff=$root/cli/build/install/kiff/bin/kiff
    fi
    if [[ ! -x $kiff ]]; then
      fail "No kiff at $kiff."
    fi
    set_output version "$version"
    ;;
  *)
    fail "install-kiff.sh takes native or jvm, not '${1:-}'."
    ;;
esac
set_output kiff "$kiff"
