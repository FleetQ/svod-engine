# Test plan — memory review, rule book, remember expiry

## Engine (JUnit, `engine/src/test/kotlin/...`)

### Review list
- R1 provisional memory is listed; active is not; revoked is not; superseded provisional is not.
- R2 active memory with `needs-review: true` is listed (needsReview term).
- R3 `private: true` provisional note is not listed; `<private>` span inside a listed note is masked in `excerpt`.
- R4 `messy/sessions/` note carrying `status: provisional` is never listed.
- R5 order: needs-review / contradicts first, then newest `created`; `total` counts beyond `limit`; limit capped at 500.
- R6 a note whose file changed to active after indexing (index lag) is dropped on the re-read.
- R7 Cyrillic path and body round-trip.

### Review actions
- A1 approve: status active, `needs-review` removed, `reviewed_at`/`reviewed_by` set, body byte-identical, other keys kept in order; one commit authored by the principal; note becomes visible to `search`.
- A2 decline: status revoked, hidden from search.
- A3 reopen after approve and after decline → provisional, hidden again.
- A4 stale `expectedRevision` → 409 conflict body; file unchanged.
- A5 non-memory note (no status/type) → 400; missing path → 404; bad action → 400; superseded memory → 409 `superseded`.
- A6 reader principal → refused (403/404 per AppApiAuth), file unchanged; editor → allowed.
- A7 `commit.created` event carries `tool: memory.review` and `vault`.
- A8 dashboard `awaitingReview` equals list `total`.

### Rule book
- B1 lists active policy + preference, not provisional/revoked/superseded/expired, not other types unless `types` asks.
- B2 summary = first non-heading body line, private masked, ≤160 chars; `private: true` note excluded.
- B3 `awaitingReview` present; `limit` default 40, max 200.

### remember expiresAt
- E1 future ISO instant and future date are stored as `expires_at` ISO instant; the note disappears from search after the instant (inject clock via the existing `nowEpoch` filter parameter or an already-past value written directly).
- E2 past or unparseable → bad request, nothing written, no commit.

### MCP
- M1 `tools/list` descriptions: `promote` mentions it does not change status; `remember` lists `expiresAt`.
- M2 initialize result carries `instructions` (if the SDK supports it).

### Regression
- Full suite green; `VersionConsistencyTest`; `AppApiContractTest` covers the new routes if it enumerates routes.
- Negative check: revert each fix locally and confirm its test fails (A1, R2, E2 at minimum).

## App (XCTest, `SvodTests/`)
- U1 `MemoryReviewModel.load` fills items/total from mock; approve removes item and records undo; undo calls reopen and restores.
- U2 failure on approve restores the item and sets error; 409 triggers reload.
- U3 `MemoryDashboard` decodes with and without `awaitingReview`.
- U4 `MultiEngineClient` routes review calls to the active vault's engine.
- U5 feature gate: section hidden below 0.33.0.
- All models built in tests use an injected `UserDefaults(suiteName:)` (the XCTest host is the real app).

## Hooks (bash harness in the worktree, like `hook_harness.py`)
- H1 project from `git@github.com:FleetQ/svod-engine.git`, `https://github.com/FleetQ/svod-engine`, `ssh://git@github.com/FleetQ/svod-engine.git` → `github.com/fleetq/svod-engine`; no remote → basename.
- H2 session-start: engine down → exit 0, no output; 401 → one notice line; normal → block ≤ 6,000 chars, ≤ 40 item lines, content containing `</svod-rulebook>` cannot close the block.
- H3 installer: run twice → settings.json has exactly one entry per event; `--uninstall` removes only its entries; other hooks untouched; backup written.

## Live verification (after deploy)
- `GET /memory/review` on `personal` total ≈ provisional count measured before deploy (98 + 2 needs-review overlap).
- Approve one real memory in the app, confirm it shows in search, then reopen it (leave vault as found unless operator acts).
- Session-start hook output in a fresh `claude` session.
