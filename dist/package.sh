#!/usr/bin/env bash
# Build a self-contained app image for the Svod engine with jpackage + a jlink runtime.
# The native bits for embeddings (ONNX/DJL/tokenizers) are downloaded on first run, so the
# app image only bundles the JARs + a trimmed JDK runtime.
set -euo pipefail

DIST_DIR="$(cd "$(dirname "$0")" && pwd)"
ENGINE_DIR="$(cd "$DIST_DIR/../engine" && pwd)"
OUT="$DIST_DIR/build"
VERSION="1.2.4"
BUNDLE_VERSION="1.2.4"   # macOS CFBundleVersion must not start with 0
NAME="SvodEngine"
MAIN_JAR="svod-engine-$VERSION.jar"
MAIN_CLASS="dev.svod.engine.MainKt"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 20)}"
JAVA="$JAVA_HOME/bin"

echo "==> gradle installDist"
( cd "$ENGINE_DIR" && ./gradlew installDist -q )
LIB="$ENGINE_DIR/build/install/svod-engine/lib"
[ -f "$LIB/$MAIN_JAR" ] || { echo "main jar not found: $LIB/$MAIN_JAR" >&2; exit 1; }

echo "==> jlink runtime (jdeps-derived modules + curated extras)"
DEPS="$("$JAVA/jdeps" --print-module-deps --ignore-missing-deps --multi-release 20 \
          --recursive --class-path "$LIB/*" "$LIB/$MAIN_JAR" 2>/dev/null || echo java.base)"
# Curated extras: TLS/crypto, reflection-loaded and locale modules the classpath app needs.
MODULES="$DEPS,java.base,java.net.http,java.naming,java.management,java.security.jgss,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,java.logging,jdk.localedata"
rm -rf "$OUT/runtime"
mkdir -p "$OUT"
"$JAVA/jlink" --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages \
  --compress=2 --output "$OUT/runtime"

# Opt-in macOS code signing. If SVOD_SIGN_IDENTITY is set, jpackage signs the app
# image with that Developer ID; if unset, the build is unsigned (local-dev default).
# Optionally pin a keychain via SVOD_SIGN_KEYCHAIN.
SIGN_ARGS=()
if [ -n "${SVOD_SIGN_IDENTITY:-}" ]; then
  echo "==> signing enabled (identity: $SVOD_SIGN_IDENTITY)"
  SIGN_ARGS+=(--mac-sign --mac-signing-identity "$SVOD_SIGN_IDENTITY")
  if [ -n "${SVOD_SIGN_KEYCHAIN:-}" ]; then
    SIGN_ARGS+=(--mac-signing-keychain "$SVOD_SIGN_KEYCHAIN")
  fi
else
  echo "==> signing disabled (set SVOD_SIGN_IDENTITY to sign)"
fi

echo "==> jpackage app-image"
rm -rf "$OUT/$NAME.app"
"$JAVA/jpackage" \
  --type app-image \
  --name "$NAME" \
  --app-version "$BUNDLE_VERSION" \
  --input "$LIB" \
  --main-jar "$MAIN_JAR" \
  --main-class "$MAIN_CLASS" \
  --runtime-image "$OUT/runtime" \
  --java-options "-Dfile.encoding=UTF-8" \
  ${SIGN_ARGS[@]+"${SIGN_ARGS[@]}"} \
  --dest "$OUT"

echo "==> built $OUT/$NAME.app"
echo "    run: $OUT/$NAME.app/Contents/MacOS/$NAME /path/to/config.json"
