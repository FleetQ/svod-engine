# ADR-0020 — Team sharing: people behind agents, and roles on vault grants

- Status: **Accepted**
- Date: 2026-11-15
- Scope: `docs/plan-team-sharing.md` (copy of the Svod vault plan, rev. Nov 2026). The plan calls this
  ADR "ADR-0018"; in this repo 0017–0019 were already taken (see Consequences §1).
- Depends on: ADR-0009 (multi-host sync, merge authority), ADR-0010 (TLS, `Secrets`), ADR-0011
  (multi-vault, NFR-S1), ADR-0014 (per-request vault selection + grant enforcement), ADR-0018 (typed
  memory: fact classification), ADR-0019 (App API principals).

## Context

A small team (2–10 people) wants to share one or more vaults through one central engine: each person
runs their own agents with their own tokens, every write is attributed to the human, roles decide who
may promote drafts into the curated vault, and the central instance is merge authority and the team's
remote MCP endpoint.

What already exists:

- **People** are App API principals (ADR-0019): `users[]` with `userId`, `name`, `email`, a personal
  key ref, `admin`, and READER/EDITOR grants per vault. Their App API writes are authored by them.
- **Agents** are a separate registry (`agents[]`, ADR-0005/0011/0014): a token maps to an
  `AgentIdentity` whose `author` is the agent itself (`<agentId>@agents.svod.local` by default), a
  global role WRITE | READ_ONLY, and a list of granted vaults that `SvodMcpServer.routed()` enforces
  per call (ADR-0014).

The gap: an agent's commit names the agent, not the person who runs it, and the only role an agent has
is global — "may write" is all-or-nothing across its vaults, and every WRITE agent may `promote`.

## Decisions

### 1. Agents bind to existing people: `agents[].user`

`agents[]` gains an optional `user` that names a `users[].userId`. There is **one** person model — the
ADR-0019 `users[]` — not a second list. A bound agent's commits are authored by the person
(`name <email>`; email defaults to `<userId>@users.svod.local` exactly as for App API writes) and carry
a `Svod-Agent: <agentId>` git trailer, so history says *who* and *through which agent*. The committer
stays the host id (ADR-0019 Consequences), so sync provenance is unchanged.

An unbound agent behaves exactly as before: same author, no trailer.

Validation: a binding to an unknown user id is a config error (fatal at startup, also on loopback —
it is not a stale grant, it is a broken identity). Duplicate user ids were already an error.

Token → person resolution happens once, when the registry is built (`SvodConfig.toAgentSpecs`), not per
request: `AgentIdentity` carries `userId` and the person's author. A change to a person (name, email)
or a removed person therefore has to reload the agent registry; deleting a person that an agent is
bound to is refused.

### 2. Roles per grant: `reader` < `writer` < `maintainer`

Each `agents[].vaults` entry is a bare vault id or `{vault, role}`. In memory the object form is split
into `vaults` (ids, unchanged type) and `vaultRoles` (id → role); `AgentSettingsSerializer` folds it
back on write, so a config that never used roles is written byte-for-byte as before.

| Role | May call (MCP) |
|---|---|
| `reader` | read, search, context_pack, list, tree, grep, history, diff, get_revision, link, graph_query, graph_communities, graph_community, graph_status |
| `writer` | + write, edit, move, delete, remember |
| `maintainer` | + promote |

Conflict resolution and vault settings (backup/sync) are App API operations, not MCP tools; on the App
API they remain governed by ADR-0019 (EDITOR / admin). `maintainer` therefore adds exactly one MCP tool
today; the role exists so that the next maintainer-only MCP tool has a place to go.

**Who the roles apply to (back-compat).** Today every WRITE agent may `promote`. Making every bare grant
a `writer` that may not promote would silently take that away from every existing agent — a break of
the "unbound agents behave exactly as today" invariant. So role enforcement applies to an agent that
**opts in**: it is bound to a user, or at least one of its grants is in the `{vault, role}` form. For
such an agent a bare id means `writer`. Every other agent is resolved exactly as before (grant check
only; the global role gates mutations inside `SvodTools`).

**Ceiling.** The global role stays and caps every grant: `READ_ONLY` ⇒ at most `reader`, whatever the
grant says. `WRITE` ⇒ no cap. Effective role = min(grant role, ceiling).

