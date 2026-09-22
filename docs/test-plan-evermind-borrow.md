# Test plan — EverOS borrow

## Engine (JUnit)

### Degraded search (`IndexService`)
- D1 HYBRID with an embedder whose `embedQuery` throws → `degraded == ["semantic"]`, keyword hits still returned.
- D2 SEMANTIC mode, same embedder → `["semantic"]` (and no exception).
- D3 KEYWORD mode, same embedder → `[]` (semantic was not requested).
- D4 healthy embedder, HYBRID → `[]`.
- D5 embedder `none` (inactive), HYBRID → `[]` (configuration, not failure).
- D6 active reranker that throws → `["rerank"]`, order equals the fused order; healthy reranker → `[]`.
- D7 blank query + tag filter (browse) with a failing embedder → `[]`.
- D8 during an embedding-model rebuild (`setEmbedder`, semantic suppressed): HYBRID and SEMANTIC both report
  `["semantic"]` and answer from keyword hits.

### API / MCP
- A1 `GET /api/v1/search` JSON carries `"degraded": []` on a healthy vault and `["semantic"]` with a failing
  embedder.
- A2 `across=true` → union over vaults.
- M1 MCP `search` result JSON carries `degraded`; `context_pack` too.
- C1 `VersionConsistencyTest` with 1.25.0; contract version 0.34.0 in `ApiCompatibility` and `openapi.yaml`.

### Negative check
Revert the `semanticLeg` change and the `maybeRerank` change one at a time; D1 and D6 must fail.

## App

### Swift (XCTest)
- S1 `SearchResult` decodes without `degraded` (0.33.0 engine) → `[]`; with `["semantic"]` → that.

### Hooks (`Scripts/test-claude-hooks.py`)
- H4 `SVOD_CAPTURE=off` → capture posts nothing; unset → posts (existing H1 path).

### Narrative job (`Scripts/test-project-narrative.py`, fake engine + fake `claude`)
- N1 slug matches the engine rule (`github.com/karlovotech/resheno` → `github-com-karlovotech-resheno`).
- N2 `<private>` spans removed; an unclosed `<private>` drops the rest; frontmatter stripped.
- N3 first run for a project: note created with frontmatter `covered_until` = newest endedAt, `sessions_folded`.
- N4 second run with no new sessions → no model call, no write.
- N5 fewer than MIN_NEW new sessions → skipped.
- N6 job-run sessions (distiller marker) are never sent to the model.
- N7 byte budget: only the newest sessions that fit are sent, presented oldest-first; `covered_until` = newest.
- N8 engine 409 → logged, next project still processed; 422 → same.
- N9 model output empty / without the `# <project> — narrative` H1 / with CJK or Hangul characters / with
  Russian-only letters (ы э ё, when the language is Bulgarian) → nothing written. A literal `<private>` tag in
  the answer is the feature's name, not private content (that was stripped before the model saw anything):
  it is written as `‹private›`, because an unclosed tag would hide the rest of the note from search.
- N13 `NARRATIVE_REBUILD=1` ignores the current narrative and `covered_until`; the write is still guarded by
  `expectedRevision`.
- N10 the model is invoked with `--tools ""` and `SVOD_CAPTURE=off` in its environment.
- N11 dry run → no model call, no write.
- N12 engine down → exit 0, one log line.

### Live (manual, once)
- L1 `NARRATIVE_DRY_RUN=1` against the live engine: plan lists the real projects and session counts.
- L2 one real run restricted to one small project (`NARRATIVE_PROJECTS=svod-ui-macos`): note appears in
  `narratives/`, is committed, is found by `search`, contains no private text.
- L3 after deploy: a search with the embedder endpoint forced down shows `degraded: ["semantic"]`.
