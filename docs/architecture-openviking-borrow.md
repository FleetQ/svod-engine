# Architecture — whole-session capture, MCP `tree` and `grep`

Engine v1.22.0, App API contract 0.32.0. Hook changes live in `svod-ui-macos/.claude/`.

## 1. Capture: replace a session note when its transcript grew

Verified facts the design rests on:
- Claude Code keeps pre-compaction messages in the transcript JSONL: a 6.6 MB file has its
  `compact_boundary` at line 2383 of 4022, one `sessionId`, user turns before and after.
  So transcripts only grow within a session.
- `SessionEnd` and `PreCompact` hook payloads carry `session_id` and `transcript_path` (Claude Code
  hooks docs), same as `Stop`.
- Compacting a 5.3 MB transcript with the hook's jq filter takes 0.08 s and yields ~216 KB.

### Engine (`POST /api/v1/memory/capture`)

```
existing = session note with this sessionId
none            -> create (unchanged)                        deduped=false updated=false
newBytes <= old -> no write                                  deduped=true  updated=false
newBytes >  old -> rewrite the SAME path, expectedRevision   deduped=false updated=true
                   startedAt = min(old, new), endedAt = new, distilled = false
```

- **Grow-only.** A payload that is not larger is a duplicate or a late delivery; it must never
  shrink a stored transcript. Byte size (UTF-8 of the compacted transcript) is the comparison —
  the same number the note already stores as `bytes`.
- **`distilled` resets to false** when a distilled session grows: the new tail has not been
  distilled. Cost: the distiller may write a second draft for that session (drafts, never promoted).
- **Optimistic write** with the read revision; a concurrent writer → 409 like every other write.
- `CaptureResult.updated` is additive (contract 0.32.0). `deduped` keeps its meaning "nothing written".

### Hook (`capture-session.sh`, registered on `Stop`, `PreCompact`, `SessionEnd`)

- `PreCompact` / `SessionEnd`: always POST.
- `Stop`: POST only on the first capture of a session, or once the compacted transcript is at
  least **2×** the size last accepted. State: `${TMPDIR}/svod-capture/<session_id>` holding that
  byte count, written only after a 2xx. Commits per session ≈ log2(final/first) + compactions + 1
  (a 3 KB → 216 KB session: ~7), instead of one per response.
- A crash or killed terminal (no `SessionEnd`) loses at most the part since the last doubling.
- Compact-summary entries (`isCompactSummary: true`) are dropped: they restate earlier turns.
- Still best-effort: every path exits 0; engine down ⇒ no state written ⇒ the next event retries.

## 2. MCP `tree`

`tree(pathPrefix?, depth = 2)` → folders under the prefix down to `depth` (clamped 1..10), each with
the recursive file count, plus files sitting directly at the prefix.

```json
{"status":"ok","prefix":"projects/","depth":2,"totalFiles":812,"rootFiles":3,
 "folders":[{"path":"projects/svod/","files":140}, ...],"truncated":false}
```

Same source and visibility as `list` (`SvodEngine.list()`: user files, dot-dirs excluded) — it only
aggregates what `list` already returns. Capped at 500 folders (`truncated`).

## 3. MCP `grep`

`grep(pattern, pathPrefix?, literal = false, ignoreCase = false, limit = 50)` → line hits
`{path, line, text}` over `.md` notes (`SvodEngine.readAllNotes()`, one actor pass; matching runs off
the actor).

Visibility — `grep` is a recall surface and follows the recall rules, not `read`'s:
- `messy/sessions/` — always skipped (no escape, like `buildFilter`).
- `messy/` — skipped unless `includeMessyInRecall` or the caller's `pathPrefix` starts with `messy/`
  (the same two escapes search has; `includeAll` does not exist on grep).
- `private: true` notes — skipped.
- `<private>` spans — masked before matching, **keeping their newlines**, so reported line numbers
  stay true to the file an agent may then `edit`. An opening tag with no closing tag hides the rest of
  the note — a fix to the shared span regex, so it also closes the same hole in the index,
  `context_pack` and graph prompts.

Safety:
- Invalid regex → `bad_request`.
- Catastrophic backtracking cannot pin a CPU: the text is wrapped in a `CharSequence` that throws
  once a per-call deadline (2 s) passes; the result returns what it has with `timedOut: true`.
- `limit` clamped 1..500; `text` trimmed to 240 chars; `truncated` when the limit cut results.

Both tools are read-only (`guarded(write = false)`) and rate-limited like every tool. Tool count 18 → 20.
