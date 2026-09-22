#!/usr/bin/env bash
# Self-update for a launchd-managed Svod engine on macOS.
#
# The engine runs this (POST /api/v1/update/apply) as:  self-update.sh <version> <asset-url> [sha256]
# Run it by hand with no arguments to install the latest release. The engine looks for it at
# ~/.config/svod/self-update.sh (or $SVOD_SELF_UPDATE_SCRIPT); to set a machine up once:
#
#   mkdir -p ~/.config/svod && curl -fsSL -o ~/.config/svod/self-update.sh \
#     https://github.com/FleetQ/svod-engine/releases/latest/download/self-update.sh \
#     && bash ~/.config/svod/self-update.sh
#
# The install is read from the running engine (~/.config/svod/engine.json → its pid → command
# line), so no paths are configured. Three layouts:
#   app-image   …/SvodEngine.app                 replaced from SvodEngine-<platform>.tar.gz
#   lib dir     java -cp …/lib/* or installDist  jars synced from the same archive; an installDist
#                                                bin/svod-engine is pointed at lib/* (it names
#                                                every jar with its version)
#   native      …/svod-engine-<platform>         replaced from the native binary
# Only the asset for THIS layout is downloaded, whatever URL the engine passed: engines up to
# 1.25.0 pass the native binary, which the app-image and lib layouts cannot use. The asset's
# sha256 comes from the GitHub release. After the swap the launchd job is restarted; if the
# engine does not come back at the new version, the previous install is put back.
#
# Overrides: SVOD_LAUNCHD_LABEL, SVOD_APP_API_PORT, SVOD_INSTALL_PATH (app-image dir, lib dir or
# binary), SVOD_UPDATE_FORCE=1 (reinstall the same version).
set -euo pipefail

REPO="FleetQ/svod-engine"
CONFIG_DIR="$HOME/.config/svod"
DISCOVERY="$CONFIG_DIR/engine.json"
INSTALLED_SCRIPT="$CONFIG_DIR/self-update.sh"
READY_TIMEOUT="${SVOD_UPDATE_READY_TIMEOUT:-180}"

log() { echo "[$(date '+%F %T')] $*"; }
die() { log "ERROR: $*" >&2; exit 1; }

[[ "$(uname -s)" == "Darwin" ]] || die "this script restarts the engine through launchd, so it runs on macOS only"

# launchd kills every process left in a job's process group when the job exits. Started by the
# engine, this script is in that group and would die the moment it restarts the engine — before
# it can check the new version or roll back. Move into a group of our own first.
if [[ -z "${SVOD_UPDATE_DETACHED:-}" ]]; then
  export SVOD_UPDATE_DETACHED=1
  exec /usr/bin/perl -e 'setpgrp(0, 0); exec @ARGV or die "exec: $!\n"' /bin/bash "$0" "$@"
fi

mkdir -p "$CONFIG_DIR"
LOCK="$CONFIG_DIR/self-update.lock"
/usr/bin/shlock -f "$LOCK" -p $$ || die "another self-update is running (lock $LOCK)"

TMP="$(mktemp -d)"
cleanup() { rm -rf "$TMP"; rm -f "$LOCK"; }
trap cleanup EXIT

# plutil reads JSON as well as plists; `raw` prints a scalar without quotes.
json_get() { plutil -extract "$2" raw -o - "$1" 2>/dev/null || true; }

# ---- 1. find the running engine ------------------------------------------------------------
PID="" PORT="" LABEL=""
if [[ -f "$DISCOVERY" ]]; then
  PID="$(json_get "$DISCOVERY" pid)"
  PORT="$(json_get "$DISCOVERY" appApiPort)"
  LABEL="$(json_get "$DISCOVERY" launchdLabel)"
fi
LABEL="${SVOD_LAUNCHD_LABEL:-$LABEL}"
PORT="${SVOD_APP_API_PORT:-${PORT:-7619}}"
if [[ -z "$LABEL" ]]; then
  LABEL="$(launchctl list | awk 'tolower($3) ~ /svod/ && tolower($3) ~ /engine/ {print $3; exit}')"
