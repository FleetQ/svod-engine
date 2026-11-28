# Svod

**Auditable, git-backed memory for AI agents — memory you can read, `git diff`, and restore.**

Svod is a local, headless knowledge base that gives one or many AI agents a shared, durable
memory and **never loses a file**. Every change an agent makes is an atomic, attributed git
commit over plain Markdown + YAML. An agent's memory stops being an opaque vector blob you
have to trust and becomes something you can open in any editor, search, diff, blame, branch,
and roll back — by hand or by tool.

It speaks **MCP** to agents (the same protocol Claude, Cursor, and the rest already use),
exposes a **local HTTP/WebSocket API** to UIs, and indexes everything for **hybrid search**
(keyword BM25 + semantic vectors, fused with RRF). One serialized writer guarantees no two
agents ever corrupt each other's work; optimistic concurrency means a stale write is rejected
with a conflict, never silently overwritten.

> **Positioning:** Svod is an alternative to opaque agent-memory stores (Mem0 / Letta / Zep),
> not a note-taking app and not a vector database you bolt on. The store of record *is* a git
> repo of Markdown — zero lock-in, full provenance.

**Jump to:**
[Why Svod](#why-svod-exists) ·
[Feature tour](#what-it-does-feature-tour) ·
[Architecture](#how-it-works-90-second-architecture) ·
[Agent interface (23 MCP tools)](#the-agent-interface--23-mcp-tools) ·
[Connecting an LLM agent](#connecting-an-llm-agent) ·
[App API for UIs](#the-ui-interface--local-app-api) ·
[Download & install](#download--install) ·
[Quickstart](#quickstart) ·
[Example scenarios](#example-scenarios) ·
[How it compares](#how-it-compares) ·
[Status](#status)

---

## Why Svod exists

Agent memory today is usually one of two bad options:

1. **An opaque vector store.** Fast to write, impossible to audit. You can't see *what* the
   agent "remembered," you can't correct it without surgery, you can't diff it, and when it's
   wrong you can't tell *when* or *why* it went wrong. Recovery is "hope you have a backup."
2. **A pile of files an agent edits directly.** No concurrency control (two agents racing =
   lost writes), no history beyond the last save, no atomicity (a crash mid-write truncates a
   note), no search, no provenance.

Svod takes the auditable, durable substrate that already won for *code* — **git** — and makes
it a first-class memory backend for *agents*, behind a single safe writer and a real search
index. The result:

- **Nothing is ever lost.** Every mutation is a commit. A "deleted" file is a soft-delete to
  `.trash/` plus a commit — recoverable forever. Crash mid-write? Startup recovery completes
  or rolls back the partial write and cleans orphan temp files. `git fsck` stays clean.
- **Everything is attributable.** Each commit is authored by the specific agent (or the UI, or
  an external editor) that made it. "Which agent wrote this, and when?" is `git log`.
- **Wrong memory is fixable.** Bad note? `git revert`, restore a prior revision, or edit it in
  Obsidian. The agent sees the corrected state next read.
- **You are never locked in.** Export is the git tree. Import is a copy through the writer.
  The data is Markdown you already know how to read.

---

## What it does (feature tour)

| Capability | What it means in practice |
|---|---|
| **Single-writer integrity** | All writes funnel through one serialized actor (a coroutine + channel). No path bypasses it, so concurrent agents can never interleave a corrupt write. |
| **Atomic writes only** | tmp → fsync → rename → fsync dir. Never truncate-in-place, never hard `rm`. A power cut can't leave a half-written note. |
| **Git as durable history** | Every create/update/delete/move is a commit (via jgit). Full `log`, `diff`, `blame`, `restore`. The vault is a normal git repo you can clone. |
| **Optimistic concurrency** | Revision = git blob hash. Write with a stale revision → structured `conflict` + current content for a 3-way merge. **Never a silent overwrite.** |
| **Crash recovery** | On startup the engine reconciles the working tree against git, completes/rolls back partial writes, and removes orphan `.tmp` files. |
| **Hybrid search** | Lucene BM25 (lexical) + optional HNSW kNN vectors (semantic), fused with Reciprocal Rank Fusion. Heading-aware chunking. Filters by tag / path / date; fuzzy / prefix / phrase / field-scoped queries. |
| **Pluggable embeddings** | Default `onnx-local` runs `multilingual-e5-small` (MIT) in-process via ONNX Runtime — no server, no Python, no network at query time. Or `local-ollama` (a local Ollama server), `remote-openai` (any OpenAI-compatible `/v1/embeddings` endpoint, API key as a Secrets ref), or `none` for a guaranteed BM25-only baseline. Switchable at runtime via `PUT /api/v1/embedder`; `POST /api/v1/embedder/models` lists what a provider can serve. |
| **Optional reranking** | A second-stage cross-encoder re-scores the fused candidates for a relevance lift — opt-in, default off, degrades to the fused order on any failure. Configured under `reranker` and reported in `GET /settings`. |
| **MCP server for agents** | 23 tools over streamable HTTP, per-agent bearer tokens → git author (or the bound person, see team sharing), read-only/write roles, token-bucket rate limiting, append-only audit log, optional TLS. Memory review is chosen per agent (`requireReview`). |
| **Typed memory + review** | `remember` turns an observation into a typed memory note (policy / preference / fact / episode / procedure) with a lifecycle; `context_pack` recalls it (ranked, or the whole rule book with `enumerate=true`). Review is per agent via `agents[].requireReview` (agents without one follow `memory.requireReview`) and **off by default since 1.30.0**; an agent that opts in has its fact/policy wait `provisional` in the review queue (`GET`/`POST /api/v1/memory/review`, the viewer's Memory review pane) until a person approves it. See [ADR-0018](docs/adr/0018-memory-fact-classification.md). |
| **Local App API + WebSocket** | An HTTP/JSON API for UIs (loopback by default; a shared engine serves it over TLS with personal keys), plus a live `/api/v1/events` stream (agent activity, commits, index updates, conflicts) so a UI feels alive. |
| **Wikilink graph + thematic graph** | `[[wikilinks]]` parsed into a backlink/outlink graph. Moving or renaming a note **rewrites every backlink in a single commit** — link integrity for free. Optionally (`graph.enabled`), a derived note graph (wikilinks + kNN similarity over the stored vectors) is clustered by Louvain into thematic communities, with optional build-time summaries: MCP `graph_communities` / `graph_community` / `graph_status`, App API `graph/communities` / `graph/community` / `graph/status` / `graph/rebuild`. |
| **Team vault sharing** | People are App API principals with personal keys, an `admin` flag and per-vault grants ([ADR-0019](docs/adr/0019-app-api-principals.md)). An agent can be bound to a person (`agents[].user`) and get a role per vault (`reader` / `writer` / `maintainer`); a bound agent's commits are authored by the person with a `Svod-Agent:` trailer, so history shows who stands behind each change. See [ADR-0020](docs/adr/0020-team-sharing-users-and-roles.md). |
| **Multiple vaults** | Run N vaults in one engine (e.g. `personal` + `work`), each its own git repo, lock, index, and sync remote. Routes select a vault via `?vault=`; agents are scoped to their granted vault. Cross-vault `[[vault:note]]` links resolve and surface as backlinks. |
| **Multi-host sync** | Replicate the vault across machines over git transport. Frontmatter-aware structured merge (YAML keys union, body via git 3-way). A designated merge authority keeps history deterministic; real conflicts are *surfaced*, never auto-resolved. |
| **Hardening** | Opt-in pre-commit secret scanning of note writes (`secretScanning`; transcript capture and binary files are not scanned, see [`docs/security-overview.md`](docs/security-overview.md)), opt-in personal-data review (`personalDataScanning`: agent writes that add emails, phone numbers, IBANs or card numbers wait in the Memory review queue), live metrics (write latency, queue depth, index lag, conflicts, sync status), secret refs (`env:` / `file:` / macOS `keychain:`) instead of plaintext tokens. |
| **Obsidian import, zero lock-in** | One-shot migration of an existing Obsidian vault — markdown + **attachments** (images/PDF) preserved, frontmatter + wikilinks intact, `.obsidian/` skipped. Idempotent (re-run reconciles, never clobbers). Symlinks are skipped by default or, with `followSymlinks`, materialized (file + whole directory links copied in). Leaving is just `git clone`. See [Using a Svod vault with Obsidian](docs/obsidian.md). |
| **Basic Memory import** | `svod-engine import-basic-memory <config.json> <dir> [into]` (engine stopped) imports a Basic Memory project the same way: observations, relations and frontmatter kept verbatim, and relations that point at a note's `title` or `permalink` turned into resolvable `[[path\|Original]]` wikilinks. Idempotent, never clobbers. |
| **Claude Code memory import** | `svod-engine import-claude-memory <config.json> <memory-dir> [into]` (engine stopped) imports a Claude Code auto-memory folder (`~/.claude/projects/*/memory/`) under `into` (default `claude-memory`), one note per memory: frontmatter and body kept, the memory's type (`metadata.type` or `type`) added as a tag next to `claude-memory`, and `[[name]]` links to another memory's `name` turned into resolvable `[[path\|name]]` wikilinks. The top-level `MEMORY.md` index is skipped. Idempotent, never clobbers. |
| **Mem0 import** | One note per memory, idempotent by Mem0 id, through the single writer. `POST /api/v1/import` with `format: mem0` and a local JSON export file; categories become tags, a locally edited note is never overwritten. |
| **Notion import** | `svod-engine import-notion <config.json> <notion-export-dir> [into]` (engine stopped) or `POST /api/v1/import` with `format: notion` imports an unzipped Notion "Markdown & CSV" export under `notion/`: page ids are removed from file and folder names, links between pages become `[[wikilinks]]`, attachment links follow the renamed files, and database CSVs are skipped (listed in `skipped`). Idempotent, never overwrites a local edit. |
| **External sources (re-syncable)** | Register a file/dir living *outside* the vault and re-sync its content in — the git-friendly answer to Obsidian's symlinked notes. *external-wins-unless-locally-edited*: external edits flow in, a vault copy you edited is a conflict (never clobbered). Optional `prune` soft-deletes files gone from the source (an edited copy is still protected); optional auto-sync on startup + interval. See [ADR-0016](docs/adr/0016-external-sources-and-symlink-import.md). |
| **UTF-8 / Cyrillic everywhere** | Filenames and content in any script. `core.quotepath=false`, tested with Cyrillic paths + YAML. |

---

## How it works (90-second architecture)

```
        AI agents (remote)            UI / scripts (local)            you (anytime)
              │                              │                            │
        MCP over HTTPS                 HTTP + WebSocket               git / editor /
        token + role                  loopback default                Obsidian
              │                              │                            │
              ▼                              ▼                            ▼
        ┌──────────────────────────────────────────────────────┐
        │                     Svod engine                        │
        │   ┌────────────────────────────────────────────────┐  │
        │   │      SINGLE WRITER (serialized actor)           │  │   atomic write,
        │   │   optimistic revision · soft-delete · commit    │  │   one commit per
        │   └────────────────────────────────────────────────┘  │   mutation
        │        │                    │                  │       │
        │   jgit (history)     Lucene (BM25+kNN)   wikilink graph │
        │        │                    ▲                          │
        │   file watcher  ───►  index off the write path         │
        └──────────────────────────────────────────────────────┘
                     │
              vault = a normal git repo of Markdown + YAML
                     │
              git transport ──► other Svod hosts (sync)
```

Reads fan out (search, history, graph — concurrent and cheap). **Writes are serialized**
through one actor, so integrity is structural, not best-effort. Indexing runs *off* the write
path, so a slow embed never blocks a write, and the index self-heals against git `HEAD` after
any wipe. The App API is **loopback by default**; remote agents come in through MCP with
TLS + per-agent tokens (architecture invariant #7). A shared engine may expose both ports off
loopback only with TLS on each, personal keys and `localAdmin` off (ADR-0019).

Full detail: [`docs/architecture.md`](docs/architecture.md) and the ADRs in
[`docs/adr/`](docs/adr/).

---

## The agent interface — 23 MCP tools

Per-agent bearer token authenticates and **becomes the git commit author**. Roles are
enforced *before* the engine is touched.

**Read (any role):** `read` · `list` · `tree` · `grep` · `search` · `context_pack` · `history` ·
`recent_changes` · `diff` · `get_revision` · `blame` · `link` · `graph_query` · `graph_communities` · `graph_community` · `graph_status`
**Write (WRITE role):** `write` · `edit` · `delete` · `move` · `promote` · `remember` · `extract_memories`

- `tree` shows the folder structure with recursive file counts; `grep` finds exact text or a regex
  and returns line hits. `grep` obeys recall's visibility: captured sessions never, `messy/` drafts
  only with a `messy/` prefix, `<private>` content never.
- `recent_changes` is the vault-wide change feed for catching up at session start: commits newest
  first back to `since` (a commit id such as the `head` of your last call, or an ISO date), each with
  author, agent and changed paths marked add/modify/delete/rename/restore. Page with
  `before` = `nextCursor`. It lists paths only; `diff`/`get_revision` give the content.
- `blame` attributes each line of a note at HEAD to the commit that last changed it, with author,
  date and the `Svod-Agent` when present. Committed history only; renames are not followed.

- `search` runs hybrid retrieval (BM25 + vectors, RRF-fused, plus any reranker) with `mode`
  (`keyword`/`semantic`/`hybrid`) and filters; `context_pack` goes one step further — it assembles
  the top hits into a single **token-budgeted, deduped, cited** context block (each block carries the
  note's latest commit + author), the one-call "recall my memory about X" primitive for an agent.
- **Typed memory + two retrieval paths.** A note's frontmatter `type` (policy/preference/fact/
  episode/procedure) classifies it; `status` (`active`/`provisional`/`revoked`), `superseded_by` and `expires_at`
  give it a lifecycle (recall hides revoked/provisional/superseded/expired by default; `search` and
  `context_pack` report the hidden matches per reason in `withheld`, with a hint to retry with
  `includeAll=true`). `context_pack`
  with `enumerate=true` returns *every* note matching a `type`/`tags` filter **in full, unranked** —
  the "rule book" (all active policies/preferences) loaded verbatim, vs. the ranked semantic recall.
- **Procedures.** `type: procedure` holds ordered how-to steps (one per line) for a `subject`. A new
  `active` version for a subject is an attributed git commit that revokes and links the previous one
  when the similarity search for the new text finds it (no model judges it; a full rewrite may not
  be found, so pass `supersedes` then). A `provisional` version, e.g. from `extract_memories`, is not
  matched by subject: it is classified like any other memory, so it does not retire the approved one
  just for sharing its subject. Load them all with
  `context_pack` `enumerate=true` `type=procedure`.
- `remember` is the **promotion gate**: it turns an observation into a durable typed memory note —
  dedups by content, sets `status` by type (fact/policy enter `provisional`), and `supersedes` an
  older memory by revoking + linking it — so an agent-written KB doesn't poison its own recall.
  Review of agent-written memory is chosen per agent. By default (since 1.30.0) there is none: every
  memory enters `active` at once, an `UNCERTAIN` one with `needs-review: true` so a person can still
  look at it, and a `CONTRADICTION` with its `contradicts:` link to the other side. An agent with
  `"requireReview": true` in its `agents[]` entry (or set via `PUT /api/v1/agents/{id}`) has its
  fact/policy stored `provisional`, waiting in the review queue until a person approves it in the
  reference web viewer's Memory review pane (`examples/web-viewer`) or through
  `POST /api/v1/memory/review`. Agents without their own setting follow `"memory": { "requireReview": … }` (default
  `false`). Each agent's MCP instructions and tool descriptions say what applies to it, and
  `GET /api/v1/settings` reports the engine-wide default as `memory.requireReview`.
- `extract_memories` is **opt-in**: given a finished conversation transcript, a configured local
  model (`"memory": { "extractionProvider": "ollama", "extractionModel": … }`, endpoint defaulting to
  the embedder's Ollama endpoint) proposes candidate memories, and each goes through the `remember`
  gate. Its output is **always `provisional`**, whatever `requireReview` says, so it waits in the
  Memory review queue and stays out of recall until a person approves it. A transcript with secrets
  is refused before any model sees it. With no provider configured (the default) the tool answers
  "not configured" and writes nothing. Pass `format: "langsmith"` or `format: "braintrust"` to hand
  it a run export (JSON object, array or JSONL) instead of a plain transcript: it is converted to a
  `role: text` transcript first, which gets the same secret check and provisional review.
- `write` / `delete` / `move` take an optional `expectedRevision` for optimistic concurrency —
  a mismatch returns a structured `conflict` (with current content), not a protocol error.
- `move` rewrites all backlinks atomically.
- A session-based (2025-11-25) MCP client that goes away without closing its session is dropped
  after `"mcp": { "sessionIdleMinutes": 30 }` (default 30) without a request; when it comes back it
  gets 404 and re-initializes. Stateless (2026-07-28) requests keep no session.
- `promote` moves a note from a scratch `messy/` namespace into the curated `vault/` (a
  revision-checked move) — the "draft → publish" path for agents.
- A `READ_ONLY` token is not offered write tools, and calling one fails as an unknown tool; an over-quota agent gets `rate_limited`;
  every action (including denials and conflicts) lands in an append-only audit log.

### Connecting an LLM agent

Point any MCP-capable client (Claude Desktop/Code, Cursor, OpenAI Codex CLI, VS Code/GitHub Copilot,
Windsurf, Gemini CLI, or an agent framework such as LangGraph,
the OpenAI Agents SDK, the Vercel AI SDK or Mastra) at the engine's **MCP endpoint** and authenticate
with one of the bearer tokens from your config's `agents` list. For copy-paste framework setups, see
[Use Svod from your agent framework](docs/integrations-agent-frameworks.md).

- **URL:** `http://127.0.0.1:7518/mcp` (streamable HTTP; HTTPS when `mcpTls` is configured — required
  for any non-loopback access).
- **Auth:** header `Authorization: Bearer <token>`, where `<token>` is an agent's `token` from the
  config (a literal value or a `env:` / `file:` / `keychain:` Secrets ref). The token *is* the
  identity — its `agentId` becomes the git commit author and its `role` (`WRITE` / `READ_ONLY`) gates
  every call. An agent may also set an optional `"tools": ["search", "context_pack", ...]` allowlist:
  `tools/list` then offers only those tools (within its role), and any other tool fails as unknown.

`svod-engine init` (see [Quickstart](#quickstart)) prints every one of the blocks below, filled in with your
own endpoint and the new agent's token (shown here as `friday-write-token`).

Claude Desktop / Cursor — add to the MCP config:

```json
{
    "mcpServers": {
        "svod-memory": {
            "url": "http://127.0.0.1:7518/mcp",
            "headers": {
                "Authorization": "Bearer friday-write-token"
            }
        }
    }
}
```

Claude Code — run:

```sh
claude mcp add --transport http svod-memory http://127.0.0.1:7518/mcp --header "Authorization: Bearer friday-write-token"
```

Codex CLI — add to `~/.codex/config.toml`:

```toml
[mcp_servers.svod-memory]
url = "http://127.0.0.1:7518/mcp"
http_headers = { "Authorization" = "Bearer friday-write-token" }
```

VS Code / GitHub Copilot — add to `.vscode/mcp.json` or the user-level MCP config:

```json
{
    "servers": {
        "svod-memory": {
            "type": "http",
            "url": "http://127.0.0.1:7518/mcp",
            "headers": {
                "Authorization": "Bearer friday-write-token"
            }
        }
    }
}
```

Windsurf — add to `~/.codeium/windsurf/mcp_config.json`:

```json
{
    "mcpServers": {
        "svod-memory": {
            "serverUrl": "http://127.0.0.1:7518/mcp",
            "headers": {
                "Authorization": "Bearer friday-write-token"
            }
        }
    }
}
```

Gemini CLI — add to `~/.gemini/settings.json`:

```json
{
    "mcpServers": {
        "svod-memory": {
            "httpUrl": "http://127.0.0.1:7518/mcp",
            "headers": {
                "Authorization": "Bearer friday-write-token"
            }
        }
    }
}
```

#### Ready-to-paste system prompt

Drop this into your agent's system prompt so it actually *uses* the memory well:

```text
You have a persistent, git-backed memory called Svod, reached through its MCP tools. It is your
durable long-term memory across sessions — auditable Markdown notes, not a black box.

Recall first, then answer:
- Before answering anything that might rely on earlier knowledge, call `context_pack` with a short
  query and a token budget (e.g. 1500) to pull the relevant notes assembled into one cited block.
  Use `search` (mode=hybrid) when you want raw hits to inspect, or filter-only (tags/pathPrefix) to
  browse. Use `link` / `graph_query` to follow connections between notes.

Write durable facts as you learn them:
- Use `write` to save a note as Markdown. Put it under a clear path (e.g. `projects/<x>/decisions.md`).
  Add YAML frontmatter `tags: [..]` for retrieval, and link related notes with `[[wikilinks]]`.
- To UPDATE an existing note safely, first `read` it to get its `revision`, then `write` with
  `expectedRevision` set to that value. If you get a `conflict`, someone changed it meanwhile — re-read,
  merge, and retry. Never blindly overwrite.
- Draft uncertain material under `messy/` and `promote` it into `vault/` once confirmed.
- Deletes are soft (recoverable); use `delete` rather than overwriting with empty content.

Principles:
- Prefer small, well-titled, linked notes over one giant note. One fact per note where it helps recall.
- Everything you write is attributed to you and versioned — write as if a human will read the diff.
- If a tool returns `rate_limited` or `denied`, stop and report it; do not retry in a loop.
```

## The UI interface — local App API

Loopback HTTP/JSON, validated against [`contract/openapi.yaml`](contract/openapi.yaml) (the
versioned single source of truth for every client — currently **0.43.0**):

- **Health:** `/health` · `/ready`
- **Content:** `/api/v1/` `tree` · `file` (GET/PUT/DELETE; DELETE under `.trash/` purges, admin only) ·
  `file/move` · `file/restore` · `file/history` · `file/diff` · `file/revision` · `file/blame` · `file/links` ·
  `trash` (list soft-deleted files) · `changes` (vault-wide change feed, paged)
- **Discovery:** `search` (filter-only "browse by tag" when `q` is omitted) · `graph` · `tags`
- **Graph (thematic):** `graph/communities` · `graph/community` · `graph/status` · `graph/rebuild`
- **People & agents** ([ADR-0019](docs/adr/0019-app-api-principals.md),
  [ADR-0020](docs/adr/0020-team-sharing-users-and-roles.md)): `me` · `users` · `users/{id}` ·
  `users/{id}/key` · `agents` · `agents/{id}` · `stats` (engine admin only)
- **Memory:** `memory/review` (GET queue / POST approve, decline, reopen) · `memory/rulebook` ·
  `memory/capture` · `memory/sessions` · `memory/sessions/mark-distilled` · `memory/proposals` ·
  `memory/proposals/{id}` · `memory/dashboard`
- **Updates & secrets:** `update/check` · `update/apply` · `secrets` (upload once, get a `file:` ref)
- **Index & embedder:** `index/status` · `index/reembed` · `index/pause` · `index/resume` ·
  `embedder` (PUT, switch provider) · `embedder/test` · `embedder/models` · `settings`
- **Conflicts & sync:** `conflicts` · `conflicts/resolve` · `sync/config` · `sync/status` ·
  `sync/now` · `backup/now` · `settings/backup` · `maintenance/reindex`
- **Import & external sources:** `import` · `sources` (GET/POST) · `sources/{id}` (PATCH/DELETE) ·
  `sources/{id}/sync` · `sources/{id}/resolve` · `sources/sync`
- **Multi-vault:** `vaults` · `vaults/{id}`; every per-vault route takes `?vault=<id>`
- **Live + metrics:** `events` (WebSocket) · `/api/v1/metrics` (JSON) · **`/metrics`** (Prometheus
  text exposition, for scrapers — at the root, outside the versioned contract)
  — incl. `svod_conflicts`, `svod_sync_held_seconds` / `svod_sync_out_of_sync_seconds` per synced
  vault and `svod_mcp_live_sessions`

The `conflicts` endpoint carries each conflict's `base`/`ours`/`theirs` so a client can render a
3-way merge; `conflicts/resolve` commits the merged content through the single writer and clears
it. The reference web viewer's **Sync conflicts** pane (`examples/web-viewer`) is a ready-made
client: it shows base / ours / theirs side by side and resolves with keep ours (`keepMine`), take
theirs (`acceptIncoming`) or an edited merge (`content`).

The WebSocket streams `agent.activity` / `commit.created` / `index.updated` / `index.progress` /
`file.changed` / `conflict` / `engine.status` live — what powers the "watch your agents think" feed.

---

## Download & install

Prebuilt, self-contained app-images for every release are on the
**[Releases page](https://github.com/FleetQ/svod-engine/releases/latest)** — a bundled trimmed JVM,
no Java needed, every embedder including in-process `onnx-local` semantic search:

| OS | Download | Run |
|---|---|---|
| **macOS** (Apple Silicon) | `SvodEngine-macos-arm64.tar.gz` | `./SvodEngine.app/Contents/MacOS/SvodEngine config.json` |
| **Linux** (x64) | `SvodEngine-linux-x64.tar.gz` | `./SvodEngine/bin/SvodEngine config.json` |
| **Windows** (x64) | `SvodEngine-windows-x64.zip` | `SvodEngine\SvodEngine.exe config.json` |

Every OS follows the same three steps: **(1)** unpack the download, **(2)** create a `config.json` —
fastest is `init ~/svod` (e.g. `./SvodEngine/bin/SvodEngine init ~/svod`), which writes
`~/svod/config.json` with a random agent token and prints how to connect your agent; or write one by
hand (see [Quickstart](#quickstart) for a minimal one, or copy
[`dist/config.sample.json`](dist/config.sample.json)), **(3)** run the engine with that config as its
only argument. It binds `127.0.0.1:7517` (App API) + `127.0.0.1:7518` (MCP) by default.

#### macOS (Apple Silicon)

```sh
tar xzf SvodEngine-macos-arm64.tar.gz                       # → SvodEngine.app
xattr -dr com.apple.quarantine SvodEngine.app              # only if the build is unsigned (see note)
./SvodEngine.app/Contents/MacOS/SvodEngine ~/svod/config.json
```

> Gatekeeper: release binaries are signed + notarized when a signing identity is configured. For an
> unsigned build, run the `xattr` line above once (or right-click the app → **Open**).

#### Linux (x64)

```sh
tar xzf SvodEngine-linux-x64.tar.gz                         # → SvodEngine/
./SvodEngine/bin/SvodEngine ~/svod/config.json
```

#### Windows (x64)

```powershell
Expand-Archive SvodEngine-windows-x64.zip -DestinationPath .   # → SvodEngine\
.\SvodEngine\SvodEngine.exe C:\svod\config.json
```

Verify it's up (any OS): `curl http://127.0.0.1:7517/ready` → `{"ready":true,...}`, and open
`http://127.0.0.1:7517/` for the bundled reference viewer.

#### Run it as a background service (always-on)

So the engine survives logout/reboot and the UI's "Start Svod" can wake it:

- **macOS — launchd.** Edit the sample `dev.svod.engine.plist` in [`dist/`](dist/) (set the binary +
  config paths), then:
  ```sh
  cp dist/dev.svod.engine.plist ~/Library/LaunchAgents/
  launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/dev.svod.engine.plist
  launchctl kickstart -k gui/$(id -u)/dev.svod.engine     # start (or restart) now
  ```
- **Linux — `systemd --user`.** Create `~/.config/systemd/user/svod.service` with
  `ExecStart=/path/to/SvodEngine/bin/SvodEngine /path/to/config.json`, then
  `systemctl --user enable --now svod` (add `loginctl enable-linger $USER` to keep it running after logout).
- **Windows — Task Scheduler.** Create a task triggered **At log on** running
  `SvodEngine.exe C:\svod\config.json`, or wrap the `.exe` as a Windows service with a tool like NSSM.

### Native binary (single executable, no JVM)

Each release also ships a GraalVM `native-image` build — one self-contained executable with instant
startup and no bundled runtime. It serves the `none` (BM25) and `ollama` embedders; it does **not**
include in-process `onnx-local` semantic search (DJL/ONNX are JVM-only — use the app-image above for that).

| OS | Download | Run |
|---|---|---|
| **macOS** (Apple Silicon) | `svod-engine-macos-arm64` | `chmod +x svod-engine-macos-arm64 && ./svod-engine-macos-arm64 config.json` |
| **Linux** (x64) | `svod-engine-linux-x64` | `chmod +x svod-engine-linux-x64 && ./svod-engine-linux-x64 config.json` |
| **Windows** (x64) | `svod-engine-windows-x64.exe` | `svod-engine-windows-x64.exe config.json` |

Built on **Oracle GraalVM** for JDK 23: with Profile-Guided Optimization on Linux, `-O3` on Windows, a plain
build on macOS (the PGO build does not fit the macOS CI runner's memory);
the app-images stay on GraalVM CE / JDK 21. Oracle GraalVM is licensed under the
[GraalVM Free Terms and Conditions](https://www.oracle.com/downloads/licenses/graal-free-license.html)
(free, production use allowed); the parts of it inside the native binaries are under that licence, the engine
code stays Apache-2.0. Redistributors must keep these binaries free of charge: GFTC allows redistribution only under its own terms and with no fee for products or services that include or use them (the Apache-2.0 source and the app-images have no such condition). See [ADR-0015](docs/adr/0015-native-image-and-cross-platform-releases.md).

Build from source instead? Read on.

## Quickstart

Requires a JDK (20 used here; the Gradle wrapper is committed).

Fastest start: let the engine write the config for you, then run with it.

```sh
svod-engine init ~/svod                    # writes ~/svod/config.json (never overwrites); add --embedder onnx-local for semantic search
svod-engine ~/svod/config.json             # start the engine with it
svod-engine doctor ~/svod/config.json      # check the setup without changing anything (exits 1 on a failure)
svod-engine eval ~/svod/config.json golden.jsonl # score search quality on your own notes (engine stopped)
svod-engine digest ~/svod/config.json proj # write proj/index.md + proj/log.md from git history (engine stopped)
```

`eval` reports recall@k, nDCG@10 and MRR per search mode for a golden set you write; see
[Retrieval quality](docs/retrieval-quality.md) for the file format and the baseline numbers.

`digest` writes a generated `index.md` (each note's title, last change and author/agent) and `log.md` (the
folder's commits, newest first) into each named folder, or every top-level folder when none is given, in one
commit per folder; a re-run with nothing new makes no commit, and a file without the
`generated: svod-folder-digest` frontmatter marker is never overwritten.

From source, the same is `cd engine && ./gradlew run --args="init $HOME/svod"`.

`init` creates one `WRITE` agent with a random token (kept in the owner-only config file) and prints a
ready-to-paste MCP setup for Claude Desktop / Cursor, Claude Code (`claude mcp add`), Codex CLI,
VS Code / GitHub Copilot, Windsurf and Gemini CLI, plus the system prompt.

Or write the config by hand:

```sh
# 1. write a config (or copy dist/config.sample.json)
cat > /tmp/svod.json <<'JSON'
{
  "vaultPath": "/tmp/svod-vault",
  "host": "127.0.0.1",
  "appApiPort": 7517,
  "mcpPort": 7518,
  "embedder": { "provider": "none" },
  "agents": [
    { "token": "friday-write-token", "agentId": "friday", "role": "WRITE", "name": "Friday" },
    { "token": "reader-token",        "agentId": "reader", "role": "READ_ONLY" }
  ],
  "secretScanning": true,
  "webViewerPath": "examples/web-viewer"
}
JSON

# 2. run the engine  ("none" embedder = BM25-only, instant start, no model download)
cd engine
JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew run --args=/tmp/svod.json

# 3. it's up
curl -s http://127.0.0.1:7517/ready                 # {"ready":true,...}
open  http://127.0.0.1:7517/                         # the reference web viewer
```

Write a note through the loopback API (agents do the same over MCP):

```sh
curl -X PUT 'http://127.0.0.1:7517/api/v1/file?path=notes/first.md' \
  -H 'Content-Type: application/json' \
  -d '{"content":"# First note\n\nHello from Svod. [[second]]\n"}'
# → {"path":"notes/first.md","revision":"<blob>","commit":"<sha>"}

git -C /tmp/svod-vault log --oneline      # your agents' memory, as history
```

Switch `"embedder"` to `{ "provider": "onnx-local" }` for semantic search (downloads
`multilingual-e5-small`, ~120 MB, once, checksum-pinned). Package a self-contained
`SvodEngine.app` with [`dist/package.sh`](dist/package.sh). Run it under launchd with the
plist in [`dist/`](dist/). Build & test:
`cd engine && JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test`.

### Troubleshooting

Run `svod-engine doctor <config.json>` (no argument checks the default `~/svod-vault` setup) and paste its
output when you ask for help. It only reads: it never writes to the vault, the config, the discovery file or
the model cache, and it never prints a secret, only the ref and who owns it. Each line is `ok`, `warn` or
`FAIL`, a warn or fail with a one-line fix, and it exits 1 when any check fails. It checks:

- **config**: the file exists and parses, and every validation error (all of them, not only the first).
- **agents**: at least one agent, or every MCP client is rejected.
- **secrets / tls**: `env:`, `file:` and `keychain:` refs for agent tokens, user keys, embedder/reranker API
  keys and TLS passwords resolve (`keychain:` is macOS only), and TLS keystores exist.
- **vault**: each vault folder is writable; a missing folder or one that is not yet a git repo is a warn, as
  the engine creates both on first start.
- **engine / ports**: `appApiPort` and `mcpPort` are free, and whether an engine is already running.
- **embedder**: with `onnx-local`, whether the model is already downloaded (it is fetched on first start).

---

## Example scenarios

**1. A multi-agent crew with shared, non-colliding memory.**
A research agent, a writer agent, and a reviewer all hold the same Svod vault over MCP. They
write concurrently; the single writer serializes them so nothing is lost, and each note's
`git log` shows exactly which agent contributed what. The reviewer searches (`search`), reads
the writer's draft (`read`), and the writer `promote`s it from `messy/` to `vault/` only once
it's reviewed.

**2. "The agent remembered something wrong."**
You spot a bad fact in the agent's memory. Instead of black-box surgery, you open the note in
Obsidian (or `git revert` the offending commit). The file watcher ingests your edit as an
`external`-authored commit, re-indexes it, and the agent reads the corrected memory next time.
No special tooling, no migration. (See [Using a Svod vault with Obsidian](docs/obsidian.md).)

**3. Long-running project knowledge that compounds.**
Over months, agents accumulate decisions, ADRs, and findings as linked notes. `[[wikilinks]]`
build a navigable graph; moving a note keeps every backlink intact. Hybrid search finds the
right note by meaning *or* keyword, in any language. Six months in, you can still answer "when
did we decide X, and which agent proposed it?" with `git log`.

**4. Two machines, one brain.**
Your laptop and a server both run Svod over the same git remote. Edits on either replicate;
YAML frontmatter merges key-by-key, note bodies merge via git's 3-way, and a genuine conflict
is surfaced at `/api/v1/conflicts` (both versions preserved) rather than silently picking a
winner; the reference web viewer's Sync conflicts pane lets you settle it side by side. Each
host holds the full history — lose a machine, lose nothing.

**5. A custom dashboard / second-brain UI.**
Point any client at the loopback API and subscribe to `/api/v1/events`. You get a live feed of
every agent action, jump-to-diff, search, graph, and one-click restore — without writing a
line of engine code, because the contract is versioned and stable. (`examples/web-viewer/` is
a working, dependency-free reference you can crib from.)

---

## How it compares

| | **Svod** | Mem0 | Letta / MemGPT | Basic Memory | Zep | Plain Obsidian | Vector DB + RAG |
|---|---|---|---|---|---|---|---|
| **Store of record** | Git repo of Markdown | Vector + KV store | DB + vector store; ⚠️ git-backed Context Repositories (newer) | Markdown files + SQLite index | DB + vector store | Markdown files | Embeddings |
| **Human-readable / editable** | ✅ any editor | ❌ opaque | ⚠️ via API | ✅ any editor | ❌ opaque | ✅ | ❌ |
| **Full version history / diff** | ✅ git | ❌ | ⚠️ via git-backed Context Repositories (newer, opt-in) | ⚠️ DIY (your own git/sync) | ⚠️ partial | ❌ (last save) | ❌ |
| **Per-change provenance (who/when)** | ✅ commit author | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ❌ | ❌ |
| **Never-lose-files guarantee** | ✅ atomic + soft-delete + recovery | ❌ | ❌ | ⚠️ | ❌ | ❌ | ❌ |
| **Concurrency control** | ✅ single writer + optimistic revision | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ❌ | ⚠️ |
| **Conflict surfaced, not auto-merged** | ✅ | ❌ | ❌ | ⚠️ | ❌ | n/a | ❌ |
| **Hybrid search (BM25 + vectors)** | ✅ RRF | ⚠️ vector-first | ⚠️ | ⚠️ full-text first | ✅ | ⚠️ plugins | ⚠️ vector-only |
| **Runs fully local, no Python/server** | ✅ single JVM, in-proc embeddings | ⚠️ | ⚠️ | ⚠️ local, needs Python | ⚠️ | ✅ | ⚠️ |
| **Agent protocol** | ✅ MCP (23 tools) | SDK | SDK | ✅ MCP | SDK | ❌ | ❌ (DIY) |
| **Multi-host replication** | ✅ git transport | ⚠️ hosted | ⚠️ | ⚠️ cloud sync | ⚠️ hosted | ⚠️ sync apps | ⚠️ |
| **Lock-in** | None (git clone) | High | Medium | Low | High | None | High |
| **Licence** | Apache-2.0 | Apache-2.0 | Apache-2.0 | AGPL-3.0 | — | Proprietary (free app) | Varies |
| **Hosted tier** | ❌ self-host only (no hosted product yet) | ✅ hosted platform | ✅ cloud | ✅ cloud (paid) | ✅ cloud | ⚠️ paid Sync/Publish add-ons | Varies |

**Where Svod wins:** auditability, durability, and provenance — memory as a *substrate you own
and can inspect*, not a service you query and trust. **Where the others may fit better:** if
you want a fully-hosted SaaS memory with zero ops and don't care that it's opaque, a managed
store is less setup. Svod's bet is that for serious, long-lived agent memory, "you can read and
restore it" beats "it's someone else's database."

*(Comparison reflects each tool's typical/default posture; specific configurations vary.)*

Choosing between Markdown + MCP memory tools? See [Svod vs Basic Memory](docs/compare-basic-memory.md).

Moving off a hosted memory store? See [Svod vs Mem0](docs/compare-mem0.md).

Weighing git-backed agent memory? See [Svod vs Letta Context Repositories](docs/compare-letta.md).

---

## Products & repos

| Repo | What |
|---|---|
| **`FleetQ/svod-engine`** (this repo, OSS) | The product: headless engine (Kotlin/JVM), the OpenAPI contract, packaging, docs, examples. OS-agnostic. |
| `FleetQ/svod-ui-macos` (separate, personal) | A personal SwiftUI client built against the published contract. **Not a supported product surface.** |

FleetQ is **one MCP client** — a first-party reference integration, not an embedded
dependency. The engine stays vendor-agnostic (see
[ADR-0002](docs/adr/0002-repo-split-and-license.md)).

## Find Svod in MCP directories

[`server.json`](server.json) at the repo root is the manifest for the official
[MCP Registry](https://registry.modelcontextprotocol.io). Publishing is a manual step for the
maintainer: install `mcp-publisher`, run `mcp-publisher login github`, then `mcp-publisher publish`
from the repo root. The same title, description and links can be reused when listing Svod on
glama.ai, mcpservers.org and mcp.directory.

The manifest describes the local endpoint `http://127.0.0.1:7518/mcp` (streamable HTTP) with an
`Authorization: Bearer <agent token>` header; tokens come from the `agents` list in your svod config
and are never stored in the manifest. Binaries are on the
[Releases page](https://github.com/FleetQ/svod-engine/releases/latest).

When bumping `SvodNode.ENGINE_VERSION`, bump `version` in `server.json` too —
`McpDirectoryManifestTest` fails until they match.

## Layout

| Dir | What |
|---|---|
| `engine/` | Kotlin + Gradle engine. |
| `contract/` | OpenAPI spec — single source of truth for all clients. |
| `dist/` | launchd plist, jpackage/jlink packaging, install + secrets docs. |
| `examples/` | Reference integrations — start with `examples/web-viewer/`. |
| `docs/` | Architecture + ADRs. Start at [`docs/architecture.md`](docs/architecture.md) and [`docs/build-order.md`](docs/build-order.md). |

For security reviews and questionnaires, see [`docs/security-overview.md`](docs/security-overview.md).

## Status

**v1.35.0 — stable, production.** Integrity core → hybrid index → MCP → App API/contract/graph/
watcher → lifecycle → reference viewer → multi-host sync → hardening → multi-vault + Obsidian
import → backup/DR + ops surface → external sources (prune + auto-sync) → non-blocking keyword-first
indexing + pluggable embedders (local-onnx / local-ollama / remote-openai) → restart robustness
(stale-lock self-heal, observable resume), Prometheus `/metrics`, opt-in reranking, oversized-chunk
fault tolerance → embedder model enumeration (`POST /embedder/models`) → embedding ETA, incremental
link/tag index, `context_pack` agent-memory recall, reranker in `/settings`, filter-only tag browse →
scheduled auto-backup → two-way multi-machine sync (canonical `refs/svod/sync/<vault>`, or a plain branch via `syncBranch`, 3-way merge,
conflict surface/resolve, `clone` bootstrap) → per-source filesystem auto-sync (FSEvents watcher,
debounced; `autoSync` toggle via PATCH) → thematic graph (Louvain communities over wikilinks + kNN
similarity, `graph_communities` / `graph_community` / `graph_status`) → typed memory (`remember`,
rule-book recall) and the review queue (per agent, off by default since 1.30.0) → people as App API
principals (personal keys, per-vault grants) → team sharing (agents bound to people, per-vault roles,
person-authored commits) → trash listing/purge → SSRF
guard for hosted engines (`denyPrivateRemotes`) → admin-only `GET /stats` counters.
Full suite green;
CI gates every change; packaged as self-contained app-images **and** GraalVM `native-image` single
binaries for macOS / Linux / Windows (see **Download & install**). App API **contract 0.43.0**
(versioned independently of the engine). See [`docs/adr/`](docs/adr/) (ADR-0001 through 0021) for the decision record.

Deferred (documented, not hidden): encryption built into the engine. Encryption at rest is handled by host volume encryption with a key the customer holds ([ADR-0021](docs/adr/0021-encryption-at-rest.md), [paths to put on the volume](docs/security-overview.md#encryption-at-rest)).
The GraalVM `native-image` binary now ships for all three OSes (built on Oracle GraalVM/JDK 23; serves
BM25/Ollama, not in-process onnx-local — see
[ADR-0015](docs/adr/0015-native-image-and-cross-platform-releases.md)).

## License

Licensed under the **Apache License 2.0** — see [LICENSE](LICENSE). Copyright 2026 FleetQ.