**One enforcement point.** The check extends the ADR-0014 resolver (`routed()` in `SvodMcpServer`):
the call's target vault is resolved, the grant is checked as before, then — for an opted-in agent —
the tool's required role is compared with the effective role. A denial uses the existing `denied`
result shape. The tool → required-role table lives in one place next to the resolver; no tool handler
checks a role itself. `SvodTools.guarded()` (the global WRITE gate) is untouched and remains a second,
older line that can only deny more, never less.

### 3. Sharing granularity stays the vault (NFR-S1 reaffirmed)

No sub-vault ACL, no path rules, no per-note permissions. A role is a property of a (agent, vault) pair
and nothing smaller. Personal vs. team content means separate vaults (ADR-0011).

### 4. Attribution in read surfaces, derived from git

Read surfaces (MCP `read`, App API file read) expose `author` (who created the note) and
`lastModifiedBy` (who made the latest commit touching it), each `{name, email}` plus `agent` when the
commit carries a `Svod-Agent` trailer; history entries expose `agent` the same way. Everything is read
from git history. Frontmatter is not consulted and the index stays content-only. The contract change is
additive (§Consequences 3).

### 5. Topology: one central instance, no new sync machinery

The central engine holds the team vaults and is the team's MCP endpoint over TLS, with one token per
agent per person. Because every person's agents write through that one engine, every team write is
already serialized by its single writer: there is nothing to merge on the server.

The plan calls the central instance "merge authority" (ADR-0009). The shipped sync no longer has an
authority: `SyncEngine` is symmetric — every engine fetches, reconciles and pushes one canonical ref
`refs/svod/sync/<vaultId>` on a shared remote, and `mergeAuthority` only changes the role label
reported by the API (verified in code and by the S5 runbook run, 2026-11-15). For the team topology
this changes nothing: the central engine plus an optional off-site remote (per-vault backup/sync,
ADR-0014) is the whole picture, and an optional replica is another engine syncing the same ref.
Conflicts on that path are surfaced exactly as before. Nothing in sync changes.

## Alternatives rejected

- **OAuth/OIDC now.** A static per-person token behind TLS is enough for 2–10 people and keeps the
  identity model a lookup table. ADR-0019 already notes an OIDC resolver would be a second
  `authenticate` source, not a new model. Deferred to Svod Cloud phase 2 (the claude.ai connector).
- **Per-note / per-folder ACL.** Contradicts NFR-S1, cannot be made cheap on git (every reader of the
  repo sees every path), and turns every read into a policy evaluation. A separate vault does it.
- **A separate user service.** A process that owns people and hands the engine identities would add a
  network hop and a second source of truth for the same `users[]` the engine already validates and
  reloads at runtime.
- **A second person list for agents (`agents[].person {name, email}` inline).** Would let the same human
  appear under two spellings in history and could not be revoked in one place.
- **Making every bare grant a non-promoting `writer`.** Literal reading of the plan; breaks every
  existing WRITE agent that promotes (Decision 2, back-compat).

## Consequences

1. **ADR number.** The plan and the overnight brief name this ADR-0018 and say "0017 is taken by typed
   memory". In this repo 0017 is non-blocking indexing, 0018 is typed memory (fact classification) and
   0019 is App API principals, so the next free number, 0020, is used. References in the plan to
   "ADR-0017 (typed memory)" mean ADR-0018 here.
2. **`users[]` is reused, `keyRef` stays required.** The plan sketches `users: [{id, name, email}]` as a
   new list. It already exists (ADR-0019) with `userId` and a required personal key ref. Keeping the key
   ref required means the `User` contract does not change; a person who only uses agents simply has an
   App API key they may never use. Smallest option that keeps the invariants.
3. **Contract 0.38.0 → 0.39.0, engine 1.30.0 → 1.31.0, additive.** `Agent`, `CreateAgentRequest` and
   `UpdateAgentRequest` gain `user` and `vaultRoles`; the attribution fields of Decision 4 join the same
   minor. `ApiCompatibility` major is unchanged. The one existing test assertion that pins the contract
   version (`GraphRagUnitTest` G1) was edited from 0.38.0 to 0.39.0; it exists to force a deliberate bump
   and is edited at every bump.
