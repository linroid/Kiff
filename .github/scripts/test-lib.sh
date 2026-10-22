#!/usr/bin/env bash
# Tests how action/lib.sh finds the release the action was loaded from, against made-up paths and
# git ls-remote --tags output, the platform it runs on, and which archive entries it refuses, so no
# network or runner is needed.
set -euo pipefail

# shellcheck source=action/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/../../action/lib.sh"

failures=0

# Checks that $1, a description, came out as $3 when $2 was expected.
expect() {
  if [[ $3 != "$2" ]]; then
    echo "::error::$1 gave '$3', not '$2'."
    failures=$((failures + 1))
  fi
}

c1=1111111111111111111111111111111111111111
c2=2222222222222222222222222222222222222222
c3=3333333333333333333333333333333333333333
# The tag objects of annotated tags, which are not commits.
t1=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
t2=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb

# v0.1.0 is lightweight. v0.2.0-rc.1 and v0.2.0 are annotated, at one commit, which the moving v1
# also names. Neither a snapshot nor nightly is a release.
tags="$c1	refs/tags/v0.1.0
$t2	refs/tags/v0.2.0
$c2	refs/tags/v0.2.0^{}
$t1	refs/tags/v0.2.0-rc.1
$c2	refs/tags/v0.2.0-rc.1^{}
$c3	refs/tags/v0.3.0-SNAPSHOT
$c3	refs/tags/nightly
$c2	refs/tags/v1"

release() {
  release_at "$1" <<< "$tags"
}

expect 'A lightweight release tag' 0.1.0 "$(release v0.1.0)"
expect 'An annotated release tag' 0.2.0 "$(release v0.2.0)"
expect 'A prerelease tag at a release' 0.2.0-rc.1 "$(release v0.2.0-rc.1)"
expect 'A moving tag at a release and a prerelease' 0.2.0 "$(release v1)"
expect 'A SHA at an annotated release' 0.2.0 "$(release $c2)"
expect 'A SHA in capitals' 0.2.0 "$(release "$(tr '[:lower:]' '[:upper:]' <<< $c2)")"
expect 'A SHA at a lightweight release' 0.1.0 "$(release $c1)"
expect 'A SHA at a snapshot and a tag that is no version' '' "$(release $c3)"
expect 'A tag that is no version' '' "$(release nightly)"
expect 'A tag that names a snapshot' '' "$(release v0.3.0-SNAPSHOT)"
expect 'A branch' '' "$(release main)"
expect 'A tag object, which is no commit' '' "$(release $t2)"
expect 'A SHA no tag names' '' "$(release 4444444444444444444444444444444444444444)"
expect 'A short SHA' '' "$(release 2222222)"
expect 'No ref' '' "$(release '')"
expect 'No tags at all' '' "$(release_at v0.1.0 < /dev/null)"

actions=/home/runner/work/_actions
expect 'A tag' v0.2.0 "$(action_ref v0.2.0 $actions/linroid/kiff/v0.2.0)"
# A workflow written before the repository was renamed from linroid/Kiff still loads it so.
expect 'The old spelling of the repository' v0.2.0 \
  "$(action_ref v0.2.0 $actions/linroid/Kiff/v0.2.0/)"
expect 'A branch with a slash' feat/x "$(action_ref feat/x $actions/linroid/kiff/feat/x)"
expect 'A Windows path' v0.2.0 "$(action_ref v0.2.0 'D:\a\_actions\linroid\Kiff\v0.2.0')"
expect 'A container path' v0.2.0 "$(action_ref v0.2.0 /__w/_actions/linroid/kiff/v0.2.0)"
expect "The ref of a composite action that runs this one" '' \
  "$(action_ref v1 $actions/linroid/kiff/v0.2.0)"
expect 'A fork' '' "$(action_ref v0.2.0 $actions/someone/Kiff/v0.2.0)"
expect 'uses: ./' '' "$(action_ref '' /home/runner/work/Kiff/Kiff)"
expect 'A ref with a checkout for a path' '' "$(action_ref v0.2.0 /home/runner/work/Kiff/Kiff)"
expect 'A ref that is a pattern' '' "$(action_ref '*' $actions/linroid/kiff/v0.2.0)"

platform() {
  RUNNER_OS=$1 RUNNER_ARCH=$2 runner_platform
}

expect 'Linux on x64' linux-x64 "$(platform Linux X64)"
expect 'Linux on arm64' linux-arm64 "$(platform Linux ARM64)"
expect 'macOS on Apple silicon' macos-arm64 "$(platform macOS ARM64)"
expect 'macOS on Intel' macos-x64 "$(platform macOS X64)"
expect 'Windows on x64' windows-x64 "$(platform Windows X64)"
expect 'Windows on arm64, which has no executable' '' "$(platform Windows ARM64)"
expect 'A 32-bit runner' '' "$(platform Linux X86)"
expect 'No runner' '' "$(platform '' '')"

root=kiff-1.2.3-linux-x64
outside() {
  printf '%s\n' "$@" | entry_outside "$root"
}

expect 'An archive of its directory alone' '' \
  "$(outside "$root/" "$root/kiff" "$root/LICENSE" "$root/README.md" "$root")"
expect 'A name with two dots in it' '' "$(outside "$root/a..b")"
expect 'An entry that climbs out' "$root/../../x" "$(outside "$root/kiff" "$root/../../x")"
expect 'An entry that climbs out with backslashes' "$root/..\..\x" "$(outside "$root/..\..\x")"
expect 'An entry beside the directory' x "$(outside "$root/" x)"
expect 'An absolute entry' /etc/x "$(outside /etc/x)"
expect 'A directory that only starts with its name' "$root-x/kiff" "$(outside "$root-x/kiff")"
expect 'An entry under ./' "./$root/kiff" "$(outside "./$root/kiff")"
expect 'Nothing at all' '' "$(entry_outside "$root" < /dev/null)"

if ((failures > 0)); then
  exit 1
fi
echo "Every case passed"
