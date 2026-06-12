# ADR-0010 — Hardening: secret scanning, observability, TLS/secret store, Obsidian import

- Status: **Accepted**
- Date: 2026-06-12
- Scope: Build step 8 (security, observability, migration — the final step)

## Decisions

### 1. Secret scanning before commit
`SecretScanner` checks note content for **high-confidence** secrets (private keys, AWS
`AKIA…`, GitHub/Slack/Google tokens, JWTs, key-like assignments) on the write path. A hit
returns `WriteOutcome.Blocked` — the write never reaches git, so a leaked credential can't
become permanent history. Rules are conservative to keep a prose KB free of false alarms;
off by default, enabled via `config.secretScanning`. MCP maps it to a `blocked` error,
the App API to `422`.

### 2. Observability metrics at `/api/v1/metrics`
Live, lock-free: **write latency** (count/avg/max/last ms, timed in the engine), **write-actor
queue depth + peak** (back-pressure), **index lag** (HEAD vs indexed head), **conflict count**,
and **sync status** (role + last result). Added to the contract and validated by the contract
test.

### 3. TLS for the MCP endpoint
Remote agents reach MCP over **HTTPS**. The MCP server runs on **Netty** (Ktor's CIO server
engine does not serve TLS) with an `sslConnector` from a configured keystore. The App API
stays CIO/loopback (no TLS needed — invariant 7). *(Proven: an MCP client completes a tool
call over a real TLS handshake.)*

### 4. Secrets out of plaintext config
`Secrets.resolve` reads `env:NAME` / `file:/path` references (else literal), used for agent
tokens and keystore passwords — so credentials live in the environment or a file, not the
config. A macOS `keychain:` provider plugs in here from `dist/`, keeping the engine
OS-agnostic.

### 5. Obsidian import (zero lock-in)
Because Svod's source of truth *is* markdown + YAML frontmatter, `ObsidianImport` is a
faithful copy through the normal write path: frontmatter and `[[wikilinks]]` preserved
verbatim, every file an attributed commit, `.obsidian/` and dot-dirs skipped. Export is just
the git tree — nothing to extract.

### 6. Full test suite
The whole suite (89 tests) runs green: integrity/concurrency/crash, hybrid index +
ONNX/Ollama, MCP (HTTP + TLS), App API contract conformance + events + watcher, link-integrity,
lifecycle, sync (frontmatter merge + replication), and the hardening features above.

### 7. macOS Keychain token provider
`Secrets` resolves `keychain:[service/]account` via the macOS `security` CLI (service defaults
to `svod`), so agent tokens / keystore passwords can live in the Keychain. macOS-only by
nature; proven by a real add → read → delete round-trip test (skipped off macOS).

### 8. Packaging — jpackage + jlink app image
`dist/package.sh` builds a self-contained app image: `installDist` → a **jlink** runtime
(jdeps-derived modules + curated TLS/crypto/locale extras) → **jpackage** `--type app-image`.
The packaged `SvodEngine.app` launches and serves `/ready` 200. (Native embedding libs are
fetched on first run, so the image stays small.) GraalVM `native-image` remains the planned
2nd iteration.

## Deferred (documented, not hidden)

- **Encryption-at-rest** — out of scope for now (spec marks it optional); the vault relies on
  filesystem/disk encryption. The git-backed model is unchanged when added.
- **GraalVM native-image** — the planned 2nd packaging iteration (jpackage+jlink ships now).
- **macOS SwiftUI client** — its own personal repo (`FleetQ/svod-ui-macos`), next session.
- **Reverse-proxy TLS** — terminating TLS at an nginx/caddy front (MCP on loopback) remains a
  valid alternative to native TLS; both are supported.

## Alternatives considered

- **Aggressive secret regexes (high-entropy heuristics)** — too many false positives on prose;
  rejected for high-confidence structural patterns.
- **Serve MCP TLS on CIO** — unsupported by the engine; Netty is the minimal change. (Jetty
  would also work.)
- **Tokens in plaintext config** — convenient but leaky; rejected for secret refs.