4. **Roles are opt-in per agent** (Decision 2). An existing config gets the role system only by adding a
   `user` or a `{vault, role}` entry. The gate "bare-id grant ⇒ writer" holds for every opted-in agent.
5. **Maintainer adds only `promote` on MCP** (Decision 2). Conflict resolution and settings stay App API
   (ADR-0019 EDITOR/admin); people's App API roles are not changed by this ADR.
6. **Update API.** `UpdateAgentRequest.user = ""` unbinds; `vaultRoles` replaces the whole map; dropping
   a vault from `vaults` drops its role.
7. **"Merge authority" is a label today** (Decision 5): ADR-0009's authority/proposal-branch model was
   replaced by symmetric sync on `refs/svod/sync/<vaultId>`. ADR-0009 itself is left unedited; this
   note is the correction for the team topology.
8. Decisions taken during implementation are appended below by stage.

### Implementation notes (appended per stage)

- S0 (schema): `AgentSettingsSerializer` (`JsonTransformingSerializer` over the kept generated
  serializer) is the only code that knows the `{vault, role}` JSON form. An object entry without a
  `role` means `writer`; without a `vault` it is a parse error.
- S1 (identity): `Author` gained `agent: String? = null`; `GitRepo` appends `Svod-Agent: <id>` at all
  three commit sites (commitAll, commitPaths, commitMerge) only when `agent != null`, so an unbound
  agent's message is unchanged byte for byte. The trailer therefore also lands on a merge commit
  whose author is a bound agent (in practice merges are authored by the engine, not by agents).
  `UserController` reloads the agent registry after a person changes, and refuses to delete a person
  that any agent is bound to (this check runs before the last-admin check, so a bound last admin gets
  the "bound" error). A user id can be reused only after every binding is removed.
- S2 (roles): the table and `optedIn()` live in `mcp/GrantResolver.kt`; `routed(req, tool)` is the
  only caller, for both the session and the stateless MCP transport (both build tools through
  `buildTools`). A tool missing from the table requires `maintainer` (fail closed); a test asserts the
  table keys equal the registered tool names. A denial names the effective role after the ceiling
  (a READ_ONLY agent with a maintainer grant is told "role reader").
- S3 (attribution): `author` = latest commit that ADDED the path (`git log --diff-filter=A -n 1`), so a
  note deleted and re-added reports the re-add; `lastModifiedBy` = newest commit touching the path;
  renames are not followed (same as `history`). Both are read through the write-actor like `history`,
  so an MCP `read` now holds the queue for two `git log -n 1` calls (whole read measured 3.5–4.3 ms on
  a 500-commit vault). Content and attribution are two actor turns, so a write landing between them can
  make `lastModifiedBy` newer than the returned content — harmless, never older. History `message`
  still contains the trailer line; the parsed value is in `agent`.
- Review (adversarial verifier, 2026-11-15), fixed:
  - **Forged attribution.** A path is part of the commit message (`write: <path>`) and paths may
    contain a newline, so any writer could end a message with `Svod-Agent: <someone>` and be shown as
    that agent. `GitRepo` now drops caller-supplied lines that start with `Svod-Agent:` before adding
    the engine's own trailer; a message without such a line is unchanged byte for byte.
  - **Stale sessions.** A 2025-11-25 MCP session bound its tools to the identity it opened with, so a
    role downgrade, unbinding, person rename or agent deletion took effect only when the session went
    idle. A session whose identity no longer equals the registry's is now closed on its next request
    (404, the client re-initializes). This also closes the same pre-existing gap for vault grants.
  - Deleting a vault now drops its role from `vaultRoles` together with the grant.
- Review, accepted and documented (no code change):
  - **`maintainer` on `promote` is a label, not a security boundary.** A writer can `move` a note out of
    `messy/` or `write` straight into the curated tree; stopping that would need a path rule, which
    NFR-S1 forbids. The role records intent and keeps `promote` (with its memory-specific wording)
    away from writers; the real boundary is the vault (reader vs writer).
  - Agent and user admin calls check, then update the config without a common lock; two admins acting
    at the same instant could leave an agent bound to a just-deleted person. The next start refuses
    that config with a clear error. Admin-only, rare; left as is.
