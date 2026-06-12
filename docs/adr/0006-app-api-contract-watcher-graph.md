# ADR-0006 — App API, OpenAPI contract, watcher, and graph link-integrity

- Status: **Accepted**
- Date: 2026-06-12
- Scope: Build step 4 (the UI-facing API + the knowledge graph)

## Context

The UI (and any other client) needs a stable, versioned surface to drive the engine and to
watch what agents are doing live. Separately, a knowledge base lives and dies by its links:
a rename must not silently break `[[wikilinks]]`. And the vault may be edited outside the
engine (another editor, a `git pull`), which the system must absorb without losing the
single-writer guarantees.

## Decisions

### 1. Contract-first: `contract/openapi.yaml` is the source of truth
The OpenAPI 3 spec was written **before** the server and is versioned independently
(`info.version 0.1.0`). A contract test validates **every live App API response against the
spec** (via swagger-request-validator) and asserts the implemented routes exactly equal the
declared paths — the build fails if the server drifts from the contract. This is what makes
the UI swappable without codegen.

### 2. App API binds 127.0.0.1 only; no per-agent auth
The App API is loopback-trusted and acts as a single UI identity (`svod-ui`), per invariant
7. Remote/multi-agent access is exclusively the MCP endpoint (ADR-0005), which has the
tokens, roles, and rate limits. Keeping auth off the App API avoids duplicating a security
surface that loopback already bounds.

### 3. File paths as query parameters, not path segments
File endpoints take `?path=` rather than `/{path}`. Vault paths contain `/` and Cyrillic;
a query param sidesteps slash-in-path-template ambiguity and URL-encoding pitfalls, and
keeps the OpenAPI paths clean and validatable.

### 4. Live events over one WebSocket, lossy under backpressure
A shared in-process `EventBus` (`MutableSharedFlow`, DROP_OLDEST) is published by engine
mutations, MCP tool calls (`agent.activity`), the indexer (`index.updated`), and the watcher
(`file.changed`). The App API streams it over `/api/v1/events`. Publishing is non-blocking
and may drop under extreme load, so a slow UI can never stall the write path or the indexer.
Event types match the contract: `file.changed, index.updated, commit.created, conflict,
engine.status, agent.activity`.

### 5. Link-integrity is a single-commit transaction on the write-actor
`moveWithLinks` snapshots all notes, moves the file, rewrites every `[[wikilink]]` that
resolved to it, and commits **all of it in one commit** — on the write-actor, so it can't
interleave with another write. `LinkRewriter` preserves `#heading`/`|alias` suffixes and
both link styles (path vs basename), and refuses to touch **ambiguous** basename links
(a basename shared by ≥2 notes), since those weren't resolving to one note anyway. Both the
MCP `move` tool and the App API move go through this, so a rename never breaks backlinks.

### 6. The watcher absorbs external edits via actor-serialized ingest
`FileWatcher` (io.methvin/FSEvents) debounces filesystem changes and runs
`ingestExternalChanges` **on the write-actor**: if the working tree is dirty it commits the
change as an `external` author, then reindexes and emits events. Because ingest is
actor-serialized, it can never race an engine write — a change the engine itself made is
already committed, so ingest is a no-op for it. No double-commits, no feedback loop.
*(Both proven by test.)*

### 7. Lightweight graph now; tags + global graph included
`LinkGraph` resolves outlinks/backlinks/unresolved and exposes nodes/edges for the graph
view; tag taxonomy is built from frontmatter. Graph/tags are cached by HEAD. This completes
the graph subsystem the spec scoped to Step 4.

## Consequences

- The UI is buildable against a frozen, tested contract; engine and UI version independently.
- A rename is safe: references follow the note, atomically and attributably.
- The vault is editable outside the app without corrupting history or the index.
- `conflicts` is a contract endpoint returning empty until **multi-host sync (Step 7)**
  populates it; the shape is fixed now so the UI needn't change later.

## Alternatives considered

- **Hand-written response assertions instead of OpenAPI validation** — wouldn't catch schema
  drift; the validator ties tests to the contract itself. Rejected.
- **Path-segment file routes (`/files/{path...}`)** — slash/encoding friction and weaker
  OpenAPI validation. Rejected for query params.
- **Rewriting links lazily / on read** — leaves stale link text in the stored notes (not
  diffable, not portable). Rejected for eager transactional rewrite.
- **Watcher committing off the actor** — would break the single-writer invariant and risk
  racing engine writes. Rejected for actor-serialized ingest.
- **SSE instead of WebSocket for events** — fine one-way, but WebSocket keeps a single
  upgrade path open for future client→server messages. Chose WebSocket.
