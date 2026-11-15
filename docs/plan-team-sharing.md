# Svod — Team Vault Sharing: Plan (rev. Nov 2026)

> Copied verbatim from the Svod vault (`projects/svod-cloud/plan-team-sharing.md`, revision
> `2398de54`, read 2026-11-15). The vault copy is the source of truth. Where the repo turned out to
> differ from the plan's assumptions, the decision is in **ADR-0020** (see "Repo reconciliation" at
> the end of this file); the plan text itself is unchanged.

- Status: Ready for implementation (autonomous Claude Code run, company-skill orchestration)
- Supersedes: Aug 2026 draft (never committed; its ADR number 0017 has since been taken by typed memory)
- ADR to write: **ADR-0018 — team sharing: users and roles**
- Depends on: ADR-0009 (multi-host sync), ADR-0010 (TLS, Secrets), ADR-0011 (multi-vault, NFR-S1), ADR-0014 (per-request vault selection + grant enforcement), ADR-0017 (typed memory — off-actor classification must keep working for user-bound agents)

## Goal

Let a small team (2–10 people) share one or more vaults: each person's agents connect with their own
token, every write is attributed to the human (not just the agent), roles gate who can promote, and
one central instance serves as merge authority + remote MCP endpoint. Local replicas stay optional
per ADR-0009. This is the prerequisite for the BYO-git managed-per-team offer ([[memory-cloud-idea]]).

## Verified baseline (do not rebuild)

- Multi-vault in one engine: `VaultManager`, per-vault engine/index/sync, `?vault=` routing,
  federated search, `[[vault:note]]` links (ADR-0011).
- Per-agent vault grants enforced per-request at the MCP layer; `denied` on ungranted vault (ADR-0014).
- **Per-agent role gating WRITE/READ_ONLY + per-agent rate limiter already exist** — S2 extends this,
  it does not introduce roles from scratch.
- Multi-host replication: merge authority, proposal branches `svod/<hostId>`, frontmatter-aware
  merge, `ConflictStore`, never-lose-data (ADR-0009).
- Agent tokens via `Secrets`, TLS on MCP, secret scanning, optimistic locking (`expectedRevision`),
  attributed commits, typed memory (`remember`, off-actor classification, ADR-0017).

## Decisions (seal as ADR-0018)

1. **User model.** Config gains `users: [{id, name, email}]`; each `agents[]` entry gains optional
   `user: <userId>`. Writes via a user-bound agent get git author = `name <email>` + commit trailer
   `Svod-Agent: <agentId>`. Unbound agent ⇒ today's behavior (full back-compat).
