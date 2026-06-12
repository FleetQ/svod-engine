# Svod examples

Reference integrations and demos for the Svod engine. These are **examples**, not
supported product surfaces.

## Reference web viewer — *planned, built at Step 4*

A trivial, dependency-light web viewer that tells the Svod story in one screen:

> **Watch agents write, then `git diff` their memory.**

It will consume the engine's **App API + WebSocket** (`agent.activity`, `commit.created`,
`file.changed`) to show a live feed of what each agent is writing, with a one-click jump to
the underlying git diff — making "auditable, git-backed agent memory" tangible.

**Status: not built yet.** It depends on the App API, which lands in **Step 4** of the
build order. There is no HTTP surface to build against before then. See
[`../docs/build-order.md`](../docs/build-order.md).

## FleetQ integration

FleetQ attaches to Svod like any other MCP client — a first-party *reference* integration
and proof point, **not** an embedded client. The engine itself stays FleetQ-agnostic (see
[ADR-0002](../docs/adr/0002-repo-split-and-license.md)). The FleetQ example will live here
once the MCP server (Step 3) is in place.
