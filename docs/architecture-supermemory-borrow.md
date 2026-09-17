# Architecture — memory review, rule book, remember expiry (contract 0.33.0)

Design: `docs/design-supermemory-borrow.md`.

## Engine

### Index (S2)
`LuceneIndex.MemoryMeta` gains `needsReview: Boolean`. `upsertFile` adds `StringField("needsReview", "true")`
when set. `MarkdownChunker.ParsedDoc` exposes `needsReview` parsed from `needs-review` / `needsReview`
(boolean or "true"). No schema version bump (see design, out of scope).

`IndexService` gets `enumerateReview(limit)`: union of
`enumeratePaths(status=provisional filter)` and `enumeratePaths(needsReview=true, default hiding off for status but
revoked still excluded)`, both excluding superseded, `messy/sessions/`. Implementation may build the two
`Query` objects directly in `LuceneIndex` rather than going through `SearchFilters`.

### Review service (S1)
New `dev.svod.engine.memory.MemoryReview` (pure helpers + one orchestrating function), used by the App API:

```
data class ReviewItem(path, title, excerpt, type?, status?, subject?, confidence?, source?, created?,
                      contradicts?, supersedes?, needsReview, revision)
fun list(engine, index, limit): Pair<List<ReviewItem>, Int /*total*/>
fun apply(engine, path, action: approve|decline|reopen, expectedRevision?, author): ReviewOutcome
```

- `list` reads each enumerated path, re-parses frontmatter (the index can lag a just-written file; a path
  whose current frontmatter no longer qualifies is dropped), skips `private: true`, builds the excerpt
  from the body with `<private>` masked (reuse the existing masking helper used by `context_pack`/grep),
  collapsing whitespace, max 280 chars. Sort per design D4.
- `apply` reads the file, validates D2/D3, rewrites frontmatter preserving key order and body
  (reuse `frontmatterFences` from `SvodTools`, moved to a shared place if needed), writes via
  `engine.write(path, text, expectedRevision ?: current.revision, author)` so the secret scanner and
  commit path are the normal ones. Returns the `WriteOutcome` plus the new status.

### App API
- `GET /api/v1/memory/review?vault=&limit=` → `MemoryReviewListDto { total, items: [MemoryReviewItemDto] }`
- `POST /api/v1/memory/review?vault=` body `MemoryReviewActionDto { path, action, expectedRevision? }`
  → 200 `MemoryReviewResultDto { path, revision, commit, status }`; 400 bad action / not a memory;
  404 missing; 409 conflict (`ConflictDto`) or `superseded` (`ErrorDto`). Publishes `commit.created` with
  `tool: "memory.review"`. Readers are refused by `AppApiAuth` (non-GET) — no route-level check needed,
  but it is tested.
- `GET /api/v1/memory/rulebook?vault=&types=policy,preference&limit=` →
  `MemoryRulebookDto { awaitingReview, items: [{path, title, type, subject?, summary}] }`. Items are the
  default-visible (active, not superseded, not expired, not messy) notes of those types, sorted by type
  then title; `summary` is the first non-heading body line, `<private>` masked, ≤ 160 chars. Default limit 40, max 200.
- `MemoryDashboardDto` gains `awaitingReview: Int` (additive).
- `ApiCompatibility.CURRENT_CONTRACT_VERSION` → `0.33.0`; `contract/openapi.yaml` documents all of the above.

### MCP (S3, S4)
- `remember` schema adds `expiresAt` (string). Parsed as `Instant` or `LocalDate` (start of day UTC).
  Unparseable or not in the future → bad request, nothing written. Stored as `expires_at: <ISO instant>`.
  Classification is unchanged.
- Server `instructions` (if kotlin-sdk 0.13.0 `ServerOptions`/`Server` exposes it; otherwise report and skip):
  a short paragraph — which tool for which job (search = meaning, grep = exact strings, tree/list = orientation,
  context_pack enumerate = rule book, remember = durable typed memory; fact/policy stay provisional until a
  person approves them in the Svod app; promote does not change memory status).
- Descriptions: `promote` says it only moves a `messy/` draft and does not change `status`; `list` points to
  `tree` for orientation; `search` points to `grep` for exact strings; `remember` documents `expiresAt` and the
  provisional gate.

### Version
`build.gradle.kts` version and `SvodNode.currentAppVersion` → `1.23.0` (guarded by `VersionConsistencyTest`).
CHANGELOG entry.

## App (svod-ui-macos)

- DTOs: `MemoryReviewItem`, `MemoryReviewList`, `MemoryReviewResult`, `MemoryRulebook` (not used by the UI;
  skip unless needed), `MemoryDashboard.awaitingReview: Int?` (optional — older engines omit it).
- `SvodClient`: `memoryReview(limit:)`, `reviewMemory(path:action:expectedRevision:)`; `LiveSvodClient`,
  `MultiEngineClient` (route to `current`), `MockSvodClient` (in-memory state, per instance).
- `MemoryReviewModel` (`@MainActor ObservableObject`, `App/`): items, total, busy, error, `lastActions`
  (path → previous status for Undo), `load()`, `approve/decline/undo(item)`; removes an acted item optimistically,
  restores it on failure; 409 → reload + message.
- `MemorySettingsView`: new first section "Awaiting review (N)" when `apiVersionAtLeast("0.33.0")`, rows with
  title, type chip, subject, confidence, contradicts/supersedes, excerpt, buttons Approve / Decline / Open,
  Undo for items acted on in this view. Overview shows the count.
- `MemoryBadgesBar`: `needs-review` badge, `contradicts` and `supersedes` links (tap opens the note),
  and Approve / Decline buttons when status is provisional or needs-review is true, the engine supports review,
  and the editor is not read-only. After an action the editor reloads the note.
- Hooks (`.claude/hooks/`): `capture-session.sh` project = git remote `origin` normalized to `host/owner/repo`
  (lowercase, no scheme/user/`.git`), fallback basename of `$CLAUDE_PROJECT_DIR`/cwd;
  new `session-start-rulebook.sh`; new `Scripts/install-claude-hooks.sh` copies both to
  `~/.claude/hooks/svod/` and registers them idempotently in `~/.claude/settings.json` with `jq`
  (backup first, `--uninstall` removes exactly its own entries), and the project-level wiring in
  `.claude/settings.json` is removed so this repo does not capture twice.
- Version `0.2.25` build `27`.
