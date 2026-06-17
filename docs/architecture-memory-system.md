# Architecture: Memory-system primitives

Companion to `design-memory-system.md`. Engine = Kotlin. All changes additive; existing notes/tests unaffected.

## Frontmatter contract (reserved keys; all optional)
| Key | Type | Meaning | Default when absent |
|---|---|---|---|
| `type` | string | memory class (`policy\|preference\|fact\|episode\|note`, free-form) | untyped |
| `status` | string | `active\|provisional\|revoked` | treated as `active` (visible) |
| `superseded_by` | string | path/id of the memory that replaces this one | not superseded |
| `expires_at` | ISO-8601 date/datetime | memory expiry | never expires |
| `confidence` | number | (metadata, stored in note; not indexed) | — |
| `source` | string | (metadata) | — |

Parsed by `MarkdownChunker.parse` (snakeyaml, already tolerant of malformed YAML).

## Component changes (data flow: parse → index → filter → retrieve)

1. **`index/MarkdownChunker.kt`** — `ParsedDoc` gains `type:String?`, `status:String?`, `supersededBy:String?`, `expiresAt:Long?` (epoch via existing `epochOf`). Parsed in `parse()`.
2. **`index/LuceneIndex.kt`**
   - `upsertFile(... , type, status, supersededBy, expiresAt, ...)`: index `StringField("type")`, `StringField("status")`, `StringField("superseded","true")` (only when `supersededBy != null`), `LongPoint("expiresAt")` (when present). Store `superseded_by` value as a `StoredField` for the UI.
   - `buildFilter(filters)`: add positive `type` clause; add **lifecycle exclusions** (unless `includeAll`): `MUST_NOT status:revoked`, `MUST_NOT status:provisional`, `MUST_NOT superseded:true`, `MUST_NOT expiresAt ≤ now`. If an explicit `status` filter is set → positive `status` clause and skip the status-based negatives. When only negative clauses exist, anchor with `MUST MatchAllDocs` (Lucene needs a positive). Returns null only when there is truly nothing to constrain.
   - New `enumeratePaths(filter, limit)`: distinct note paths matching a filter (for Path A).
3. **`index/SearchModels.kt`** — `SearchFilters` gains `type:String?=null`, `status:String?=null`, `includeAll:Boolean=false`. `isEmpty` still reflects **user** filters only (tags/type/status/prefix/created) — lifecycle defaults are applied inside `buildFilter` regardless, so the App API "blank q + no filter → 400" guard is preserved.
4. **`index/IndexService.kt`** — thread the 4 new fields through `FileDocs`/`EmbedPlan` → `upsertFile` (mirrors existing tags/created threading). `search()` unchanged except it now always gets a (usually non-null) lifecycle filter; legs already AND the filter in. Add `enumerate(filters, limit)` → list of `(path, fullText)` for Path A.
5. **`mcp/SvodTools.kt`** — `contextPack(agent, query, tokenBudget, enumerate)`: when `enumerate`, build blocks from `index.enumerate(query.filters)` (all matching notes, **full content, unranked, ordered by path, no budget cutoff** but a safety cap of 500 notes — logged if hit) instead of ranked hits. New **`remember`** tool (see below).
6. **`mcp/SvodMcpServer.kt`** — `parseQuery` parses `type`/`status`/`includeAll` into `SearchFilters`; `context_pack` tool gains `enumerate` arg; register `remember` (tool #14).
7. **`api/AppApiServer.kt`** — `/api/v1/search` accepts `type`, `status`, `includeAll` query params → `SearchFilters`. (No new App API endpoint; `context_pack`/`remember` are MCP-surface. `apiVersion` → 0.14.0.)
8. **`lifecycle/ApiCompatibility.kt`** — `CURRENT_CONTRACT_VERSION = "0.14.0"`.
9. **`contract/openapi.yaml`** — `/search` params (`type`,`status`,`includeAll`); document the reserved frontmatter keys + default lifecycle visibility; note the new MCP `remember` tool + `context_pack enumerate` in prose. Version 0.14.0.

## The `remember` promotion gate (MCP, WRITE role)
Args: `content` (req), `type` (default `fact`), `subject?`, `confidence?`, `source?`, `status?`, `into?` (default `memory/`), `supersedes?` (path of a memory this replaces).
Flow (mirrors the article's gate):
1. **Classify + scope** — type + the vault (scope = vault; no tenant/user rows).
2. **Dedup** — normalized content hash (`sha256` of trimmed content + type); deterministic path `memory/<type>/<hash12>.md`. If that note already exists with identical content → return `deduped` (no write).
3. **Type-specific status** — caller `status` wins; else `fact`/`policy` → `provisional` (kept out of recall until confirmed), `preference`/`episode`/`note` → `active`.
4. **Supersession** — if `supersedes` given, set that note's `status: revoked` + `superseded_by: <new path>` (a normal engine write, conflict-guarded).
5. **Write** the memory note (frontmatter `type/status/created` + optional `subject/confidence/source/superseded_by`) via the engine write-actor (secret-scanned, committed, indexed) → conflict-preserve + provenance already apply.
Returns `{ status: written|deduped, path, type, memoryStatus, superseded? }`.

## Retrieval semantics (the two paths)
- **Path B (existing `context_pack`/`search`)** — hybrid BM25+kNN+RRF(+reranker), ranked, top-k, now lifecycle-filtered (active, not provisional/revoked/superseded/expired) by default.
- **Path A (`context_pack enumerate`)** — filter-only (`type`/`tag`/`pathPrefix`), MatchAllDocs+filter, **every** match in full, unranked, deterministic by path. The "rule book every turn".

## Risks / mitigations
- **Hiding existing notes** → defaults only exclude docs that *carry* the field; untouched notes never match the exclusions. Covered by a regression test.
- **MUST_NOT-only Lucene filter matches nothing** → anchor with MatchAllDocs. Tested.
- **Enumerate unbounded size** → 500-note safety cap, logged.
- **Tool surface growth (13→14)** → accepted (scope decision); update the "13 tools" references in README.
