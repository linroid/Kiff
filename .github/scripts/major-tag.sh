#!/usr/bin/env bash
# Points the major tag vX at the release vX.Y.Z, so that uses: linroid/kiff@vX runs that release.
# The release workflow runs it once everything else for the release is built and uploaded.
#
#   major-tag.sh vX.Y.Z
#
# Leaves vX alone for 0.x, which has no major tag, for a prerelease, and for a release below the
# highest vX.Y.Z tag, so a late fix to an older minor never takes vX backwards. Fails when FORMAT.md
# specifies another patch format than it does at the commit vX names: apps that took vX could not
# read the patches, so a new format needs a new major version.
#
# Reads the tags of the repository it runs in, which needs every one of them and the commits they
# name, and moves vX on its origin remote, only if vX there is still what the tags here say.
set -euo pipefail

# shellcheck source=action/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/../../action/lib.sh"

# Sets format to the patch-format version that FORMAT.md at commit $1 specifies in its title, or
# fails.
read_format() {
  local text title
  if ! text=$(git show "$1:FORMAT.md" 2> /dev/null); then
    fail "There is no FORMAT.md at $1 to read the patch format from."
  fi
  title=${text%%$'\n'*}
  if [[ ! $title =~ ^#\ The\ Kiff\ patch\ format,\ version\ ([0-9]+)$ ]]; then
    fail "FORMAT.md at $1 starts '$title', not '# The Kiff patch format, version N'."
  fi
  format=${BASH_REMATCH[1]}
}

# Commit $1, followed by the release tags at it in parentheses when there are any.
describe() {
  local tags
  tags=$(git tag --points-at "$1" | { grep -E '^v[0-9]+\.[0-9]+\.[0-9]+' || true; } |
    paste -s -d ' ' -)
  printf '%s' "$1${tags:+ ($tags)}"
}

tag=${1:-}
if [[ $tag =~ ^v[0-9]+\.[0-9]+\.[0-9]+- ]]; then
  echo "$tag is a prerelease, which moves no major tag."
  exit 0
fi
if [[ ! $tag =~ ^v([0-9]+)\.[0-9]+\.[0-9]+$ ]]; then
  fail "major-tag.sh takes a release tag vX.Y.Z, not '$tag'."
fi
major=${BASH_REMATCH[1]}
if [[ $major == 0 ]]; then
  echo "$tag is a 0.x release, which has no major tag."
  exit 0
fi
floating=v$major

commit=$(git rev-parse --verify --quiet "refs/tags/$tag^{commit}") ||
  fail "There is no tag $tag here."
highest=$(git tag --list "v$major.*" | grep -E "^v$major\.[0-9]+\.[0-9]+$" |
  sort -t . -k 2,2n -k 3,3n | tail -n 1)
if [[ $highest != "$tag" ]]; then
  notice "$highest is a higher $major.x release than $tag, so $floating stays where it is."
  exit 0
fi

read_format "$commit"
new_format=$format
# The tag object of an annotated vX, which is what the remote holds, or its commit.
old_ref=$(git rev-parse --verify --quiet "refs/tags/$floating") || true
if [[ -z $old_ref ]]; then
  echo "Creating $floating at $(describe "$commit"), with patch format $new_format."
else
  old=$(git rev-parse --verify "$old_ref^{commit}")
  if [[ $old == "$commit" ]]; then
    echo "$floating is already at $(describe "$commit")."
    exit 0
  fi
  read_format "$old"
  if [[ $format != "$new_format" ]]; then
    fail "FORMAT.md at $tag specifies patch format version $new_format, but version $format at \
$old, where $floating is. A new patch format needs a new major version, so $floating stays \
where it is. Release this as v$((major + 1)).0.0 instead."
  fi
  echo "Moving $floating from $(describe "$old") to $(describe "$commit"), both with patch \
format $new_format."
fi

# The lease refuses the push if vX moved on the remote since these tags were fetched. An empty
# expected value means that vX must not exist there yet.
git push --force-with-lease="refs/tags/$floating:$old_ref" origin "$commit:refs/tags/$floating"