fi
[[ -n "$LABEL" ]] || die "no launchd job for the engine found; set SVOD_LAUNCHD_LABEL"
if [[ -z "$PID" ]] || ! kill -0 "$PID" 2>/dev/null; then
  PID="$(launchctl list "$LABEL" 2>/dev/null | sed -n 's/.*"PID" = \([0-9]*\);.*/\1/p')"
fi

running_version() {
  local f="$TMP/check.json"
  curl -fsS --max-time 20 "http://127.0.0.1:${PORT}/api/v1/update/check" -o "$f" 2>/dev/null || return 0
  json_get "$f" currentVersion
}
CURRENT="$(running_version)"
log "engine: label=$LABEL port=$PORT pid=${PID:-none} version=${CURRENT:-unknown}"

# ---- 2. work out the install layout --------------------------------------------------------
LAYOUT="" TARGET="${SVOD_INSTALL_PATH:-}" EXPLICIT_CP=""
if [[ -z "$TARGET" ]]; then
  [[ -n "$PID" ]] || die "the engine is not running, so its install can't be found; set SVOD_INSTALL_PATH"
  CMD="$(ps -o command= -p "$PID")"
  if [[ "$CMD" =~ ^(/.*SvodEngine\.app)/Contents/ ]]; then
    TARGET="${BASH_REMATCH[1]}"
  elif [[ "$CMD" == *" -cp "* || "$CMD" == *" -classpath "* ]]; then
    cp="${CMD#* -cp }"; [[ "$cp" == "$CMD" ]] && cp="${CMD#* -classpath }"
    first="${cp%%:*}"; first="${first%% dev.svod.engine.MainKt*}"; first="${first%% -*}"
    if [[ "$first" == */\* ]]; then TARGET="${first%/\*}"; else TARGET="$(dirname "$first")"; EXPLICIT_CP=1; fi
  else
    TARGET="$(lsof -a -p "$PID" -d txt -Fn 2>/dev/null | awk '/^n/ {print substr($0, 2); exit}')"
  fi
fi
if [[ -d "$TARGET" && "$TARGET" == *.app ]]; then LAYOUT=appimage
elif [[ -d "$TARGET" ]] && compgen -G "$TARGET/svod-engine-*.jar" >/dev/null; then LAYOUT=libdir
elif [[ -f "$TARGET" && -x "$TARGET" && "$(basename "$TARGET")" == svod-engine* ]]; then LAYOUT=native
else die "can't tell how the engine is installed (resolved '$TARGET'); set SVOD_INSTALL_PATH"
fi
log "install: $LAYOUT at $TARGET"

# installDist's bin/svod-engine lists every jar by name and version
# (CLASSPATH=$APP_HOME/lib/svod-engine-1.24.0.jar:…). After the jars are swapped it names files
# that are gone and the JVM stops at "Could not find or load main class dev.svod.engine.MainKt".
START=""
if [[ "$LAYOUT" == libdir ]]; then
  START="$(dirname "$TARGET")/bin/svod-engine"
  if [[ -f "$START" ]] && grep -q '^CLASSPATH=\$APP_HOME/lib/[^*]' "$START"; then :
  elif [[ -f "$START" ]] && grep -q '^CLASSPATH="\$APP_HOME/lib/\*"' "$START"; then START=""
  elif [[ -n "$EXPLICIT_CP" ]]; then
    die "the engine runs with a list of jar names and no installDist start script to fix next to $TARGET; start it with -cp '$TARGET/*' first"
  else START=""
  fi
fi

case "$(uname -m)" in
  arm64) PLATFORM=macos-arm64 ;;
  *) die "no release build for $(uname -m) macOS" ;;
esac
if [[ "$LAYOUT" == native ]]; then ASSET="svod-engine-$PLATFORM"; else ASSET="SvodEngine-$PLATFORM.tar.gz"; fi

