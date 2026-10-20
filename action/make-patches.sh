#!/usr/bin/env bash
# Downloads the files the plan names and runs kiff release once per file, every run writing into one
# directory and one manifest. Writes the job summary, then fails if any run did, so that nothing is
# uploaded unless every patch restored.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=action/lib.sh
. "$here/lib.sh"

plan=${PLAN:?}
kiff=${KIFF:?}
work=$(dirname "$plan")
repo=$(jq -r .repository "$plan")
tag=$(jq -r .tag "$plan")
max_ratio=${MAX_RATIO:?}
patcher=$(trim "${INPUT_PATCHER:-}")
name=$(trim "${INPUT_NAME:-}")

out=$work/patches
manifest=$out/${MANIFEST:?}
mkdir -p "$out"
base_url=${GITHUB_SERVER_URL:-https://github.com}/$repo/releases/download/$(
  jq -rn --arg tag "$tag" '$tag | @uri'
)/

# Downloads the asset with id $1, size $2 and name $3, once, and sets path to where it is.
download() {
  local dir=$work/assets/$1 size
  path=$dir/$3
  if [[ -f $path ]]; then
    return 0
  fi
  mkdir -p "$dir"
  # By id rather than by URL, which reaches a draft's files too.
  if ! gh api -H 'Accept: application/octet-stream' "repos/$repo/releases/assets/$1" \
    > "$path.part"; then
    fail "Could not download $3 from $repo."
  fi
  size=$(wc -c < "$path.part" | tr -d '[:space:]')
  if [[ $size != "$2" ]]; then
    fail "Downloaded $size bytes of $3, but $repo lists it at $2."
  fi
  mv "$path.part" "$path"
}

# $1 made safe for a cell of a Markdown table.
cell() {
  printf '%s' "${1//|/\\|}"
}

# Sets size, share and result for the source labelled $1 from the output of kiff release in $2,
# which prints a line for each source:
#   v1.2.0     4.6 MiB    6.42%  restored  app-1.3.0.apk.from-v1.2.0.kiff
#   v1.0.0    60.2 MiB   83.96%  dropped, over --max-ratio 0.8
#   v0.9.0  failed: <why>
read_result() {
  local line rest status measured='^([0-9.]+ [KMGT]?i?B) +([0-9.]+%) +(.*)$'
  size='' share='' result=''
  while IFS= read -r line; do
    if [[ $line == "  $1 "* ]]; then
      rest=$(trim "${line#"  $1 "}")
      if [[ $rest =~ $measured ]]; then
        size=${BASH_REMATCH[1]}
        share=${BASH_REMATCH[2]}
        status=${BASH_REMATCH[3]}
        case $status in
          restored*) result='restored exactly' ;;
          dropped*) result="dropped, over max-ratio $max_ratio" ;;
          *) result=$status ;;
        esac
      else
        result=$rest
      fi
      return 0
    fi
  done < "$2"
  # kiff release stopped before it reached the sources, and said why on a line of its own.
  result="failed: $(sed -n 's/^Error: //p' "$2" | tail -n 1)"
}

rows=''
failed=0
files=$(jq '.files | length' "$plan")
for ((i = 0; i < files; i++)); do
  file=$(jq -c ".files[$i]" "$plan")
  pattern=$(jq -r .pattern <<< "$file")
  target_name=$(jq -r .target.name <<< "$file")
  file_cell="\`$(cell "$target_name")\`"

  while IFS= read -r missing; do
    if [[ -n $missing ]]; then
      rows+="| $file_cell | $(cell "$missing") | | | not in that release |"$'\n'
    fi
  done < <(jq -r '.missing[]' <<< "$file")

  if [[ $(jq '.sources | length' <<< "$file") == 0 ]]; then
    notice "No earlier release has a file matching '$pattern', so $target_name gets no patches."
    rows+="| $file_cell | | | | no earlier release has it |"$'\n'
    continue
  fi

  IFS=$'\t' read -r id size name_of_target < <(
    jq -r '[.target.id, .target.size, .target.name] | @tsv' <<< "$file"
  )
  download "$id" "$size" "$name_of_target"
  args=(
    release
    --to "$tag=$path"
    --out "$out"
    --manifest "$manifest"
    --base-url "$base_url"
    --max-ratio "$max_ratio"
  )
  if [[ -n $patcher ]]; then
    args+=(--patcher "$patcher")
  fi
  if [[ -n $name ]]; then
    args+=(--name "$name")
  fi
  labels=()
  while IFS=$'\t' read -r from id size source_name; do
    download "$id" "$size" "$source_name"
    args+=(--from "$from=$path")
    labels+=("$from")
  done < <(jq -r '.sources[] | [.tag, .id, .size, .name] | @tsv' <<< "$file")

  log=$work/kiff-$i.log
  status=0
  "$kiff" "${args[@]}" 2>&1 | tee "$log" || status=$?
  if ((status != 0)); then
    failed=$((failed + 1))
  fi
  for label in "${labels[@]}"; do
    read_result "$label" "$log"
    rows+="| $file_cell | $(cell "$label") | $size | $share | $(cell "$result") |"$'\n'
  done
done

set_output directory "$out"
if [[ -f $manifest ]]; then
  set_output manifest "$manifest"
  patches=$(jq -c '
    [
      .files[] as $file | $file.patches[]
      | {
          file: $file.name,
          from,
          name,
          url,
          size,
          ratio: (if $file.size > 0 then (.size / $file.size * 10000 | round) / 10000 else 0 end)
        }
    ]
  ' "$manifest")
else
  set_output manifest ''
  patches='[]'
fi
count=$(jq length <<< "$patches")
set_output patches "$patches"
set_output count "$count"

if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then
  {
    echo "### Kiff patches for $tag"
    echo
    if [[ -n $rows ]]; then
      echo '| File | From | Patch size | Share of file | Result |'
      echo '| --- | --- | ---: | ---: | --- |'
      printf '%s\n' "$rows"
    fi
    if ((failed > 0)); then
      echo "kiff release failed for $failed of $files files, so nothing was uploaded."
    elif ((count == 1)); then
      echo "1 patch made."
    else
      echo "$count patches made."
    fi
  } >> "$GITHUB_STEP_SUMMARY"
fi

if ((failed > 0)); then
  fail "kiff release failed for $failed of $files files, so nothing is uploaded."
fi
