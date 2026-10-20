#!/usr/bin/env bash
# Settles what the run will do before anything slow starts: the release that gets patches, the file
# of it each pattern names, and the earlier releases each file is patched from. The steps after it
# follow plan.json and ask GitHub only for downloads, so a bad input fails here, in seconds.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=action/lib.sh
. "$here/lib.sh"

repo=$(trim "${GH_REPO:-}")
if [[ ! $repo =~ ^[^/[:space:]]+/[^/[:space:]]+$ ]]; then
  fail "repository is '$repo', not OWNER/NAME."
fi

previous=$(trim "${INPUT_PREVIOUS:-3}")
if [[ ! $previous =~ ^[0-9]+$ ]]; then
  fail "previous is '$previous', not a whole number."
fi
previous=$((10#$previous))

# true or false, from an input that says so in any case.
boolean() {
  case $(lowercase "$(trim "$1")") in
    true) echo true ;;
    false) echo false ;;
    *) return 1 ;;
  esac
}
prereleases=$(boolean "${INPUT_PRERELEASES:-false}") \
  || fail "prereleases is '${INPUT_PRERELEASES:-}', not true or false."
upload=$(boolean "${INPUT_UPLOAD:-true}") || fail "upload is '${INPUT_UPLOAD:-}', not true or false."

max_ratio=$(trim "${INPUT_MAX_RATIO:-0.8}")
if [[ ! $max_ratio =~ ^([0-9]+(\.[0-9]*)?|\.[0-9]+)$ ]]; then
  fail "max-ratio is '$max_ratio', not a number such as 0.8."
fi

manifest=$(trim "${INPUT_MANIFEST:-kiff-patches.json}")
if [[ -z $manifest || $manifest == . || $manifest == .. || $manifest == */* || $manifest == *\\* ]]
then
  fail "manifest is '$manifest', which is not a file name."
fi

patterns=()
while IFS= read -r line; do
  line=$(trim "$line")
  if [[ -n $line ]]; then
    patterns+=("$line")
  fi
done <<< "${INPUT_ASSETS:-}"
if ((${#patterns[@]} == 0)); then
  fail "assets is empty. Give a glob pattern for each file to patch, one per line."
fi

# kiff release takes a tag as the label of a file, written LABEL=FILE, and puts it in the patch's
# name, which is no place for a separator.
usable_tag() {
  [[ $1 != */* && $1 != *\\* && $1 != *=* ]]
}

tag=$(trim "${INPUT_TAG:-}")
if [[ -z $tag && -f ${GITHUB_EVENT_PATH:-} ]]; then
  tag=$(jq -r '.release.tag_name // empty' "$GITHUB_EVENT_PATH")
fi
if [[ -z $tag && ${GITHUB_REF_TYPE:-} == tag ]]; then
  tag=${GITHUB_REF_NAME:-}
fi
if [[ -z $tag ]]; then
  fail "No release to make patches for. Set the tag input, or run on a release event or a tag push."
fi
if ! usable_tag "$tag"; then
  fail "Tag $tag has a '/', '\\' or '=', which a patch's name cannot hold."
fi

froms=()
while IFS= read -r line; do
  line=$(trim "$line")
  if [[ -z $line ]]; then
    continue
  fi
  if [[ $line == "$tag" ]]; then
    fail "from lists $tag, which is the release being patched."
  fi
  if ! usable_tag "$line"; then
    fail "from lists $line, which has a '/', '\\' or '=' that a patch's name cannot hold."
  fi
  for seen in ${froms[@]+"${froms[@]}"}; do
    if [[ $seen == "$line" ]]; then
      continue 2
    fi
  done
  froms+=("$line")
done <<< "${INPUT_FROM:-}"

work=$(mktemp -d "${RUNNER_TEMP:?}/kiff-release.XXXXXX")

# The API lists drafts only to a token that can write to the repository, which is why the release
# is looked for in the list rather than fetched by its tag.
if ! gh api --paginate "repos/$repo/releases?per_page=100" \
  --jq '.[] | {id, tag: .tag_name, draft, prerelease, assets: [.assets[] | {id, name, size}]}' \
  > "$work/releases.jsonl"; then
  fail "Could not list the releases of $repo."
fi

# The release, and the published releases before it in version order, closest first. A tag that
# reads as a version is only compared with others that do; any other is compared naturally.
jq -s -L "$here" --arg tag "$tag" --argjson prereleases "$prereleases" '
  include "versions";
  ($tag | version_key) as $version
  | ($tag | natural_key) as $natural
  | {
      all: .,
      targets: map(select(.tag == $tag)),
      earlier: (
        map(
          select(.tag != $tag and (.draft | not) and ($prereleases or (.prerelease | not)))
          | . + {key: (if $version != null then .tag | version_key else .tag | natural_key end)}
          | select(.key != null and .key < ($version // $natural))
        )
        | sort_by([.key, .tag]) | reverse | map(del(.key))
      )
    }
' "$work/releases.jsonl" > "$work/releases.json"

case $(jq '.targets | length' "$work/releases.json") in
  1) ;;
  0) fail "$repo has no release tagged $tag. A draft is only visible to a token that can write to \
the repository." ;;
  *) fail "$repo has several releases tagged $tag. Delete the extra drafts so one is left." ;;
esac
release=$(jq -c '.targets[0] | {id, draft, prerelease}' "$work/releases.json")