2. **Roles on grants.** Extend the existing WRITE/READ_ONLY axis into per-grant roles:
   `agents[].vaults` entries accept bare vault id (⇒ `writer`, back-compat) or `{vault, role}`:
   - `reader` — read/search/graph/history (maps to today's READ_ONLY);
   - `writer` — + write/move/delete/remember (maps to today's WRITE);
   - `maintainer` — + promote, conflict resolution, vault settings (backup/sync).
   Enforcement extends the ADR-0014 grant resolver — one checkpoint, existing `denied` shape.
   Global per-agent READ_ONLY keeps working as a ceiling (role ∧ ceiling).
3. **Sharing granularity stays the vault.** NFR-S1 stands: no sub-vault ACL, no path rules.
   Personal vs. team = separate vaults (exists).
4. **Attribution surfaces.** Read/history (MCP + App API) expose `author` + `lastModifiedBy`,
   derived from git, not frontmatter; index stays content-only. Contract bump additive.
5. **Topology.** Central instance = merge authority + team MCP endpoint (TLS, per-person tokens);
   optional local replicas via shared bare repo. No new sync machinery.

## Out of scope

Sub-vault ACL (NFR-S1); OAuth/OIDC (token-per-person suffices until Svod Cloud phase 2);
encryption at rest; web-viewer multi-user auth; FleetQ wiring.

## Stages

| Stage | Content | Owner (company skill) |
|---|---|---|
| S0 | ADR-0018 + this plan into repo `docs/`; full config/DTO schema (users, user binding, {vault, role}) + validation + schema tests — the one merge hotspot, lands before any parallel work | CTO (orchestrator) |
| S1 | Identity: token→user resolution, commit author = person, `Svod-Agent` trailer, back-compat; must not break off-actor typed-memory classification | Dev A ∥ S2 |
| S2 | Roles: extend WRITE/READ_ONLY resolver to reader/writer/maintainer per grant; role matrix; ceiling semantics | Dev B ∥ S1 |
| S3 | Attribution in read surfaces + additive contract bump + contract tests | Dev A or B (after merge) |
| S4 | Multi-user integration test: 3 users × concurrent agents, one team vault — drain to 0, zero loss, 409 flow, full role matrix over TLS MCP | QA ∥ S3 |
| S5 | `docs/runbook-central-instance.md`: authority host, TLS/reverse proxy, per-colleague token issuance, onboarding MCP snippet, optional replica (ADR-0009), per-vault backup (ADR-0014) | Tech writer ∥ S3/S4 |
| S6 | Deploy to fleetq-01 as `svod.fleetq.net` per the runbook: build artifact, dedicated service user + systemd unit, data dir, vhost on the host's EXISTING reverse proxy with TLS, initial config (one team vault; user nikola as maintainer; tokens via Secrets), live smoke tests | DevOps (after S1–S5 gates green) |

## Gates (executable)

- S0: legacy configs parse unchanged; full suite green.
- S1: user-bound agent ⇒ author is the person + trailer; unbound ⇒ pre-change authorship; typed-memory flow green.
- S2: reader denied write, writer denied promote, maintainer allowed all; bare-id grant ⇒ writer; global READ_ONLY ceiling wins over maintainer grant.
- S3: contract test green; previous-minor requests still succeed.
- S4: concurrency test green (no lost writes, conflicts surfaced not swallowed).
- S5: fresh checkout following the runbook reaches `/ready` 200 with two distinct user tokens.
- S6: `https://svod.fleetq.net/ready` → 200 over valid TLS; MCP endpoint answers with a valid user token and denies without one; service survives `systemctl restart`; no pre-existing service on fleetq-01 modified, restarted, or degraded.

## Deployment constraints (S6)

- fleetq-01 runs production FleetQ services: **additive changes only** — new service user, new unit,
  new vhost/server block on the already-running reverse proxy. Never modify, restart, or reconfigure
  any existing service, unit, or vhost.
- DNS for `svod.fleetq.net` is assumed to already point at fleetq-01. If it does not resolve,
  finish everything else and flag it — DNS is not changeable from the machine.
- No OAuth shim in this phase (out-of-scope stands): TLS + static per-person tokens via `Secrets`.
  The claude.ai connector attachment (OAuth) is Svod Cloud phase 2.
- Secrets are never committed, logged, or echoed; token values live only in the host's secret store.

## Risks

- Config schema churn — fully contained in S0.
- Role checkpoint must wrap the ADR-0014 resolver, not scatter per-tool checks.
- S1 touches the commit path that ADR-0017's apply-on-actor step uses — coordination point between Dev A and the typed-memory tests.

---

## Repo reconciliation (added in the repo copy, 2026-11-15)

The plan was written against the vault's picture of the engine. Three facts in the repo differ; each
is decided in ADR-0020 and none changes the plan's intent:

1. **ADR numbers.** In this repo ADR-0017 is non-blocking indexing, ADR-0018 is typed memory
   (fact classification) and ADR-0019 is App API principals. The plan's "ADR-0018" is therefore
   written as **ADR-0020**; "ADR-0017 (typed memory)" above means repo ADR-0018.
2. **`users[]` already exists** (ADR-0019, App API principals with personal keys). The plan's
   `users: [{id, name, email}]` is that list; agents bind to `users[].userId`.
3. **Promote is allowed to every WRITE agent today.** The role system therefore applies only to
   agents that opt into it (bound to a user, or with at least one `{vault, role}` grant); every
   other agent keeps today's behaviour exactly.
