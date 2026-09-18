#!/usr/bin/env bash
#
# Publish the draft release — but only once every required asset is really on it.
#
#   .github/scripts/finalize-release.sh <tag> <required-asset-name>...
#
# The build jobs publish into one draft release. Whichever job finished first used to flip the
# release out of draft, so a release could go public while another platform's asset was missing
# or had just been deleted by a failed retry (v1.24.0 sat published with only the two Linux
# assets). Draft stays on until this check passes.
set -euo pipefail

TAG="${1:?usage: finalize-release.sh <tag> <required-asset>...}"
shift

assets="$(gh release view "$TAG" --json assets --jq '.assets[] | .name + " " + (.size|tostring)')"
echo "assets on $TAG:"; echo "$assets" | sed 's/^/  /'

missing=0
for required in "$@"; do
  line="$(printf '%s\n' "$assets" | awk -v n="$required" '$1 == n {print; exit}')"
  size="${line#* }"
  if [ -z "$line" ] || [ "${size:-0}" -le 0 ]; then
    echo "ERROR: required asset missing or empty: $required" >&2
    missing=1
  fi
done
[ "$missing" -eq 0 ] || { echo "refusing to publish an incomplete release" >&2; exit 1; }

gh release edit "$TAG" --draft=false >/dev/null
draft="$(gh release view "$TAG" --json isDraft --jq .isDraft)"
[ "$draft" = "false" ] || { echo "ERROR: release is still a draft" >&2; exit 1; }

# A draft release has no tag on the remote; after publishing it must be there, or the download
# URLs in the notes point at nothing. Asked over the API, so this works from any directory.
gh api "repos/{owner}/{repo}/git/ref/tags/$TAG" --jq .ref >/dev/null || {
  echo "ERROR: no refs/tags/$TAG on the remote after publishing" >&2; exit 1; }
echo "published $TAG"
