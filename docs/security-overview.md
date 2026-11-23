# Security and data-handling overview

A one-page summary for security reviews and vendor questionnaires. It covers the engine in this
repository (`svod-engine`). Every claim links to the code that implements it, and
[`SecurityOverviewDocsTest`](../engine/src/test/kotlin/dev/svod/engine/security/SecurityOverviewDocsTest.kt)
checks the defaults, paths and rules quoted here against that code, so CI fails if the page drifts.

Svod is software you run yourself. It is not a hosted service, and no Svod operator receives your data.

## At a glance

| Question | Answer |
|---|---|
| Where is data stored? | On the machine running the engine: plain Markdown files in a local git repository per vault, plus a derived index under the vault's `.svod/`. |
| Is data encrypted at rest? | No, not by Svod. Use full-disk or volume encryption (see [Known gaps](#known-gaps)). |
| Is data encrypted in transit? | Loopback-only by default. A shared engine must enable TLS on both the App API and MCP before it may bind off loopback. |
| How do users authenticate? | People: personal API keys (`svk_…`, 256 random bits). Agents: one MCP bearer token per agent. |
| Role-based access? | Yes. Per-vault grants: `READER` / `EDITOR` for people, plus engine admin. Agents get per-vault roles. |
| SSO / SAML / OIDC? | No. |
| Audit logging? | Yes. Append-only JSON-lines logs for agent actions and for keyed App API requests, plus git authorship on every commit. |
| Deletion? | Soft delete to `.trash/`, restore, and admin purge. Purged content remains in git history. |
| Telemetry? | None in the engine. |
| Third-party data processors? | None by default. Only remote embedders, rerankers or sync/backup remotes that the operator configures. |
| Certifications? | None (no SOC 2, ISO 27001 or HIPAA attestation). |

## Data location

- Notes are plain Markdown files in a git repository, one per vault, at the path set in the
  config (`vaults[].path`, or the legacy `vaultPath`), see
  [`SvodConfig.kt`](../engine/src/main/kotlin/dev/svod/engine/lifecycle/SvodConfig.kt). Every
  change is a git commit ([`core/SvodEngine.kt`](../engine/src/main/kotlin/dev/svod/engine/core/SvodEngine.kt),
  [`core/GitRepo.kt`](../engine/src/main/kotlin/dev/svod/engine/core/GitRepo.kt)).
- The search index (Lucene), the link/graph sidecars and the agent audit log are derived data under
  the vault's `.svod/` directory, which is never committed
  ([`core/VaultPath.kt`](../engine/src/main/kotlin/dev/svod/engine/core/VaultPath.kt) reserves
  `.git`, `.svod` and `.trash`).
- The default embedder is `onnx-local` (`EmbedderSettings.provider`): the model runs in-process
  ([`index/OnnxLocalEmbedder.kt`](../engine/src/main/kotlin/dev/svod/engine/index/OnnxLocalEmbedder.kt)).
  Note text leaves the machine only if the operator configures a `remote-openai` embedder
  ([`index/OpenAiEmbedder.kt`](../engine/src/main/kotlin/dev/svod/engine/index/OpenAiEmbedder.kt)),
  a remote reranker ([`index/RemoteReranker.kt`](../engine/src/main/kotlin/dev/svod/engine/index/RemoteReranker.kt)),
  or sync/backup remotes ([`sync/`](../engine/src/main/kotlin/dev/svod/engine/sync/)).

## Network exposure

- By default the engine binds to `127.0.0.1`: the App API on port `7517` and MCP on port `7518`
  ([`SvodConfig.kt`](../engine/src/main/kotlin/dev/svod/engine/lifecycle/SvodConfig.kt)).
- To bind any other address, `SvodConfig.validate()` refuses to start unless **all** of these are
  set: `appApiTls` (HTTPS for the App API), `mcpTls` (HTTPS for MCP), at least one entry in
  `users`, and `localAdmin` set to false (ADR-0019,
  [`docs/adr/`](adr/)). There is no plain-HTTP mode off loopback.
- The keyless loopback admin (`localAdmin`) is accepted only when the peer address is loopback,
  the `Host` header names a loopback host and there is no foreign `Origin`. This blocks DNS
  rebinding and cross-origin requests or WebSockets from a browser on the same machine
  ([`api/AppApiAuth.kt`](../engine/src/main/kotlin/dev/svod/engine/api/AppApiAuth.kt)). No CORS
  is enabled.
- `/health` and `/ready` are unauthenticated by design and return no vault data. `/metrics`
  lists vaults, so on a shared engine it requires an admin key.

## Authentication and access control

- **Agents (MCP).** Each agent has its own bearer token (`agents[].token`), so one agent can be
  revoked without affecting others, at runtime through `/api/v1/agents`
  ([`lifecycle/AgentController.kt`](../engine/src/main/kotlin/dev/svod/engine/lifecycle/AgentController.kt)).
  Tokens may be given as secret references, and token comparison is constant-time
  ([`mcp/Agent.kt`](../engine/src/main/kotlin/dev/svod/engine/mcp/Agent.kt)). Each agent is
  granted specific vaults with a role per vault (ADR-0020).
- **People (App API).** Personal keys are `svk_`-prefixed, 256 random bits from `SecureRandom`
  ([`lifecycle/UserController.kt`](../engine/src/main/kotlin/dev/svod/engine/lifecycle/UserController.kt)).
  The config holds only a reference to the key: `env:`, `file:` or `keychain:`.
  `SvodConfig.validate()` rejects a key written inline. Keys are compared in constant time
  ([`api/Principal.kt`](../engine/src/main/kotlin/dev/svod/engine/api/Principal.kt)), and revoking or
  rotating a key takes effect immediately.
- **Roles.** A person has per-vault grants of `READER` or `EDITOR`, and may be an engine admin.
  Admin-only routes (users, agents, vaults, backup, settings and so on) return 403 to non-admins. A
  vault the caller has no grant for returns 404, the same as an unknown vault, so vault names
  cannot be enumerated ([`api/AppApiAuth.kt`](../engine/src/main/kotlin/dev/svod/engine/api/AppApiAuth.kt)).
- **Local single-user mode.** `localAdmin` (default `true`) lets a keyless request from the same
  machine act as the admin, so a desktop install works without keys. A shared engine must set it
  to `false`.
- Keys and tokens are never logged. Refusals are logged with the peer address and reason, not the key.

## Secrets

- Secret config values (agent tokens, TLS keystore passwords, remote API keys) can be given as
  references resolved at runtime: `env:NAME`, `file:/path` or `keychain:name` (macOS)
  ([`security/Secrets.kt`](../engine/src/main/kotlin/dev/svod/engine/security/Secrets.kt)).
  These fields also accept a literal value, so keep the config file owner-only. Personal API
  keys are the exception: they must be references.
- Secret files the engine writes itself are created owner-only (0600)
  ([`security/SecretFiles.kt`](../engine/src/main/kotlin/dev/svod/engine/security/SecretFiles.kt),
  [`lifecycle/SecretStore.kt`](../engine/src/main/kotlin/dev/svod/engine/lifecycle/SecretStore.kt)).
- A new personal key is shown once, when it is created. It is never returned by the API again.

## Pre-commit secret scan

- The scan is **opt-in**: `secretScanning` is off by default. Enable it in the config to use it.
- When it is enabled, [`security/SecretScanner.kt`](../engine/src/main/kotlin/dev/svod/engine/security/SecretScanner.kt)
  blocks a write *before* the commit if the note contains a real private key (key material, not
  just a quoted `BEGIN` line) or a high-confidence token pattern (AWS access key id, GitHub,
  Slack and Google API tokens, JWTs, `api_key = "…"` assignments).
- Imported text files that trip the scanner are skipped, and incoming synced files are
  quarantined as conflicts rather than written
  ([`sync/SyncEngine.kt`](../engine/src/main/kotlin/dev/svod/engine/sync/SyncEngine.kt)).
- The rules are tuned for few false positives, so they are not a complete DLP control.

## Audit trail

- **Agents.** Every MCP tool call is appended as one JSON line to `.svod/audit/audit.log` inside
  each vault, created owner-only ([`mcp/AuditLog.kt`](../engine/src/main/kotlin/dev/svod/engine/mcp/AuditLog.kt),
  wired in [`lifecycle/SvodNode.kt`](../engine/src/main/kotlin/dev/svod/engine/lifecycle/SvodNode.kt)).
- **People.** Every keyed App API request, and every refusal, is one JSON line in
  `audit-api.log` in the config directory. The file is created 0600 and never contains request
  bodies, query strings (except the `vault` parameter) or `Authorization` values
  ([`api/ApiAuditLog.kt`](../engine/src/main/kotlin/dev/svod/engine/api/ApiAuditLog.kt)).
- **Content history.** Every change is a git commit authored by the person or agent who made it.
  An agent acting for a person adds a `Svod-Agent:` trailer.
- Not audited: keyless loopback requests on a single-user engine (the local UI).

## Deletion and retention

- Delete is a soft delete: a commit that moves the note under `.trash/`. Restore moves it back.
- An admin **purge** removes a trashed note from the working tree in a `purge:` commit
  (`doPurge` in [`core/SvodEngine.kt`](../engine/src/main/kotlin/dev/svod/engine/core/SvodEngine.kt)).
- **Purged content stays in git history** and in any sync or backup remotes. Erasing it fully
  (for example for a GDPR erasure request) means rewriting git history outside Svod and in every
  remote.
- There is no automatic retention period or expiry for notes or logs.

## Outbound connections

The engine makes no outbound connections except these:

- **Model download.** On first run, the local embedding model (and the reranker model, if
  enabled) is downloaded over HTTPS
  ([`index/ModelManager.kt`](../engine/src/main/kotlin/dev/svod/engine/index/ModelManager.kt)).
- **Update check.** When an update check is requested, the engine asks GitHub releases for the
  latest version and the release's published SHA-256 checksum
  ([`lifecycle/UpdateService.kt`](../engine/src/main/kotlin/dev/svod/engine/lifecycle/UpdateService.kt)).
- **Operator-configured services only.** Remote embedders and rerankers, and sync or backup git
  remotes. With `denyPrivateRemotes` on (for hosted engines) a remote must be an HTTPS URL naming
  a public host, and the connection checks the address it reached
  ([`sync/RemoteGuard.kt`](../engine/src/main/kotlin/dev/svod/engine/sync/RemoteGuard.kt)).
- **No telemetry.** The engine sends no usage or analytics data. This statement covers the
  engine in this repository; check separately distributed clients on their own terms.

## Known gaps

- **No encryption at rest** by Svod. Rely on full-disk or volume encryption on the host.
- **No SSO** (SAML, OIDC) and no SCIM provisioning. People authenticate with personal keys.
- **No SOC 2, ISO 27001 or HIPAA attestation.** Svod is software you run yourself, not a hosted
  service.
- **No audit log rotation or retention.** The audit log files grow until the operator rotates them.
- **Purge does not rewrite git history** (see [Deletion and retention](#deletion-and-retention)).
- **No key or token expiry.** Keys stay valid until revoked or rotated.

## Reporting a vulnerability

See [`SECURITY.md`](../SECURITY.md) for how to report a vulnerability privately and which versions
receive fixes.
