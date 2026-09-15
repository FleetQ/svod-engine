# Design — what we took from OpenViking (and the capture bug it exposed)

Source research: `svod-ui-macos/claudedocs/research_openviking_2026-09-15.md` (OpenViking `b7bd0b5`, v0.4.20).
Companion docs: `architecture-openviking-borrow.md`, `test-plan-openviking-borrow.md`.

## The forcing questions (answered from evidence, not from the research's first ranking)

**Who needs this, what do they do today?**
- The operator's recall loop. A Claude Code `Stop` hook POSTs the session transcript to
  `/api/v1/memory/capture`; a nightly distiller turns sessions into draft notes. Today the engine
  dedups on `sessionId` and returns the existing note untouched, while `Stop` fires after **every**
  response — so only the transcript up to the first response is ever stored. Observed: all 8
  captured sessions for `svod-ui-macos` are 965 B – 5.8 KB; the project's transcripts run to MBs.
- MCP agents browsing a vault. They have `list(pathPrefix)` (every path — 3,384 on `personal`) and
  ranked `search`. There is no cheap view of the folder structure and no exact-text search.

**Narrowest thing worth shipping?**
1. Capture stores the whole session (the bug).
2. Two read-only MCP tools: `tree` (folder counts to a depth) and `grep` (exact text / regex, line hits).

**What makes it "whoa"?** An agent can answer "where is everything about X" with one `tree` call
instead of dumping thousands of paths, and "which notes mention `127.0.0.1:7619`" exactly — BM25
tokenisation cannot answer that reliably.

**How does it compound?** The distiller only ever saw the first turn of each session; fixing
capture is what makes every later memory feature (distill, experience memory) have input at all.

## Deliberately NOT built — each with the measurement that decided it

| Idea (research #) | Decision | Evidence |
|---|---|---|
| Auto-recall on every prompt (1) | **Dropped** | 5 realistic prompts against live `personal`: 0.5–7.6 s latency, 1 of 5 top hits relevant, fused scores 0.048–0.065 — essentially rank-only, no threshold separates useful from noise. Every injected block also rides the context for the rest of the session. |
| Summary coverage + manual edits (3) | Deferred | `Community.addedSinceSummary` already discloses drift; the prompt-side coverage footer already hedges the summary text; `.svod/graph/` is not in git, so OpenViking's stable sampling (which exists to avoid noisy git diffs) buys nothing here. No request for manual edits. |
| Experience memory (5) | Deferred | Needs capture to work first (this sprint) and the distiller to be enabled (it is staged, not loaded). |
| LLM query rewrite (6), hierarchical retrieval (7) | Not now | Put an LLM or a new ranking path into search; both need leg-V measurement before code. |

## Constraints carried in

- Session transcripts stay quarantined: `messy/sessions/` is excluded from recall on every path
  (`LuceneIndex.buildFilter`). `grep` must not become the escape.
- `<private>` spans and `private: true` notes never leave through a recall surface.
- Engine stays LLM-free.
- Git write amplification: capture commits a full transcript blob each time it writes, so "write on
  every `Stop`" is not acceptable for multi-hour sessions.
