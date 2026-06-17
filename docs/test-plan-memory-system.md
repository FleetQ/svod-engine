# Test plan: Memory-system primitives

Companion to `architecture-memory-system.md`. New tests in `engine/src/test`. Full suite must stay green (currently 167).

## Unit — typing & lifecycle (IndexService/LuceneIndex; new `MemoryIndexTest`)
1. **Type filter** — index notes with `type: policy` / `type: fact` / untyped; `search(filters.type="policy")` returns only policy notes.
2. **Backward compat (critical)** — a plain note with NO `status/superseded_by/expires_at/type` is fully visible in default search (defaults must not hide existing content).
3. **Hide revoked** — `status: revoked` note absent from default search; present with `includeAll=true`.
4. **Hide provisional** — `status: provisional` note absent from default search (Path B); present with `status="provisional"` filter or `includeAll`.
5. **Active visible** — `status: active` (and untyped) visible by default.
6. **Superseded hidden** — note with `superseded_by: x` absent by default; present with `includeAll`.
7. **Expired hidden** — `expires_at` in the past → hidden; `expires_at` in the future → visible.
8. **MUST_NOT anchor** — a plain text query (no user filter) still returns active notes (lifecycle filter doesn't zero out results).
9. **Explicit status filter** — `status="provisional"` returns provisional notes (positive filter overrides the default hide).

## Unit — Path A enumeration (`context_pack enumerate`; in `MemoryToolsTest`)
10. **Enumerate returns all in full** — 3 notes `type: policy`; `contextPack(enumerate, type=policy)` returns 3 blocks, each full content, regardless of token budget.
11. **Enumerate is unranked/deterministic** — order by path, stable across calls.
12. **Enumerate respects lifecycle** — a revoked policy is excluded from enumerate by default.

## Unit — `remember` gate (`MemoryToolsTest`)
13. **Write fact → provisional** — `remember(type=fact, content)` writes a note with `status: provisional`, deterministic `memory/fact/<hash>.md`, returns `written`.
14. **Preference → active** — `remember(type=preference,...)` → `status: active`.
15. **Dedup** — calling `remember` twice with identical content+type → second returns `deduped`, no second note.
16. **Supersession** — `remember(..., supersedes=oldPath)` sets old note `status: revoked` + `superseded_by`; old note then hidden from default search, new note present.
17. **Secret-scanned** — `remember` content tripping the scanner is blocked (reuses engine write-path scan).
18. **Role guard** — a READ_ONLY agent calling `remember` is denied (existing `guarded` path).

## Contract / API (`AppApiContractTest`)
19. **/search type+status params** — `GET /search?type=policy` and `?includeAll=true` conform to the contract and return expected sets.
20. Contract paths/version unchanged set still matches (no new App API path; version 0.14.0).

## End-to-end (live binary, throwaway engine — not user vaults)
21. Write `memory/preference/*.md` via MCP `remember`; `context_pack enumerate type=preference` returns it in full; revoke via supersession → disappears from default search, still on disk. Manual or scripted.

## Acceptance gates
- Backward-compat test (#2) MUST pass — non-negotiable.
- Full suite green; new tests cover each of typing, each lifecycle state, enumerate, dedup, supersession.
