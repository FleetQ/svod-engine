#!/usr/bin/env bash
# Notarize a signed Svod app image with Apple and staple the ticket.
#
# Notarization requires a SIGNED app (run package.sh with SVOD_SIGN_IDENTITY first).
#
# ---------------------------------------------------------------------------
# One-time setup: store notarytool credentials in a keychain profile.
# Run this once on the build machine; it is interactive and prompts for an
# app-specific password (create one at https://appleid.apple.com).
#
#   xcrun notarytool store-credentials "svod-notary" \
#     --apple-id "you@example.com" \
#     --team-id "YOURTEAMID" \
#     --password "app-specific-password"
#
# Then export the profile name before invoking this script:
#   export SVOD_NOTARY_PROFILE="svod-notary"
#
# Credentials live only in the keychain — this script never sees or stores
# Apple IDs, passwords, or team IDs. Do NOT hardcode them here.
# ---------------------------------------------------------------------------
set -euo pipefail

: "${SVOD_NOTARY_PROFILE:?set SVOD_NOTARY_PROFILE to a notarytool keychain profile (see header)}"

DIST_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIST_DIR/build"
NAME="SvodEngine"

# Target: explicit arg (app bundle or zip) or the default built app image.
TARGET="${1:-$OUT/$NAME.app}"
[ -e "$TARGET" ] || { echo "target not found: $TARGET" >&2; exit 1; }

# notarytool needs a single file. Zip an .app bundle for submission; submit
# .zip/.dmg/.pkg as-is.
case "$TARGET" in
  *.app)
    SUBMIT_ZIP="$OUT/$NAME-notarize.zip"
    echo "==> zipping app for submission: $SUBMIT_ZIP"
    rm -f "$SUBMIT_ZIP"
    /usr/bin/ditto -c -k --keepParent "$TARGET" "$SUBMIT_ZIP"
    SUBMIT="$SUBMIT_ZIP"
    ;;
  *)
    SUBMIT="$TARGET"
    ;;
esac

echo "==> submitting to Apple notary service (this waits for the result)"
xcrun notarytool submit "$SUBMIT" \
  --keychain-profile "$SVOD_NOTARY_PROFILE" \
  --wait

# Staple the ticket onto the app/dmg/pkg so Gatekeeper validates offline.
# (A zip cannot be stapled — staple the original .app, not the submission zip.)
echo "==> stapling ticket to $TARGET"
xcrun stapler staple "$TARGET"

echo "==> notarized and stapled: $TARGET"
