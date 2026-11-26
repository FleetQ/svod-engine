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
| Is data encrypted at rest? | Not by the engine itself; supported through host volume encryption with a key you hold, see [Encryption at rest](#encryption-at-rest) / [ADR-0021](adr/0021-encryption-at-rest.md). |
| Is data encrypted in transit? | Loopback-only by default. A shared engine must enable TLS on both the App API and MCP before it may bind off loopback. |
| How do users authenticate? | People: personal API keys (`svk_…`, 256 random bits). Agents: one MCP bearer token per agent. |
| Role-based access? | Yes. Per-vault grants: `READER` / `EDITOR` for people, plus engine admin. Agents get per-vault roles. |
| SSO / SAML / OIDC? | No. |
| Audit logging? | Yes. Append-only JSON-lines logs for agent writes and refused agent calls and for keyed App API requests, plus git authorship on every commit. Successful agent reads and searches are not logged. |
| Deletion? | Soft delete to `.trash/`, restore, and admin purge. Purged content remains in git history. |
| Telemetry? | No usage or analytics telemetry. Opt-in error reporting: ERROR log lines go to Sentry only when the operator sets the `SENTRY_DSN` environment variable. |
| Third-party data processors? | None by default. Note text goes only to endpoints the operator configures: an embedder (Ollama or OpenAI-compatible), a remote reranker, a graph-summary or memory-extraction LLM (Ollama), and sync/backup git remotes. Error logs go to Sentry only if `SENTRY_DSN` is set. See [Outbound connections](#outbound-connections). |
| Certifications? | None (no SOC 2, ISO 27001 or HIPAA attestation). |

## Data location

- Notes are plain Markdown files in a git repository, one per vault, at the path set in the
  config (`vaults[].path`, or the legacy `vaultPath`), see
  [`SvodConfig.kt`](../engine/src/main/kotlin/dev/svod/engine/lifecycle/SvodConfig.kt). Every
  change is a git commit ([`core/SvodEngine.kt`](../engine/src/main/kotlin/dev/svod/engine/core/SvodEngine.kt),
  [`core/GitRepo.kt`](../engine/src/main/kotlin/dev/svod/engine/core/GitRepo.kt)).
- The search index (Lucene) and the link/graph sidecars are derived data, and the agent audit log
  is not (it cannot be rebuilt, so keep it when you delete the index). All of them live under
  the vault's `.svod/` directory, which is never committed
  ([`core/VaultPath.kt`](../engine/src/main/kotlin/dev/svod/engine/core/VaultPath.kt) reserves
  `.git`, `.svod` and `.trash`).
- Engine logs go to the console and to rolling files in `$SVOD_LOG_DIR` (default
  `~/.svod/logs`), kept for 14 days and capped at 500 MB
  ([`logback.xml`](../engine/src/main/resources/logback.xml)). Log lines can contain note paths,
  vault names and exception text, so protect that directory like the vault.
- The default embedder is `onnx-local` (`EmbedderSettings.provider`): the model runs in-process
  ([`index/OnnxLocalEmbedder.kt`](../engine/src/main/kotlin/dev/svod/engine/index/OnnxLocalEmbedder.kt)).
  With the default settings, note text does not leave the machine. It can be sent to another
  host only through a service the operator configures: an Ollama or OpenAI-compatible embedder,
  a remote reranker, an Ollama graph-summary or memory-extraction LLM, or a sync/backup git
  remote. The full list, with the config keys, is under
  [Outbound connections](#outbound-connections).

## Encryption at rest

The engine does not encrypt data itself. The supported setup is host volume or full-disk
encryption whose key you hold and control: LUKS2 / dm-crypt on Linux servers, FileVault on macOS,
BitLocker on Windows ([ADR-0021](adr/0021-encryption-at-rest.md); for a Debian server see the
[central-instance runbook](runbook-central-instance.md#5a-encryption-at-rest)). The key stays with
you: Svod never sees, stores or needs it, because the engine only reads and writes files on a
volume the operating system has already unlocked.

Put **all** of these on the encrypted volume. `SecurityOverviewDocsTest` reads the fixed paths,
the `.svod/` files and the path environment variables from the code and fails CI when this list
misses one. It cannot follow paths that come from configuration at runtime (vaults, external
sources, git remotes), so those are named here by the setting that holds them.

- **Each vault**, at `vaults[].path` (or the legacy `vaultPath`). Vaults created through the API
  without a path go next to the first vault, or under `~/.svod/vaults` when there is none. A
  config-less start uses `~/svod-vault`. Inside each vault:
  - the notes and `.trash/`, and the git history in `.git/`;
  - `.svod/`, which holds the plain-text note content in another form: the search index
    `.svod/index` (note text, stored fields, embedding vectors), the graph sidecar `.svod/graph`,
    the agent audit log under `.svod/audit`, the legacy model copy `.svod/models`, the
    external-source registry and manifests `.svod/sources.json` and `.svod/source-manifests`,
    the memory inbox `.svod/recall-proposals.json` and `.svod/recall-distill.json`, and
    `.svod/backup.json`, `.svod/sync-resolutions.json` and the vault lock `.svod/lock`.
- **Every external source folder** (ADR-0016): a folder registered as an external source of a
  vault can be any absolute path. Svod imports the notes from it, and with `writeBack` on it
  writes vault edits back into it. Keep those folders on the encrypted volume too.
- **Local git remotes**: a sync or backup remote that is a path on this machine (a shared bare
  repository, for example) holds the full git history and belongs on the volume as well.
- **The config directory** (the folder holding the config file; `svod-engine init` defaults to
  `~/svod`): the config itself, the key and token files in `secrets/`, the App API audit log
  `audit-api.log` and `user-activity.json`. A start without a config file uses `~/.svod` as that
  directory instead: secrets in `~/.svod/secrets`, plus `~/.svod/audit-api.log` and
  `~/.svod/user-activity.json`.
- **Logs**: `$SVOD_LOG_DIR`, default `~/.svod/logs`.
- **Files under the service user's home**, mostly in `~/.config/svod`: the discovery file
  `~/.config/svod/engine.json` (or wherever `$SVOD_DISCOVERY_FILE` points), the
  host id `~/.config/svod/host-id`, the self-update log `~/.config/svod/self-update.log`, and the
  downloaded model cache `~/.cache/svod-models` (model weights only, no note data).

The simplest layout is one encrypted volume holding the vaults, the config directory and the
service user's home, as in the runbook.

**Not covered by the volume.** Data that leaves the machine is outside it: sync and backup git
remotes receive the full git history, and Sentry receives ERROR log lines when `SENTRY_DSN` is set
(see [Outbound connections](#outbound-connections)). Encrypt those on their own terms, for example
an encrypted disk on the remote git host. Volume encryption protects a powered-off or stolen disk,
not a running, unlocked host.

## Network exposure

- By default the engine binds to `127.0.0.1`: the App API on port `7517` and MCP on port `7518`
  ([`SvodConfig.kt`](../engine/src/main/kotlin/dev/svod/engine/lifecycle/SvodConfig.kt)).
- To bind any other address, `SvodConfig.validate()` refuses to start unless **all** of these are
  set: `appApiTls` (HTTPS for the App API), `mcpTls` (HTTPS for MCP), at least one entry in
  `users` with `admin` set to true, and `localAdmin` set to false (ADR-0019,
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
  A config written by `svod-engine init`, and the sample configs in `dist/`, already turn it on.
- When it is enabled, [`security/SecretScanner.kt`](../engine/src/main/kotlin/dev/svod/engine/security/SecretScanner.kt)
  blocks a **note write** *before* the commit if the note contains a real private key (key
  material, not just a quoted `BEGIN` line) or a high-confidence token pattern (AWS access key
  id, GitHub, Slack and Google API tokens, JWTs, `api_key = "…"` assignments).
- **Not scanned, even when the scan is on:**
  - **Session transcripts.** `POST /api/v1/memory/capture` stores raw session transcripts
    verbatim and commits them to git without scanning, by design: transcripts routinely contain
    tokens, and the capture store keeps them out of recall instead of blocking them.
    `/api/v1/memory/sessions/mark-distilled` rewrites those notes the same way. A token in a
    captured transcript therefore **does** enter git history. See
    [`api/AppApiServer.kt`](../engine/src/main/kotlin/dev/svod/engine/api/AppApiServer.kt) and
    `writeBytes` in [`core/SvodEngine.kt`](../engine/src/main/kotlin/dev/svod/engine/core/SvodEngine.kt).
  - **Binary files.** Attachments and other non-Markdown files written through `writeBytes` or
    imported as binary entries are stored without scanning.
- Imported text files that trip the scanner are skipped, and incoming synced files are
  quarantined as conflicts rather than written
  ([`sync/SyncEngine.kt`](../engine/src/main/kotlin/dev/svod/engine/sync/SyncEngine.kt)).
- The rules are tuned for few false positives, so they are not a complete DLP control.

## Personal data review

- The review is **opt-in**: `personalDataScanning` is off by default, and `svod-engine init` does
  not turn it on. Enable it in the config to use it.
- When it is enabled, [`security/PersonalDataScanner.kt`](../engine/src/main/kotlin/dev/svod/engine/security/PersonalDataScanner.kt)
  looks for email addresses, international phone numbers written with a `+` prefix, IBANs that pass
  the mod-97 check and card numbers that pass the Luhn check.
- It applies only to an **agent's** MCP `write`, `edit` and `remember`, and only to personal data
  the call **adds** (data the note already held does not count). The App API, sync, import and
  session capture are not scanned, and neither is a person's approval in the review queue.
- A matching note is not blocked: it is committed with `status: provisional`, so it **does** enter
  git history, but it is hidden from search and `context_pack` and waits in the Memory review
  queue until a person approves it. For `remember` this overrides any `status` the agent asked for.
  While a note is `provisional`, an agent's `write` or `edit` that keeps the personal data in it
  keeps it `provisional`, even if the agent sets another `status`.
- Frontmatter is updated in place; other keys and the body are kept. Frontmatter that does not
  parse is kept as it was, below a new header.
- It is **not a security boundary**. The review queue only controls what recall shows by default:
  an agent with write access can still read the note, delete it, or remove the personal data and
  then change its `status`, and the rules miss many formats (see below).
- The tool result lists each finding as rule and line only (for example `email (line 2)`); the
  audit log records the rule names. Matched values are never returned or logged.
- It is a review aid, not a GDPR erasure tool: approving or declining a note does not remove
  anything from git history.
- Known false negatives: local phone numbers without a `+` prefix, names, postal addresses and
  national ID numbers are not detected.

## Audit trail

- **Agents.** Every MCP write (successful, conflicting or blocked), every call refused for the
  agent's role and every rate-limited call is appended as one JSON line to
  `.svod/audit/audit.log` inside each vault, created owner-only
  ([`mcp/AuditLog.kt`](../engine/src/main/kotlin/dev/svod/engine/mcp/AuditLog.kt),
  [`mcp/SvodTools.kt`](../engine/src/main/kotlin/dev/svod/engine/mcp/SvodTools.kt),
  wired in [`lifecycle/SvodNode.kt`](../engine/src/main/kotlin/dev/svod/engine/lifecycle/SvodNode.kt)).
  Successful read-only calls (`read`, `search`, `context_pack` and the like) are not logged.
- **People.** Every keyed App API request, and every refusal, is one JSON line in
  `audit-api.log` in the config directory. The file is created 0600 and never contains request
  bodies, query strings (except the `vault` parameter) or `Authorization` values
  ([`api/ApiAuditLog.kt`](../engine/src/main/kotlin/dev/svod/engine/api/ApiAuditLog.kt)).
- **Content history.** Every change is a git commit authored by the person or agent who made it.
  An agent acting for a person adds a `Svod-Agent:` trailer.
- Not audited: keyless loopback requests on a single-user engine (the local UI), and successful
  read-only MCP calls by agents.

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
  When an admin applies an update, the installed self-update script downloads the release asset
  from GitHub.
- **Operator-configured services that receive note text.** None of these is on by default
  beyond the local ONNX embedder. Each sends note or conversation text to the endpoint the
  operator sets:
  - **Ollama embedder** (`embedder.provider` `ollama` / `local-ollama`): note chunks go to
    `embedder.endpoint`, else `embedder.ollamaEndpoint` (default `http://127.0.0.1:11434`)
    ([`index/OllamaEmbedder.kt`](../engine/src/main/kotlin/dev/svod/engine/index/OllamaEmbedder.kt)).
  - **OpenAI-compatible embedder** (`embedder.provider` `remote-openai` / `openai`): note chunks
    go to `embedder.endpoint`, which defaults to `https://api.openai.com` when unset
    ([`index/OpenAiEmbedder.kt`](../engine/src/main/kotlin/dev/svod/engine/index/OpenAiEmbedder.kt)).
    The embedder's model list, which an admin can request, calls the same endpoint
    ([`lifecycle/EmbedderController.kt`](../engine/src/main/kotlin/dev/svod/engine/lifecycle/EmbedderController.kt)).
  - **Remote reranker** (`reranker.provider` `remote` / `remote-tei` / `tei`): the query and
    candidate passages go to `reranker.endpoint` (default `http://127.0.0.1:8080`)
    ([`index/RemoteReranker.kt`](../engine/src/main/kotlin/dev/svod/engine/index/RemoteReranker.kt)).
  - **Graph summaries** (`graph.summaryProvider` `ollama` / `local-ollama`): note excerpts go to
    `graph.summaryEndpoint`, else the embedder's endpoint, even when that is a `remote-openai`
    endpoint
    ([`graphrag/SummaryLlm.kt`](../engine/src/main/kotlin/dev/svod/engine/graphrag/SummaryLlm.kt)).
  - **Memory extraction** (`memory.extractionProvider` `ollama` / `local-ollama`): conversation text goes to
    `memory.extractionEndpoint`, else the embedder's Ollama endpoint. It never falls back to a
    `remote-openai` endpoint
    ([`memory/MemoryExtractor.kt`](../engine/src/main/kotlin/dev/svod/engine/memory/MemoryExtractor.kt),
    [`SvodConfig.kt`](../engine/src/main/kotlin/dev/svod/engine/lifecycle/SvodConfig.kt)).
  - **Sync, backup and clone** git remotes: the vault's full git history goes to the remotes
    configured for each vault and for `backup`
    ([`sync/SyncGit.kt`](../engine/src/main/kotlin/dev/svod/engine/sync/SyncGit.kt),
    [`sync/SyncBootstrap.kt`](../engine/src/main/kotlin/dev/svod/engine/sync/SyncBootstrap.kt)).
- **Endpoint checks.** `SvodConfig.validate()` does not restrict the embedder, reranker, summary
  or extraction endpoints to loopback, and it does not require HTTPS for them. Ollama endpoints
  are plain `http://` by default, so pointing one at another host sends note text unencrypted
  unless you put it behind TLS. Only git remotes are checked: with `denyPrivateRemotes` on (for
  hosted engines) a remote must be an HTTPS URL naming a public host, and the connection checks
  the address it reached
  ([`sync/RemoteGuard.kt`](../engine/src/main/kotlin/dev/svod/engine/sync/RemoteGuard.kt)).
- **Error reporting to Sentry (opt-in).** The engine bundles the Sentry logback appender
  (`io.sentry:sentry-logback`), attached to the root logger for ERROR lines
  ([`logback.xml`](../engine/src/main/resources/logback.xml), ADR-0013). It sends nothing unless
  the `SENTRY_DSN` environment variable is set. When it is set, every ERROR log line, with its
  message and exception text, goes to that Sentry project. Those can include note paths, vault
  names and fragments of error output, so treat the Sentry project as a data processor. To keep
  it off, leave `SENTRY_DSN` unset.
- **No usage telemetry.** The engine sends no usage or analytics data. This statement covers the
  engine in this repository; check separately distributed clients on their own terms.

## Known gaps

- **No encryption at rest** by Svod. Rely on full-disk or volume encryption on the host, with a key
  you hold ([Encryption at rest](#encryption-at-rest), [ADR-0021](adr/0021-encryption-at-rest.md)).
- **No SSO** (SAML, OIDC) and no SCIM provisioning. People authenticate with personal keys.
- **No SOC 2, ISO 27001 or HIPAA attestation.** Svod is software you run yourself, not a hosted
  service.
- **No audit log rotation or retention.** The audit log files grow until the operator rotates them.
- **Purge does not rewrite git history** (see [Deletion and retention](#deletion-and-retention)).
- **No key or token expiry.** Keys stay valid until revoked or rotated.
- **The secret scan has exceptions.** Session transcripts stored through
  `POST /api/v1/memory/capture`, and binary files, are committed without scanning, so
  credentials in them reach git history even with `secretScanning` on (see
  [Pre-commit secret scan](#pre-commit-secret-scan)). Removing them means rewriting git history.

## Reporting a vulnerability

See [`SECURITY.md`](../SECURITY.md) for how to report a vulnerability privately and which versions
receive fixes.