# Lines of "id<TAB>size<TAB>name", one per asset of the release tagged $1.
assets_of() {
  jq -r --arg tag "$1" '
    .all[] | select(.tag == $tag) | .assets[] | [.id, .size, .name] | @tsv
  ' "$work/releases.json"
}

# Sets found to the lines of $2, as assets_of writes them, whose names the glob $1 matches. Kiff's
# own output is never taken as input.
match() {
  found=()
  local id size name
  while IFS=$'\t' read -r id size name; do
    if [[ -z $name || $name == *.kiff || $name == "$manifest" ]]; then
      continue
    fi
    # shellcheck disable=SC2053 # The pattern is a glob, so it is left unquoted.
    if [[ $name == $1 ]]; then
      found+=("$id"$'\t'"$size"$'\t'"$name")
    fi
  done <<< "$2"
}

# The names in found, or in the lines of $1, joined by ", ".
names() {
  local list='' id size name
  while IFS=$'\t' read -r id size name; do
    if [[ -n $name && $name != *.kiff && $name != "$manifest" ]]; then
      list+="${list:+, }$name"
    fi
  done <<< "$1"
  printf '%s' "${list:-none}"
}

found_names() {
  local IFS=$'\n'
  names "${found[*]}"
}

target_assets=$(assets_of "$tag")
if [[ ${#froms[@]} -eq 0 ]]; then
  earlier_assets=$(jq -r '
    .earlier[] | .tag as $tag | .assets[] | [$tag, .id, .size, .name] | @tsv
  ' "$work/releases.json")
fi

: > "$work/files.jsonl"
taken=''
summary="Release $tag of $repo"
for pattern in "${patterns[@]}"; do
  match "$pattern" "$target_assets"
  case ${#found[@]} in
    1) ;;
    0) fail "'$pattern' matches no file of $tag. Its files are: $(names "$target_assets")." ;;
    *) fail "'$pattern' matches ${#found[@]} files of $tag: $(found_names). Give each file a \
pattern of its own." ;;
  esac
  target=${found[0]}
  target_name=${target##*$'\t'}
  while IFS=$'\t' read -r other name; do
    if [[ -n $name && $name == "$target_name" ]]; then
      fail "'$other' and '$pattern' both match $target_name."
    fi
  done <<< "$taken"
  taken+="$pattern"$'\t'"$target_name"$'\n'

  sources=''
  missing=''
  if [[ ${#froms[@]} -eq 0 ]]; then
    # The closest earlier releases that have the file. One without it is passed over and does not
    # count against previous. Each release's assets arrive together, and the blank line after the
    # last one closes it.
    count=0
    current=''
    pending=()
    while ((previous > 0)) && IFS=$'\t' read -r from id size name; do
      if [[ $from != "$current" ]]; then
        if ((${#pending[@]} > 1)); then
          found=("${pending[@]}")
          fail "'$pattern' matches ${#pending[@]} files of $current: $(found_names). Give each \
file a pattern of its own."
        elif ((${#pending[@]} == 1)); then
          sources+="$current"$'\t'"${pending[0]}"$'\n'
          count=$((count + 1))
          if ((count == previous)); then
            break
          fi
        fi
        current=$from
        pending=()
      fi
      if [[ -z $from ]]; then
        break
      fi
      match "$pattern" "$id"$'\t'"$size"$'\t'"$name"
      pending+=(${found[@]+"${found[@]}"})
    done < <(printf '%s\n\n' "$earlier_assets")
  else
    for from in "${froms[@]}"; do
      releases=$(jq --arg tag "$from" '[.all[] | select(.tag == $tag)] | length' \
        "$work/releases.json")
      case $releases in
        1) ;;
        0) fail "from lists $from, but $repo has no release tagged $from." ;;
        *) fail "from lists $from, and $repo has several releases tagged $from." ;;
      esac
      match "$pattern" "$(assets_of "$from")"
      case ${#found[@]} in
        1) sources+="$from"$'\t'"${found[0]}"$'\n' ;;
        0)
          warn "$from has no file matching '$pattern', so $target_name gets no patch from it."
          missing+="$from"$'\n'
          ;;
        *) fail "'$pattern' matches ${#found[@]} files of $from: $(found_names). Give each file \
a pattern of its own." ;;
      esac
    done
  fi

  jq -n --arg pattern "$pattern" --arg target "$target" --arg sources "$sources" \
    --arg missing "$missing" '
    def lines: split("\n") | map(select(. != ""));
    def asset: {id: (.[0] | tonumber), size: (.[1] | tonumber), name: .[2]};
    {
      pattern: $pattern,
      target: ($target | split("\t") | asset),
      sources: [$sources | lines[] | split("\t") | {tag: .[0]} + (.[1:] | asset)],
      missing: ($missing | lines)
    }
  ' >> "$work/files.jsonl"

  froms_of=$(jq -rs 'last | [.sources[].tag] | join(", ")' "$work/files.jsonl")
  summary+=$'\n'"  $pattern: $target_name from ${froms_of:-no earlier release}"
done

jq -s --arg repo "$repo" --arg tag "$tag" --argjson release "$release" \
  --argjson previous "$previous" '
  {repository: $repo, tag: $tag, release: $release, previous: $previous, files: .}
' "$work/files.jsonl" > "$work/plan.json"

echo "$summary"

set_output work "$work"
set_output plan "$work/plan.json"
set_output repository "$repo"
set_output tag "$tag"
set_output upload "$upload"
set_output max-ratio "$max_ratio"
set_output manifest "$manifest"
