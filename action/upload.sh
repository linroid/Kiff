#!/usr/bin/env bash
# Attaches the patches, then the manifest, to the release, replacing files of the same names so a
# re-run leaves the release as one run would. The manifest goes last so that an updater never reads
# one that lists a patch not yet there. gh finds a draft by its tag as well as a published release.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=action/lib.sh
. "$here/lib.sh"

directory=${DIRECTORY:?}
tag=${TAG:?}
manifest=${MANIFEST:-}

patches=()
for path in "$directory"/*; do
  if [[ -f $path && $path != "$manifest" ]]; then
    patches+=("$path")
  fi
done

if ((${#patches[@]} > 0)); then
  gh release upload "$tag" "${patches[@]}" --clobber
fi
if [[ -n $manifest ]]; then
  gh release upload "$tag" "$manifest" --clobber
else
  notice "No manifest was written, so nothing is attached to $tag."
  exit 0
fi

attached="${#patches[@]} patches and ${manifest##*/}"
if ((${#patches[@]} == 1)); then
  attached="1 patch and ${manifest##*/}"
fi
echo "Attached $attached to $tag of ${GH_REPO:?}"
if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then
  printf '\nAttached %s to %s.\n' "$attached" "$tag" >> "$GITHUB_STEP_SUMMARY"
fi
