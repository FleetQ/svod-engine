# Test plan — whole-session capture, MCP `tree` and `grep`

Companion to `architecture-openviking-borrow.md`. Every case names what it catches. The ones marked
**(neg)** are run once with the fix reverted and must fail.

## C — capture (`MemoryApiTest`)

| id | case | expectation |
|---|---|---|
| C1 **(neg)** | capture, then the same `sessionId` with a longer transcript | one note, same path, body = new transcript, `bytes` updated, `updated=true`, `deduped=false` |
| C2 | same `sessionId`, equal or shorter transcript | `deduped=true`, `updated=false`, stored body unchanged |
| C3 | distilled session receives a longer transcript | `distilled` back to `false` |
| C4 | re-capture keeps `startedAt` = min, `endedAt` = new | frontmatter values |
| C6 **(neg)** | 10 rounds × 8 concurrent captures of growing sizes | stored size equals the largest capture that answered 200 (a smaller one must not land after it) |
| C5 | contract | `CaptureResult.updated` in openapi; contract version 0.32.0 everywhere (`VersionConsistencyTest`) |

C2 guards the grow-only rule: without it a late small payload would truncate a long session.

## T — `tree`

| id | case | expectation |
|---|---|---|
| T1 | notes in `a/x.md`, `a/b/y.md`, `a/b/c/z.md`, `r.md`; depth 1 | folders `[a/ (3)]`, `rootFiles=1`, `totalFiles=4` |
| T2 | same, depth 2 | `a/ (3)`, `a/b/ (2)` — counts are recursive |
| T3 | `pathPrefix = "a/"` | folders relative to the prefix, `rootFiles=1` (`a/x.md`) |
| T4 | read-only agent | allowed |

## G — `grep`

| id | case | expectation |
|---|---|---|
| G1 | literal `127.0.0.1:7619` | hit with path, 1-based line, trimmed text; `.`/`:` not treated as regex |
| G2 | regex + `ignoreCase` | matches differing case |
| G3 | invalid regex `(` | `bad_request` |
| G4 **(neg)** | needle inside a multi-line `<private>` span; a public needle after it | span needle never returned; public hit's line number equals its line in the raw file |
| G4b **(neg)** | `<private>` with no closing tag | nothing after the tag is returned (review finding: the old span regex failed open everywhere it was used) |
| G5 | needle in a `private: true` note | no hit |
| G6 **(neg)** | needle in `messy/sessions/…` | no hit, also with `pathPrefix = "messy/sessions/"` |
| G7 | needle in `messy/draft.md` | no hit by default; hit with `pathPrefix = "messy/"` |
| G8 | 5 hits, `limit = 2` | 2 hits, `truncated=true` |
| G9 | `(a+)+$` against a long `aaaa…b` line | returns well under 10 s with `timedOut=true` |
| G12 **(neg)** | `(a|b)*c` over a 40,000-char line plus a short matching line | `ok`, `unsearchableLines ≥ 1`, the short line still hit (the stack overflow the gate found) |
| G10 | tool count | 20 tools over streamable HTTP, stateless and TLS |

## H — hook (`svod-ui-macos/.claude/hooks/capture-session.sh`, against a fake engine on a spare port)

| id | case | expectation |
|---|---|---|
| H1 | first `Stop` | POST, state = bytes |
| H2 | `Stop` again, transcript < 2× | no POST |
| H3 | `Stop` after transcript ≥ 2× | POST, state updated |
| H4 | `PreCompact` with no growth | POST |
| H5 | `SessionEnd` | POST, state file removed |
| H6 | engine down | exit 0, no state written |
| H7 | transcript with an `isCompactSummary` entry | summary text absent from the posted transcript |
| H8 | real `claude -p` run with the hook on `SessionEnd` | the fake engine receives a POST with the run's `sessionId` |

## Live (after deploy)

- `/ready` 200, `update/check` current = 1.22.0, MCP `tools/list` has 20 tools.
- `tree` and `grep` on `personal` answer, `grep` for a `messy/sessions/` needle returns nothing.
- This session's own captured note grows after the new hook runs (bytes before vs after).
