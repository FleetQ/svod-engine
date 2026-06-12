# ADR-0005 — MCP server: tools, auth, audit, and the messy/ namespace

- Status: **Accepted**
- Date: 2026-06-12
- Scope: Build step 3 (the agent-facing MCP server)

## Context

Agents are the primary writers of Svod. They reach the engine over the **MCP** endpoint
(separate from the UI's App API), each with its own identity, so that every change is
attributable and auditable. The endpoint must be safe to expose to multiple agents on
multiple hosts: authenticated, role-scoped, rate-limited, and fully logged.

## Decisions

### 1. Official Kotlin MCP SDK over streamable HTTP (Ktor)
We use `io.modelcontextprotocol:kotlin-sdk-server` 0.13.0 with the streamable-HTTP transport
on an embedded Ktor (CIO) server. This pulled the project's Kotlin to **2.3.21** and Ktor to
**3.4.3** (the SDK's bytecode metadata can't be read by Kotlin 2.1). The server binds
**127.0.0.1 only**; remote agents reach it through a TLS-terminating front (Step 5) — TLS is
not re-implemented in the engine.

### 2. Per-agent identity bound at the session, flowing to git
Auth is a Ktor `bearer` scheme: a token resolves to an `AgentIdentity{agentId, role, author}`
via `AgentRegistry` (constant-time token compare). On each new MCP session we build a
dedicated SDK `Server` whose tool handlers **close over that agent**, so a tool call can't be
spoofed into another identity. The agent's `author` becomes the git commit author — *the
write tool's commit is authored by the agent that made it*, proven end-to-end over HTTP.

### 3. Roles: read-only vs write, enforced before the engine is touched
`AgentRole.READ_ONLY` may call only read tools; mutations require `WRITE`. A denied call
never reaches the engine and is recorded in the audit log. Enforcement lives in the
transport-agnostic `SvodTools`, so it holds regardless of how a tool is invoked.

### 4. Per-agent token-bucket rate limiting / quotas
Every call (reads included) draws from a per-agent token bucket (`RateLimiter`); exhaustion
returns a `rate_limited` error result. The clock is injectable for deterministic tests.

### 5. Append-only audit log of every action
`.svod/audit/audit.log` is JSON-lines, one record per call (ts, agentId, tool, outcome,
path, target, revision, detail), appended under a lock and flushed. Mutations are always
audited — including denials and conflicts — making the trail the tamper-evident spine of
"auditable agent memory".

### 6. Outcomes as values, not protocol errors
`SvodTools` returns a structured `ToolResult` with a `status` field. **Domain outcomes**
(`ok`, `conflict`, `not_found`) are normal results the agent branches on — a `conflict`
carries the current content for a 3-way merge, never a silent overwrite. **Permission/abuse
failures** (`denied`, `rate_limited`, `bad_request`) set MCP `isError=true`. This keeps
optimistic concurrency legible to agents.

### 7. `messy/` namespace + controlled promotion
`messy/` is a draft namespace (ordinary writes, no special storage). The `promote` tool is a
transactional move that enforces the policy — source under `messy/`, target outside it — and
otherwise reuses the engine's optimistic `move` (so promotion is revision-checked and
attributed). Anything else is a `bad_request`.

### 8. Tool surface (12)
`read, write, delete, move, search, list, history, diff, get_revision, link, graph_query,
promote`. `diff`/`get_revision` read committed state via new `GitRepo` methods routed
through the write-actor; `search` delegates to the Step-2 index; `link`/`graph_query` serve a
**lightweight** `[[wikilink]]` graph (outlinks/backlinks/unresolved) computed from current
notes and cached by HEAD.

## Consequences

- Multi-agent writes are safe, attributed, and replayable from git + the audit log.
- The MCP layer holds zero engine internals; `SvodTools` is unit-tested directly and the wire
  is covered by a real client↔server HTTP test.

## Boundary with Step 4

`link`/`graph_query` here are **read-only resolution**. The full graph subsystem — tag
taxonomy, graph-view payloads, and **link-integrity on rename/move** (transactionally
rewriting references) — is Step 4. The MCP tool contracts won't change when it lands; only
their backing graph deepens.

## Alternatives considered

- **Custom JSON-RPC over Ktor** instead of the SDK — more control, but the spec mandates the
  official SDK and it gave us streamable HTTP + session handling for free. Rejected.
- **One shared MCP server, identity via coroutine-local** — fragile across the SDK's session
  scheduling; per-session agent-bound servers are unambiguous. Rejected.
- **TLS inside the engine** — keeps the engine OS-agnostic to push transport security to the
  lifecycle/front layer (Step 5). Deferred, not rejected.
- **Conflict as a protocol error** — would force agents to parse error prose; a structured
  `conflict` result with current content is far more usable. Rejected.
