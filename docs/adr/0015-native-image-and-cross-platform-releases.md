# ADR-0015 — GraalVM native-image + cross-platform releases

- Status: **Accepted**
- Date: 2026-06-13
- Scope: The deferred "2nd packaging iteration" — a native binary, plus downloadable releases for
  macOS / Linux / Windows on GitHub.

## Decisions

### 1. native-image via the GraalVM Gradle plugin
`org.graalvm.buildtools.native` adds `./gradlew nativeCompile` → a self-contained `svod-engine`
binary (no JVM, ~10–50 ms cold start, small footprint) — the win for a launchd-style daemon the UI
wakes on demand. Config: `--no-fallback`, community reachability metadata
(`metadataRepository`), `IncludeResources=logback.xml`, and `--initialize-at-run-time` for the
reflective/native-lib libraries (jgit, lucene, netty, djl, onnxruntime) so the closed-world
analysis defers their init.

### 2. Cross-platform releases require a CI matrix (native-image is host-only)
`native-image` is **not** a cross-compiler — it emits a binary for the build host only. So the
three-platform release is a **GitHub Actions matrix** (`macos-14`, `ubuntu-latest`,
`windows-latest`) in `.github/workflows/release.yml`, triggered by a `v*` tag, each runner building
its own platform's artifacts and uploading them to one GitHub Release. A `-rc`/`-beta` tag → a
prerelease.

### 3. Two flavours per OS — native (best-effort) + app-image (reliable)
Each runner builds **both**:
- **native binary** (`svod-engine-<os>`): the requested artifact; tiny + instant. Marked
  `continue-on-error` because native-image with this stack is metadata-sensitive — a native failure
  must not block the release.
- **jpackage app-image** (`SvodEngine-<os>.tar.gz`/`.zip`): a self-contained JVM bundle; larger but
  **reliable and full-featured**, the guaranteed-working download and the safety net while
  native-image's reachability metadata is iterated against CI.

### 4. native serves BM25 / Ollama; onnx-local stays JVM
DJL + ONNX Runtime download native libraries and reflect at runtime — the hardest case for a
closed-world native image. A native binary therefore serves the `none` (BM25) and `ollama`
embedders; **in-process `onnx-local` semantic search uses the app-image** (JVM). Documented in the
README download table, not hidden.

### 5. Toolchain: JDK 20 local, GraalVM 21 in CI
GraalVM has no JDK 20 build, so `kotlin.jvmToolchain` reads `-Psvod.jdk` (default 20 for local dev,
`21` in CI) — one GraalVM 21 then serves compile + jpackage + native-image. Gradle `version` bumped
`0.1.0 → 0.4.0` to align artifact names with the product/contract version.

## Consequences / caveats

- The first real native build happens **in CI** (no local GraalVM); expect 1–2 iterations on
  reachability metadata before `nativeCompile` is green. The app-image release succeeds meanwhile.
- macOS release binaries are signed + notarized when a signing identity/notary profile is
  configured (ADR-0013); unsigned local builds need a one-time `xattr -dr com.apple.quarantine`.

## Alternatives considered
- **native-only releases** — rejected: a metadata failure would leave no downloadable build. The
  app-image fallback guarantees a working release.
- **Cross-compiling from one host** — impossible for native-image; the matrix is mandatory.
- **distZip (needs a user JRE)** — poorer UX than a self-contained app-image; rejected.
