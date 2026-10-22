#!/usr/bin/env bash
# Tests how action/plan.sh lists a repository's releases, with gh and sleep replaced, so no network
# is needed and no time passes: a listing that leaves out a release the inputs name is fetched
# again, and a release that is really missing still fails the plan with its own message.
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

# gh prints the listings named in $STUB/listings, one per call and the last one from then on, as
# gh api --jq writes them for plan.sh. sleep only notes the pause it was asked for.
cat > "$stub/bin/gh" << 'EOF'
#!/usr/bin/env bash
calls=$(($(cat "$STUB/calls") + 1))
echo "$calls" > "$STUB/calls"
listing=$(sed -n "${calls}p" "$STUB/listings")
cat "$STUB/${listing:-$(tail -n 1 "$STUB/listings")}.jsonl"
EOF
cat > "$stub/bin/sleep" << 'EOF'
#!/usr/bin/env bash
echo "$1" >> "$STUB/pauses"
EOF
chmod +x "$stub/bin/gh" "$stub/bin/sleep"

# The listing's line for the release with id $1 and tag $2, which has one file.
release() {
  printf '{"id":%d,"tag":"%s","draft":false,"prerelease":false,' "$1" "$2"
  printf '"assets":[{"id":%d,"name":"tool-%s.bin","size":100}]}\n' "$(($1 * 10))" "${2#v}"
}
{ release 1 v1.0.0; release 2 v1.1.0; release 3 v1.2.0; } > "$stub/all.jsonl"
: > "$stub/none.jsonl"
release 1 v1.0.0 > "$stub/oldest.jsonl"
{ release 2 v1.1.0; release 3 v1.2.0; } > "$stub/newest.jsonl"

# Plans tool-*.bin of the release tagged $1, from the release $2 or from the ones before it when $2
# is empty, while gh lists the releases as the rest name in turn. Prints how many listings it took,
# the pauses between them, and the tags the plan patches from, or the error it failed with.
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
  printf '%s listings, pauses [%s], %s' "$(cat "$stub/calls")" "$(paste -sd ' ' "$stub/pauses")" \
    "$result"
}

expect 'A complete listing' '1 listings, pauses [], from v1.1.0 v1.0.0' "$(plan v1.2.0 '' all)"
expect 'An empty listing, then a complete one' '2 listings, pauses [2], from v1.1.0 v1.0.0' \
  "$(plan v1.2.0 '' none all)"
expect 'Two listings without the release' '3 listings, pauses [2 4], from v1.1.0 v1.0.0' \
  "$(plan v1.2.0 '' oldest oldest all)"
expect 'A listing without the release from names' '2 listings, pauses [2], from v1.0.0' \
  "$(plan v1.2.0 v1.0.0 newest all)"
expect 'A release that is not there' '4 listings, pauses [2 4 8], o/r has no release tagged '\
'v2.0.0. A draft is only visible to a token that can write to the repository.' \
  "$(plan v2.0.0 '' all)"
expect 'A from that is not there' '4 listings, pauses [2 4 8], from lists v0.9.0, but o/r has '\
'no release tagged v0.9.0.' "$(plan v1.2.0 v0.9.0 all)"

if ((failures > 0)); then
  exit 1
fi
echo "Every case passed"
