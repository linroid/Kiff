#!/usr/bin/env bash
# Checks the create action's inputs before anything is downloaded, and settles the Kiff release that
# creates the patch: the version input, or else the latest release, found as install.sh finds it.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=action/lib.sh
. "$here/../action/lib.sh"

# A release's version, as kiff --use takes it: three numbers and an optional suffix.
released='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$'

# Fails unless the path $2, which the input $1 gives, is one line, as a step output must be.
one_line() {
  if [[ $2 == *$'\n'* || $2 == *$'\r'* ]]; then
    fail "$1 holds a line break, so it is not one path."
  fi
}

# Fails unless the input $1, whose value is $2, is the path of a regular file, which $3 describes.
require_file() {
  local name=$1 path=$2 what=$3
  if [[ -z $path ]]; then
    fail "$name is empty. Give the path of $what."
  fi
  one_line "$name" "$path"
  if [[ -d $path ]]; then
    fail "$name is $path, which is a directory. Give the path of $what."
  elif [[ ! -e $path ]]; then
    fail "$name is $path, but there is no file at that path in $PWD."
  elif [[ ! -f $path ]]; then
    fail "$name is $path, which is not a regular file."
  fi
}

# The tag of the latest published Kiff release, or nothing when it cannot be found. Read from where
# the release page redirects, as install.sh does, since unlike the API it is not limited to 60
# anonymous requests an hour; the API, which takes the token, is asked only when that fails.
latest_tag() {
  local url tag='' auth=()
  if url=$(curl -fsSLI --retry 3 -o /dev/null -w '%{url_effective}' \
    https://github.com/linroid/kiff/releases/latest); then
    case $url in
      */releases/tag/*) tag=${url##*/releases/tag/} ;;
    esac
  fi
  if [[ -z $tag ]]; then
    # The workflow's token is for its own server, so it goes to github.com only from github.com.
    if [[ -n ${GITHUB_TOKEN:-} && ${GITHUB_SERVER_URL:-https://github.com} == https://github.com ]]
    then
      auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
    fi
    tag=$(curl -fsSL --retry 3 -H 'Accept: application/vnd.github+json' ${auth[@]+"${auth[@]}"} \
      https://api.github.com/repos/linroid/kiff/releases/latest | jq -r '.tag_name // empty') \
      || tag=''
  fi
  printf '%s' "$tag"
}

version=$(trim "${INPUT_VERSION:-}")
version=${version#[vV]}
if [[ -z $version ]]; then
  :
elif [[ $(lowercase "$version") == *-snapshot ]]; then
  fail "version is $version, a snapshot, and no snapshot has a release to download. Leave version \
empty to use the latest release."
elif [[ ! $version =~ $released ]]; then
  fail "version is '$(trim "${INPUT_VERSION:-}")', not a released Kiff version such as 1.0.0."
fi

base=$(trim "${INPUT_BASE:-}")
target=$(trim "${INPUT_TARGET:-}")
require_file base "$base" 'the file the patch starts from'
require_file target "$target" 'the file the patch rebuilds'

output=$(trim "${INPUT_OUTPUT:-}")
if [[ -z $output ]]; then
  output=$target.kiff
fi
one_line output "$output"
if [[ -d $output || $output == */ ]]; then
  fail "output is $output, which is a directory. Give the path of the patch file."
elif [[ $output -ef $base ]]; then
  fail "output is $output, which is base: the patch would overwrite the file it starts from."
elif [[ $output -ef $target ]]; then
  fail "output is $output, which is target: the patch would overwrite the file it rebuilds."
fi

if [[ -z $version ]]; then
  tag=$(latest_tag)
  version=${tag#[vV]}
  if [[ -z $tag ]]; then
    fail "Could not find the latest Kiff release at \
https://github.com/linroid/kiff/releases/latest. Set version to the release to use."
  elif [[ ! $version =~ $released ]]; then
    fail "The latest Kiff release is tagged $tag, which names no version to install. Set version \
to the release to use."
  fi
  echo "The latest Kiff release is $version"
fi

set_output base "$base"
set_output target "$target"
set_output output "$output"
set_output version "$version"
