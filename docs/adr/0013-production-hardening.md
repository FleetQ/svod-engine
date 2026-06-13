# ADR-0013 — Production hardening: CI, observability, backup/DR, scale validation

- Status: **Accepted**
- Date: 2026-06-13
- Scope: Making the engine production-ready (CI gating, structured logging + crash reporting,
  off-site backup, release signing, measured scale limits). Built as a parallel agent team plus a
  sequential observability pass.

## Decisions

### 1. CI gates every change
A GitHub Actions workflow (`.github/workflows/ci.yml`, job `engine-tests`, macos-14, Temurin JDK
20) runs the full suite on every push/PR to `main` and uploads the test report. Merges are no
longer ungated. ONNX/Ollama tests auto-skip without their backends, so CI needs no special setup.

### 2. Structured logging (logback) + Sentry crash reporting
The logging backend moves from `slf4j-simple` to **logback-classic** with `engine/src/main/
resources/logback.xml`: console (captured by launchd) + a rolling file
(`$SVOD_LOG_DIR`, default `~/.svod/logs`, size+time rotation, 14-day/500MB cap) + an
`io.sentry:sentry-logback` appender that reports **ERROR** logs to Sentry when `SENTRY_DSN` is set
(a no-op otherwise — local/dev needs no Sentry). A `logback-test.xml` keeps tests console-only
(no files, no Sentry). Operational `System.err.println` sites (sync failures, the multi-grant
agent warning) now go through slf4j; the CLI startup banner stays on stdout by design.

### 3. Off-site backup / disaster recovery
`BackupService` pushes every vault's canonical branch to a configured backup remote under
`refs/svod/backup/<vaultId>` (force, mirror semantics; its own read-only jgit handle per vault, so
it never races the writer). The remote is resolved through `Secrets` only at push time —
credentials never inline, and a remote that embeds a password is rejected at config-validate, at
`PUT /api/v1/settings/backup`, and in every DTO that surfaces a remote (redacted). New ops surface:
`POST /api/v1/backup/now`, `GET /api/v1/sync/config`, `PUT /api/v1/settings/backup`,
`POST /api/v1/maintenance/reindex`, `POST /api/v1/sync/now` (501 when sync isn't configured).
Contract `0.3.0 → 0.4.0` (additive).

### 4. Backup config persists across restart
A runtime-set backup remote (`PUT /settings/backup`) is persisted to `<default-vault>/.svod/
backup.json` (`BackupConfigStore`) and **takes precedence over the startup config on the next
boot**, so a UI-configured backup survives a restart. `GET /sync/config` reports the live
(persisted/runtime) config, not just the startup file. (This salvaged the one genuinely additive
piece — persistence — from a duplicate parallel effort; the duplicate's endpoint implementation
was discarded in favor of the committed, tested one.)

### 5. Release signing + notarization (opt-in)
`dist/package.sh` signs the app image when `SVOD_SIGN_IDENTITY` is set (`--mac-sign …`), else builds
unsigned as before (local dev unaffected). `dist/notarize.sh` notarizes via `notarytool
--keychain-profile` + `stapler` — entirely env-driven, no Apple credentials in the repo. Required
because an unsigned jpackage app won't launch past Gatekeeper on other Macs.

### 6. Scale validated, one real bottleneck found
A gated (`-Dsvod.perf=true`) large-vault test measured 5,000 notes (BM25): search **p50 0.62 ms /
p95 3.89 ms** (≈50× under budget), heap **12.8 MB** (flat, no leak), clean write-actor
back-pressure (64 concurrent writers, drained to 0). **Bottleneck:** per-write git commit
throughput degrades super-linearly (15.9 → 6.1 notes/sec at 5k), so a 50k-note import ≈ 2.3 h. See
`docs/perf-report.md`. Build forwards the perf flags to the test JVM (previously the test silently
skipped).

### 7. Batch-commit import (fixes the bottleneck from #6)
`SvodEngine.writeBatch(entries, author, message)` writes many files atomically through the single
writer and seals them with **one** git commit (idempotent + non-clobbering like the per-file path:
identical ⇒ unchanged, different ⇒ skipped, secret-laden text ⇒ skipped). `ObsidianImport` now
imports in chunks of 512 (one commit per chunk — bounds memory, collapses tens of thousands of
commits to a few). Measured: **6.1 → 77.7 notes/sec** at 5,000 notes (~12.7×); a 50k-note import
drops from ≈2.3 h to ≈11 min. Single-writer integrity is unchanged (still one actor submission per
batch). The remaining floor is the per-file fsync of atomic writes (durability), not the commit.

## Deliberately NOT done (documented, not hidden)

- **Encryption-at-rest** — confirmed out of scope for this deployment (relies on disk encryption).
- **Per-vault backup remotes** — backup is one global remote today; a per-vault remote (each
  environment to its own server) is a natural follow-up.
- **License** — `Apache-2.0` is still "proposed, pending owner confirmation" (owner decision).
- **Per-request MCP vault selection** — a multi-grant agent still binds to its first vault
  (warned at startup), per ADR-0011.
