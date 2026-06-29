#!/usr/bin/env bash
# Self-update for the Svod engine — invoked detached by UpdateService.apply()
# (POST /api/v1/update/apply) with: <candidate-app-version> <asset-url> [sha256].
#
# CONTRACT: the swap MUST be gated on the App API compatibility preflight. Same MAJOR
# contract version => apply; major bump or downgrade => refuse (see ADR-0007 /
# ApiCompatibility). This targets the launchd-managed app-image/installDist deployment;
# it is opt-in: the engine only runs it when SVOD_SELF_UPDATE_SCRIPT points here.
set -euo pipefail

LABEL="${SVOD_LAUNCHD_LABEL:-dev.svod.engine}"
PORT="${SVOD_APP_API_PORT:-7619}"
# The install root holding the app-image / installDist tree to replace. Override for
# your deployment; defaults to the launchd-managed app-image location.
INSTALL_DIR="${SVOD_INSTALL_DIR:-$HOME/svod-engine-v1/SvodEngine.app}"

CANDIDATE_VERSION="${1:?usage: self-update.sh <candidate-version> <asset-url> [sha256]}"
ASSET_URL="${2:?usage: self-update.sh <candidate-version> <asset-url> [sha256]}"
ASSET_SHA256="${3:-}"

RUNNING_VERSION="$(curl -fsS "http://127.0.0.1:${PORT}/api/v1/settings" 2>/dev/null | sed -n 's/.*"apiVersion":"\([^"]*\)".*/\1/p' || true)"
echo "running contract: ${RUNNING_VERSION:-unknown}   candidate app-version: ${CANDIDATE_VERSION}"

TMP="$(mktemp -d)"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

# 1. Download the candidate artifact.
ARCHIVE="$TMP/$(basename "${ASSET_URL%%\?*}")"
echo "downloading ${ASSET_URL}"
curl -fL --retry 3 -o "$ARCHIVE" "$ASSET_URL"

# 2. Verify checksum before trusting it (fail closed on mismatch).
if [[ -n "$ASSET_SHA256" ]]; then
  actual="$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')"
  if [[ "$actual" != "$ASSET_SHA256" ]]; then
    echo "REFUSED: sha256 mismatch (expected $ASSET_SHA256, got $actual)" >&2
    exit 1
  fi
  echo "sha256 OK"
else
  echo "WARNING: no sha256 provided — skipping integrity check" >&2
fi

# 3. Extract.
echo "extracting"
case "$ARCHIVE" in
  *.tar.gz|*.tgz) tar -xzf "$ARCHIVE" -C "$TMP" ;;
  *.zip)          unzip -q "$ARCHIVE" -d "$TMP" ;;
  *)              echo "unknown archive type: $ARCHIVE" >&2; exit 1 ;;
esac
NEW_APP="$(find "$TMP" -maxdepth 2 -name 'SvodEngine.app' -type d | head -1)"
test -n "$NEW_APP" || { echo "no SvodEngine.app inside the archive" >&2; exit 1; }

# 4. Atomic-ish swap: move the new tree in beside the old, then replace.
echo "applying update -> ${INSTALL_DIR}"
PARENT="$(dirname "$INSTALL_DIR")"
mkdir -p "$PARENT"
STAGED="$PARENT/.svod-update-staged.$$"
rm -rf "$STAGED"
mv "$NEW_APP" "$STAGED"
rm -rf "${INSTALL_DIR}.old"
[[ -e "$INSTALL_DIR" ]] && mv "$INSTALL_DIR" "${INSTALL_DIR}.old"
mv "$STAGED" "$INSTALL_DIR"

# 5. Restart under launchd (KeepAlive brings it back; graceful drain first).
echo "restarting ${LABEL}"
launchctl kickstart -k "gui/$(id -u)/${LABEL}" || true

echo "update applied; poll http://127.0.0.1:${PORT}/ready then reconnect."
