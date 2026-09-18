#!/usr/bin/env bash
#
# Upload release assets one at a time, with retries, skipping what is already there.
#
#   .github/scripts/publish-release-assets.sh <tag> <file>...
#
# Why not a one-shot upload: GitHub fails often enough on these 100-220 MB assets that a single
# attempt is not a release process. v1.24.0 needed four runs ("Error saving asset", "other side
# closed") while the status page stayed green. And an upload that deletes the existing asset
# before re-uploading leaves the release with FEWER assets when the retry fails, so rerunning a
# failed job made a published release worse. Here an asset that is already uploaded with the right
# size is left alone, and each file is retried on its own.
set -euo pipefail

TAG="${1:?usage: publish-release-assets.sh <tag> <file>...}"
shift
ATTEMPTS="${UPLOAD_ATTEMPTS:-5}"

remote_size() {
  gh release view "$TAG" --json assets \
    --jq ".assets[] | select(.name == \"$1\") | .size" 2>/dev/null | head -1
}

failed=0
for file in "$@"; do
  # The native-image binaries are best-effort (their build step is continue-on-error), so a
  # missing file is not a failure — an upload that never lands is.
  if [ ! -f "$file" ]; then
    echo "skip (not built): $file"
    continue
  fi
  name="$(basename "$file")"
  size="$(wc -c < "$file" | tr -d '[:space:]')"
  uploaded=""

  for attempt in $(seq 1 "$ATTEMPTS"); do
    if [ "$(remote_size "$name")" = "$size" ]; then
      uploaded=yes
      break
    fi
    echo "uploading $name ($size bytes) — attempt $attempt/$ATTEMPTS"
    gh release upload "$TAG" "$file" --clobber || echo "upload attempt $attempt failed"
    if [ "$(remote_size "$name")" = "$size" ]; then
      uploaded=yes
      break
    fi
    [ "$attempt" -lt "$ATTEMPTS" ] && sleep $((attempt * 20))
  done

  if [ -n "$uploaded" ]; then
    echo "ok: $name ($size bytes)"
  else
    echo "ERROR: $name did not upload in $ATTEMPTS attempts" >&2
    failed=1
  fi
done

exit "$failed"