# ---- 3. pick the release -------------------------------------------------------------------
TAG=""
if [[ "${2:-}" =~ /releases/download/([^/]+)/ ]]; then TAG="${BASH_REMATCH[1]}"
elif [[ -n "${1:-}" ]]; then TAG="v${1#v}"
fi
if [[ -n "$TAG" ]]; then API="https://api.github.com/repos/$REPO/releases/tags/$TAG"
else API="https://api.github.com/repos/$REPO/releases/latest"; fi
REL="$TMP/release.json"
curl -fsSL --retry 3 -H "Accept: application/vnd.github+json" -H "User-Agent: svod-self-update" "$API" -o "$REL" \
  || die "can't read the release from $API"
TAG="$(json_get "$REL" tag_name)"
VERSION="${TAG#v}"
[[ -n "$VERSION" ]] || die "release without a tag at $API"

URL="" SHA=""
i=0
while name="$(plutil -extract "assets.$i.name" raw -o - "$REL" 2>/dev/null)"; do
  if [[ "$name" == "$ASSET" ]]; then
    URL="$(json_get "$REL" "assets.$i.browser_download_url")"
    SHA="$(json_get "$REL" "assets.$i.digest")"; SHA="${SHA#sha256:}"
    break
  fi
  i=$((i + 1))
done
[[ -n "$URL" ]] || die "release $TAG has no $ASSET"
[[ -n "$SHA" ]] || die "release $TAG gives no sha256 for $ASSET; refusing to install it unverified"

if [[ -n "$CURRENT" ]]; then
  [[ "${CURRENT%%.*}" == "${VERSION%%.*}" ]] \
    || die "$CURRENT → $VERSION changes the major version (App API contract); update the app and engine together"
  if [[ "$CURRENT" == "$VERSION" && -z "${SVOD_UPDATE_FORCE:-}" ]]; then
    log "already at $VERSION"; exit 0
  fi
fi
log "updating ${CURRENT:-unknown} → $VERSION from $ASSET"

# ---- 4. download and verify ----------------------------------------------------------------
FILE="$TMP/$ASSET"
curl -fL --retry 3 --silent --show-error -o "$FILE" "$URL" || die "download failed: $URL"
actual="$(shasum -a 256 "$FILE" | awk '{print $1}')"
[[ "$actual" == "$SHA" ]] || die "sha256 mismatch for $ASSET (expected $SHA, got $actual)"
log "sha256 OK"

NEW=""
if [[ "$LAYOUT" != native ]]; then
  tar -xzf "$FILE" -C "$TMP"
  [[ -d "$TMP/SvodEngine.app" ]] || die "no SvodEngine.app inside $ASSET"
  NEW="$TMP/SvodEngine.app"
  if [[ "$LAYOUT" == libdir ]]; then
    NEW="$NEW/Contents/app"
    compgen -G "$NEW/svod-engine-*.jar" >/dev/null || die "no svod-engine jar inside $ASSET"
  fi
else
  chmod +x "$FILE"
  NEW="$FILE"
fi

