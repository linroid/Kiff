#!/usr/bin/env bash
# Tests how action/plan.sh finds a repository's releases, with gh and sleep replaced, so no network
# is needed and no time passes: a release the inputs name is fetched by its tag whatever the list
# of releases says, an empty list is fetched again when the plan needs it, and a release that is
# really missing still fails the plan with its own message.
set -euo pipefail

plan_sh=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../action" && pwd)/plan.sh
stub=$(mktemp -d)
trap 'rm -rf "$stub"' EXIT
mkdir "$stub/bin"

failures=0

# Checks that $1, a description, came out as $3 when $2 was expected.
expect() {
  if [[ $3 != "$2" ]]; then
    echo "::error::$1 gave '$3', not '$2'."
    failures=$((failures + 1))
  fi
}

# gh api finds a release by its tag in $STUB/published.jsonl, as GitHub finds only a published one
# so, and fails as GitHub does for a tag in $STUB/broken. It lists the releases as the listings
# named in $STUB/listings say, one per call and the last one from then on. Both print what
# gh api --jq writes for plan.sh. sleep only notes the pause it was asked for.
cat > "$stub/bin/gh" << 'EOF'
#!/usr/bin/env bash
for arg; do
  case $arg in
    repos/*/releases/tags/*) tag=${arg##*/} ;;
  esac
done
if [[ -z ${tag:-} ]]; then
  calls=$(($(cat "$STUB/calls") + 1))
  echo "$calls" > "$STUB/calls"
  listing=$(sed -n "${calls}p" "$STUB/listings")
  cat "$STUB/${listing:-$(tail -n 1 "$STUB/listings")}.jsonl"
elif grep -qx "$tag" "$STUB/broken"; then
  echo 'gh: Server Error (HTTP 502)' >&2
  exit 1
elif ! jq -ce --arg tag "$tag" 'select(.tag == $tag)' "$STUB/published.jsonl"; then
  echo '{"message":"Not Found","status":"404"}'
  echo 'gh: Not Found (HTTP 404)' >&2
  exit 1
fi
EOF
cat > "$stub/bin/sleep" << 'EOF'
#!/usr/bin/env bash
echo "$1" >> "$STUB/pauses"
EOF
chmod +x "$stub/bin/gh" "$stub/bin/sleep"
echo v5.0.0 > "$stub/broken"

# The release with id $1 and tag $2, a draft when $3 is true, which has one file.
release() {
  printf '{"id":%d,"tag":"%s","draft":%s,"prerelease":false,' "$1" "$2" "$3"
  printf '"assets":[{"id":%d,"name":"tool-%s.bin","size":100}]}\n' "$(($1 * 10))" "${2#v}"
}
release 1 v1.0.0 false > "$stub/published.jsonl"
release 2 v1.1.0 false >> "$stub/published.jsonl"
release 3 v1.2.0 false >> "$stub/published.jsonl"
{ cat "$stub/published.jsonl"; release 4 v1.3.0 true; } > "$stub/all.jsonl"
: > "$stub/none.jsonl"

# Plans tool-*.bin of the release tagged $1, from the release $2 or from the ones before it when $2
# is empty, while gh lists the releases as the rest name in turn. Prints how often it listed them,
# how many pauses it took between, and the tags the plan patches from, or the error it failed with.
plan() {
  local tag=$1 from=$2 out result
  shift 2
  printf '%s\n' "$@" > "$stub/listings"
  echo 0 > "$stub/calls"
  : > "$stub/pauses"
  : > "$stub/output"
  if out=$(STUB=$stub PATH="$stub/bin:$PATH" GH_REPO=o/r INPUT_TAG=$tag INPUT_FROM=$from \
    INPUT_ASSETS='tool-*.bin' RUNNER_TEMP=$stub GITHUB_OUTPUT=$stub/output "$plan_sh" 2>&1); then
    result=$(jq -r '"from " + ([.files[].sources[].tag] | join(" "))' \
      "$(sed -n 's/^plan=//p' "$stub/output")")
  else
    result=$(sed -n 's/^::error:://p' <<< "$out")
  fi
  printf '%s lists, %s pauses, %s' "$(cat "$stub/calls")" "$(wc -l < "$stub/pauses" | tr -d ' ')" \
    "$result"
}

expect 'A full list' '1 lists, 0 pauses, from v1.1.0 v1.0.0' "$(plan v1.2.0 '' all)"
expect 'An empty list, when from names releases that are there' '0 lists, 0 pauses, from v1.0.0' \
  "$(plan v1.2.0 v1.0.0 none)"
expect 'An empty list for the releases before, which fills later' \
  '3 lists, 2 pauses, from v1.1.0 v1.0.0' "$(plan v1.2.0 '' none none all)"
expect 'A draft, found once the list fills' '2 lists, 1 pauses, from v1.2.0 v1.1.0 v1.0.0' \
  "$(plan v1.3.0 '' none all)"
expect 'A list that stays empty' "6 lists, 5 pauses, GitHub's list of the releases of o/r stayed \
empty for a minute, though v1.2.0 is one of them. Run the action again later." \
  "$(plan v1.2.0 '' none)"
waited=$(($(paste -sd + "$stub/pauses")))
if ((waited < 40 || waited > 80)); then
  echo "::error::A list that stays empty is fetched again after pauses of $waited seconds in all," \
    "not about a minute."
  failures=$((failures + 1))
fi
expect 'A release that is not there' '1 lists, 0 pauses, o/r has no release tagged v2.0.0. A '\
'draft is only visible to a token that can write to the repository.' "$(plan v2.0.0 '' all)"
expect 'A from that is not there' '1 lists, 0 pauses, from lists v0.9.0, but o/r has no release '\
'tagged v0.9.0.' "$(plan v1.2.0 v0.9.0 all)"
expect 'A release that is not there, where the list stays empty' '6 lists, 5 pauses, o/r has no '\
'release tagged v2.0.0. A draft is only visible to a token that can write to the repository.' \
  "$(plan v2.0.0 '' none)"
expect 'A release GitHub fails to look up' '0 lists, 0 pauses, Could not look up the release '\
'tagged v5.0.0 in o/r.' "$(plan v5.0.0 '' all)"

if ((failures > 0)); then
  exit 1
fi
echo "Every case passed"
