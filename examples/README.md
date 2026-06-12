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

## FleetQ integration

FleetQ attaches to Svod like any other MCP client — a first-party *reference* integration
and proof point, **not** an embedded client. The engine itself stays FleetQ-agnostic (see
[ADR-0002](../docs/adr/0002-repo-split-and-license.md)). The FleetQ example will live here
once the MCP server (Step 3) is in place.