# A lib-dir install runs on whatever java its launchd job names, and release jars are compiled for
# the Java the release was built with. Java 20 cannot load them (class file 65 = Java 21): the
# engine then dies on every start with UnsupportedClassVersionError until the rollback. Check first.
java_feature() {   # the running engine's Java feature version, e.g. 20
  "$1" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2; exit}'
}
class_feature() {  # the Java version a jar's MainKt was compiled for (class major - 44)
  local hex
  hex="$(unzip -p "$1" dev/svod/engine/MainKt.class 2>/dev/null | head -c 8 | xxd -p)"
  [[ ${#hex} -eq 16 ]] && echo $(( 16#${hex:12:4} - 44 ))
}
if [[ "$LAYOUT" == libdir && -n "$PID" ]]; then
  JAVA="$(ps -o comm= -p "$PID")"
  have="$( [[ -x "$JAVA" ]] && java_feature "$JAVA" )"
  need="$(class_feature "$(compgen -G "$NEW/svod-engine-*.jar" | head -1)")"
  if [[ -n "$have" && -n "$need" && "${have%%.*}" -lt "$need" ]]; then
    die "the engine runs on Java $have ($JAVA) but $VERSION needs Java $need; install Java $need and point the launchd job at it, then run this again. Nothing was changed."
  fi
  log "java: running $have, release needs ${need:-unknown}"
fi

# ---- 5. swap, keeping the previous install at <path>.old ----------------------------------
# The running engine keeps its already-open files, so replacing them under it is safe until the
# restart below.
BACKUP="${TARGET%/}.old"
rm -rf "$BACKUP"
case "$LAYOUT" in
  appimage)
    STAGED="$(dirname "$TARGET")/.SvodEngine.app.staged.$$"
    rm -rf "$STAGED"
    ditto "$NEW" "$STAGED"
    mv "$TARGET" "$BACKUP"
    mv "$STAGED" "$TARGET"
    ;;
  libdir)
    cp -Rp "$TARGET" "$BACKUP"
    rsync -a --delete --include='*.jar' --exclude='*' "$NEW/" "$TARGET/"
    if [[ -n "$START" ]]; then
      # lib/* works for the old jars as well, so this stays in place on a rollback.
      sed 's|^CLASSPATH=\$APP_HOME/lib/.*|CLASSPATH="$APP_HOME/lib/*"|' "$START" > "$START.new.$$"
      chmod "$(stat -f %Lp "$START")" "$START.new.$$"
      mv "$START.new.$$" "$START"
      log "start script now loads lib/*: $START"
    fi
    ;;
  native)
    cp -p "$TARGET" "$BACKUP"
    mv "$NEW" "$TARGET.new.$$"
    mv "$TARGET.new.$$" "$TARGET"
    ;;
esac
log "installed $VERSION; previous install kept at $BACKUP"

restore() {
  case "$LAYOUT" in
    appimage) rm -rf "$TARGET"; mv "$BACKUP" "$TARGET" ;;
    libdir)   rsync -a --delete "$BACKUP/" "$TARGET/" ;;
    native)   cp -p "$BACKUP" "$TARGET" ;;
  esac
}

# ---- 6. restart and check the version ------------------------------------------------------
log "restarting $LABEL"
launchctl kickstart -k "gui/$(id -u)/$LABEL" || die "launchctl kickstart failed for $LABEL"

deadline=$((SECONDS + READY_TIMEOUT))
now=""
while (( SECONDS < deadline )); do
  sleep 3
  now="$(running_version)"
  [[ "$now" == "$VERSION" ]] && break
done
if [[ "$now" != "$VERSION" ]]; then
  log "engine did not come back at $VERSION within ${READY_TIMEOUT}s (reports '${now:-nothing}'); rolling back"
  restore
  launchctl kickstart -k "gui/$(id -u)/$LABEL" || true
  back=""
  deadline=$((SECONDS + READY_TIMEOUT))
  while (( SECONDS < deadline )); do
    sleep 3
    back="$(running_version)"
    [[ -n "$back" ]] && break
  done
  if [[ -n "$back" ]]; then log "previous engine is running again (${back})"
  else log "previous engine did not answer within ${READY_TIMEOUT}s either; check: launchctl print gui/$(id -u)/$LABEL"
  fi
  die "update to $VERSION failed; previous install restored"
fi
log "engine is running $VERSION"

# ---- 7. keep this script in step with the engine it installed -----------------------------
# Replace by rename, never in place: bash reads a script while running it.
SRC="$0"
if curl -fsSL --retry 2 -o "$TMP/self-update.sh" "https://github.com/$REPO/releases/download/$TAG/self-update.sh" 2>/dev/null \
   && head -1 "$TMP/self-update.sh" | grep -q '^#!'; then
  SRC="$TMP/self-update.sh"
fi
if [[ "$SRC" != "$INSTALLED_SCRIPT" ]]; then
  cp "$SRC" "$INSTALLED_SCRIPT.tmp.$$" && chmod 0755 "$INSTALLED_SCRIPT.tmp.$$" && mv "$INSTALLED_SCRIPT.tmp.$$" "$INSTALLED_SCRIPT"
fi
log "self-update script: $INSTALLED_SCRIPT"
