# Architecture — EverOS borrow (engine 1.25.0 / contract 0.34.0)

Design: `design-evermind-borrow.md`.

## A. `degraded` search results

```
IndexService.search(q)
  ├─ keywordLeg
  ├─ semanticLeg ── returns null on a failed query embed          ──┐
  │   (skipped when suppressSemantic — counted as degraded too)     ├─► SearchResult.degraded: List<String>
  └─ maybeRerank ── reports failure instead of swallowing it      ──┘
```

- `SearchResult(hits, mode, tookMillis, degraded: List<String> = emptyList())`. Values are the constants
  `SearchResult.SEMANTIC = "semantic"`, `SearchResult.RERANK = "rerank"`, in that order.
- "semantic" is set when `q.mode != KEYWORD && !blankQuery && embedder.isActive && (suppressSemantic || embed threw)`.
- "rerank" is set when the reranker is active, it was called, and it threw. A candidate that vanished mid-flight is
  not a provider failure and does not set it.
- App API: `SearchResultDto(mode, hits, degraded: List<String> = emptyList())`. `jsonFormat` has
  `encodeDefaults = true`, so the key is always present on 0.34.0. `across=true` returns the union over vaults, in
  the canonical order.
- MCP: `search` and `context_pack` add `"degraded": [...]` to their JSON. Tool descriptions say what it means.
- Contract: `SearchResult.degraded` optional array of `semantic | rerank`; version 0.34.0. Additive: old clients
  ignore it; the app decodes it with `decodeIfPresent` and defaults to `[]`.

## B. Capture opt-out (svod-ui-macos)

`capture-session.sh`: `[ "${SVOD_CAPTURE:-}" = "off" ] && exit 0` before reading stdin. Claude Code passes its
environment to hook processes, so a job that exports it for its own `claude -p` is never captured. Installed copies
live in `~/.claude/hooks/svod/` and are refreshed by `Scripts/install-claude-hooks.sh`.

## C. `Scripts/project-narrative.py` (svod-ui-macos)

One Python file (stdlib only, `/usr/bin/python3`), pure functions + `main()`, so tests import it.

```
GET  /ready
GET  /api/v1/memory/sessions?vault=V           → [{path, project, endedAt, bytes, …}]
group by project (skip null project; a bare legacy label such as "svod-ui-macos" folds into the
  single host/owner/repo label whose last segment it equals)
for each project:
   GET /api/v1/file?path=narratives/<slug>.md  → current note + revision (404 ⇒ new; any other error ⇒ skip,
                                                 never "new")
   new = sessions with endedAt > covered_until, excluding job runs
   if len(new) < MIN_NEW: skip
   bodies = GET /api/v1/file for each, newest-first until BYTE_BUDGET; strip frontmatter + <private>
   prompt = template + current narrative body + sessions (oldest-first)
   claude -p --model M --tools "" --no-session-persistence   (stdin = prompt, stdout = new body)
       env: SVOD_CAPTURE=off ; watchdog timeout
   validate: first line is "# <project> — narrative"; no CJK/Hangul; no ы/э/ё for Bulgarian;
             a literal <private> tag is rewritten to ‹private›
   PUT /api/v1/file?path=… {content, expectedRevision}
        200 → log ; 409 → skip (someone edited it) ; 422 → skip (secret found)
```

- Slug = the engine's `SessionNotes.slug` rule: lowercase, every non letter/digit → `-`, trimmed, ≤ 40 chars.
- Frontmatter written by the script, not the model:
  `type: narrative`, `project`, `covered_until` (epoch ms of the newest folded session), `sessions_folded`
  (cumulative), `updated` (ISO date), `source: project-narrative`. H1 comes from the model's body
  (`# <project> — narrative`), per the vault's AGENTS.md (H1 is the title).
- Job-run detection: body contains `RUNTIME CONTEXT (this run)` or `SVOD-NARRATIVE-JOB`. The narrative prompt
  itself carries `SVOD-NARRATIVE-JOB`.
- Config (env): `SVOD_ENGINE` (default `http://127.0.0.1:7619`), `SVOD_VAULT` (`personal`), `NARRATIVE_MODEL`
  (`sonnet` — the first real run with Haiku mixed Chinese/Korean characters and Russian words into Bulgarian
  and confused version numbers), `NARRATIVE_REBUILD=1` (rewrite from scratch), `NARRATIVE_MIN_NEW` (2), `NARRATIVE_BYTE_BUDGET` (300000), `NARRATIVE_TIMEOUT` (600 s per
  project), `NARRATIVE_PROJECTS` (optional comma list to restrict), `NARRATIVE_DRY_RUN=1` (no model, no write:
  prints the plan), `CLAUDE_BIN`.
- Log: `~/Library/Logs/svod/project-narrative.log`. Exit 0 always (launchd job), like the distiller.
- Prompt: `.claude/hooks/project-narrative-prompt.md`.
- Staged plist: `Scripts/dev.svod.project-narrative.plist` (Weekday 0, Hour 3). Not copied, not bootstrapped.

## D. App (Swift)

- `SearchResult.degraded: [String]` with a custom `init(from:)` (`decodeIfPresent ?? []`); memberwise init keeps a
  default so existing call sites compile.
- `MultiEngineClient` passes it through untouched.
- `SearchModel.degraded: [String]`, set from each result, cleared with the results.
- `CommandPaletteView`: one caption line under the filters when non-empty, worded by the current mode:
  semantic with results → "keyword results only"; semantic with no results in Semantic mode → "nothing to show in
  Semantic mode — switch to Hybrid or Keyword"; rerank → "results are not reranked".
