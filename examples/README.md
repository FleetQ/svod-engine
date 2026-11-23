# Svod examples

Reference integrations and demos for the Svod engine. These are **examples**, not
supported product surfaces.

## Reference web viewer — `web-viewer/` ✅ built

A trivial, dependency-free web viewer (`index.html` + `style.css` + `app.js`, no build step)
that tells the Svod story in one screen:

> **Watch agents write, then `git diff` their memory.**

It consumes the engine's **App API + WebSocket** (`agent.activity`, `commit.created`,
`index.updated`, …) to show a live, per-agent activity feed; click any change to see the
colorized `git diff` of that write.
A **Memory review** pane lists the review queue (`GET /api/v1/memory/review`) and approves, declines
or reopens a memory through `POST /api/v1/memory/review` with its `expectedRevision`.

### Run it

Set `webViewerPath` in your engine config to this directory, then open the App API root:

```jsonc
// config.json
{ "...": "...", "webViewerPath": "/Users/you/htdocs/svod/examples/web-viewer" }
```

```sh
open http://127.0.0.1:7517/
```

The engine serves it **same-origin** (so its WebSocket + fetch need no CORS). It is off
unless `webViewerPath` is set. You can also point the endpoint box at any reachable engine.
See [ADR-0008](../docs/adr/0008-reference-web-viewer.md).

## Agent frameworks

Copy-paste MCP setups for LangGraph / LangChain, the OpenAI Agents SDK, the Vercel AI SDK and Mastra
live in [Use Svod from your agent framework](../docs/integrations-agent-frameworks.md). Each one
connects to the engine's MCP endpoint with a bearer token and calls `search` and `remember`.

## Retrieval eval

[`golden.sample.jsonl`](golden.sample.jsonl) is a commented example golden set for
`svod-engine eval <config.json> <golden.jsonl>`, which scores search on your own vault (engine stopped).
Replace its paths with notes from your vault. Format, metrics and baseline numbers:
[Retrieval quality](../docs/retrieval-quality.md).

## FleetQ integration

FleetQ attaches to Svod like any other MCP client — a first-party *reference* integration
and proof point, **not** an embedded client. The engine itself stays FleetQ-agnostic (see
[ADR-0002](../docs/adr/0002-repo-split-and-license.md)). The FleetQ example will live here
once the MCP server (Step 3) is in place.
