#!/usr/bin/env bash
# Tests when major-tag.sh moves a major tag such as v1, against a made-up repository whose origin
# is a bare repository beside it, so no network or runner is needed.
set -euo pipefail

script=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/major-tag.sh
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT

failures=0

# Checks that $1, a description, came out as $3 when $2 was expected.
expect() {
  if [[ $3 != "$2" ]]; then
    echo "::error::$1 gave '$3', not '$2'."
    failures=$((failures + 1))
  fi
}

git init -q --bare "$dir/origin.git"
git init -q "$dir/work"
cd "$dir/work"
git config user.name Test
git config user.email test@example.com
git config commit.gpgsign false
git config tag.gpgsign false
git remote add origin "$dir/origin.git"

# Commits a FORMAT.md that specifies patch format $2 and tags it $1, annotated when $3 is given.
release() {
  printf '# The Kiff patch format, version %s\n\nA patch turns one file into another.\n' "$2" \
    > FORMAT.md
  printf '%s\n' "$1" > release.txt
  git add FORMAT.md release.txt
  git commit -q -m "Release $1"
  if [[ -n ${3:-} ]]; then
    git tag -a -m "$1" "$1"
  else
    git tag "$1"
  fi
  git push -q origin "refs/tags/$1"
}

# Runs major-tag.sh for $1 from a fresh view of origin's tags, as a new checkout has, and returns
# its status. Its output goes to $dir/out.
move() {
  git fetch -q --force --prune --prune-tags origin
  "$script" "$1" > "$dir/out" 2>&1
}

# The tag whose commit origin's v$1 names, or nothing when origin has no v$1.
where() {
  local commit
  commit=$(git ls-remote origin "refs/tags/v$1" | cut -f 1)
  if [[ -n $commit ]]; then
    git fetch -q --force --prune --prune-tags origin
    git tag --points-at "$commit" | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -t . -k 2,2n |
      tail -n 1
  fi
}

# The status of major-tag.sh for $1, as moved or failed.
outcome() {
  if move "$1"; then
    echo moved
  else
    echo failed
  fi
}

release v0.2.0 1
expect 'A 0.x release' moved "$(outcome v0.2.0)"
expect 'The major tag of a 0.x release' '' "$(where 0)"

release v1.0.0-rc.1 1
expect 'A prerelease' moved "$(outcome v1.0.0-rc.1)"
expect 'The major tag after a prerelease' '' "$(where 1)"

release v1.0.0 1
expect 'The first 1.x release' moved "$(outcome v1.0.0)"
expect 'v1 after the first release' v1.0.0 "$(where 1)"
expect 'The first release again' moved "$(outcome v1.0.0)"
expect 'v1 after the first release again' v1.0.0 "$(where 1)"
first=$(git rev-parse HEAD)

release v1.1.0 1 annotated
expect 'An annotated release' moved "$(outcome v1.1.0)"
expect 'v1 after an annotated release' v1.1.0 "$(where 1)"
expect 'v1 names the commit, not the tag object' "$(git rev-parse 'v1.1.0^{commit}')" \
  "$(git ls-remote origin refs/tags/v1 | cut -f 1)"

# A fix to 1.0, released after 1.1.0 from a commit of its own.
git checkout -q "$first"
release v1.0.1 1
expect 'A fix to an older minor' moved "$(outcome v1.0.1)"
expect 'v1 after a fix to an older minor' v1.1.0 "$(where 1)"
git checkout -q -

# 1.10.0 is higher than 1.9.0, which a sort of text would put last.
release v1.9.0 1
release v1.10.0 1
expect 'The release before a tenth minor' moved "$(outcome v1.10.0)"
expect 'v1 at a tenth minor' v1.10.0 "$(where 1)"
expect 'A ninth minor after a tenth' moved "$(outcome v1.9.0)"
expect 'v1 after a ninth minor after a tenth' v1.10.0 "$(where 1)"

release v1.11.0 2
expect 'A release with a new patch format' failed "$(outcome v1.11.0)"
expect 'v1 after a release with a new patch format' v1.10.0 "$(where 1)"
if ! grep -q 'A new patch format needs a new major version' "$dir/out"; then
  echo "::error::A new patch format should say it needs a new major version, not this:"
  cat "$dir/out"
  failures=$((failures + 1))
fi

release v2.0.0 2
expect 'The first release of a new major version' moved "$(outcome v2.0.0)"
expect 'v2 after its first release' v2.0.0 "$(where 2)"
expect 'v1 after the first 2.x release' v1.10.0 "$(where 1)"

# Someone moved v1 on origin after this checkout fetched its tags, so the push is refused.
release v1.12.0 1
git fetch -q --force --prune --prune-tags origin
git push -q --force origin "$(git rev-parse 'v1.9.0^{commit}'):refs/tags/v1"
expect 'A v1 that moved since the tags were fetched' failed \
  "$(if "$script" v1.12.0 > "$dir/out" 2>&1; then echo moved; else echo failed; fi)"
expect 'v1 after a refused push' v1.9.0 "$(where 1)"
expect 'The same release from a fresh fetch' moved "$(outcome v1.12.0)"
expect 'v1 after the same release from a fresh fetch' v1.12.0 "$(where 1)"

# FORMAT.md at v1 is gone or no longer starts with its title.
git rm -q FORMAT.md
git commit -q -m 'Drop FORMAT.md'
git tag v1.13.0
git push -q origin refs/tags/v1.13.0
expect 'A release without FORMAT.md' failed "$(outcome v1.13.0)"
expect 'v1 after a release without FORMAT.md' v1.12.0 "$(where 1)"

expect 'A tag that is no release' failed "$(outcome nightly)"
expect 'A release that is not tagged' failed "$(outcome v1.99.0)"

if ((failures > 0)); then
  exit 1
fi
echo "Every case passed"
