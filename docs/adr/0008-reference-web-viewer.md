# ADR-0008 — Reference web viewer

- Status: **Accepted**
- Date: 2026-06-12
- Scope: Build step 6 (the product demo)

## Context

Svod's pitch is *auditable, git-backed agent memory you can read and diff*. That needs to be
**shown**, not just described — and the demo must not depend on the personal macOS app
(ADR-0002). With the App API + WebSocket from Step 4 in place, a trivial web viewer can tell
the whole story in one screen: **watch agents write, then `git diff` their memory.**

## Decisions

### 1. A dependency-free static viewer in `examples/web-viewer/`
Three files — `index.html`, `style.css`, `app.js` — no framework, no build step. It talks
only to the App API: `GET /ready` for connection state, the `/api/v1/events` WebSocket for the
live feed, and `/api/v1/file/diff` (+ `/revision`, `/index/status`) on demand. Calm,
archival, dark-first; one accent; per-agent colored identities; a colorized git-diff pane.

### 2. Served same-origin by the App API, opt-in
When `webViewerPath` is configured, the App API serves the viewer at `/` (static files,
index default). Same origin means the viewer's WebSocket and `fetch` need **no CORS** — the
demo "just works" at `http://127.0.0.1:7517/`. It is **off by default**: the viewer is an
example, not part of the versioned API surface, and explicit API/lifecycle routes take
precedence over the static handler.

### 3. The feed is the event stream, de-duplicated by commit
The viewer renders `agent.activity` (rich: who/what/commit) and `commit.created` (API +
external writes), de-duplicating by commit id so an MCP write — which emits both — shows
once. Clicking an entry diffs `commit~1..commit` (falling back to the file's first-revision
content), rendering it red/green. `index.updated` refreshes the chunk count.

## Verification

Driven end-to-end in a real browser: two agents (Friday, Sage) wrote via MCP, the feed
streamed their activity live with per-agent identity, and clicking the `cats.md` update
rendered the colorized `git diff` of that change. A hermetic test (`WebViewerTest`) covers
same-origin serving + API-route precedence.

## Found + fixed during the demo

An MCP write with an explicit JSON `null` `expectedRevision` was wrongly treated as the
string `"null"` (`JsonNull.jsonPrimitive.content == "null"`), producing a spurious conflict
on create. Fixed in the MCP wire adapter (`JsonNull → null`) with a regression test. (The App
API was unaffected — it deserializes into a typed `String?`.)

## Consequences

- The product demo stands alone, no macOS app required.
- The engine optionally hosts the viewer; turning it off removes it entirely.
- `agent.activity` rendering is proven both in-browser and by `AppApiEventsTest`.

## Alternatives considered

- **CORS-enabled cross-origin viewer** (open the HTML file directly) — would require opening
  the App API to other origins; same-origin serving is simpler and safer. Rejected.
- **A framework (React/Svelte) build** — overkill for a reference demo and adds a toolchain.
  Rejected for plain HTML/CSS/JS.
- **Bundling the viewer into the engine jar** — couples an example to the product binary;
  a configurable directory keeps it cleanly optional. Rejected.
