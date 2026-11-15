# Team sharing — overnight progress (CTO log)

Plan: `docs/plan-team-sharing.md` · ADR: `docs/adr/0020-team-sharing-users-and-roles.md` (plan's "ADR-0018"; renumbered, see ADR §Consequences 1).
Vault read OK (svod MCP reachable) at 2026-11-15 19:30 UTC. Host: friday. JDK 20 in untracked `.tools/`.

| Stage | Status | Updated (UTC) | Note |
|---|---|---|---|
| S0 schema + ADR | **green** | 2026-11-15 20:25 | full suite 574 passed / 0 failed / 25 skipped (perf, flag-gated). Legacy samples round-trip unchanged. |
| S1 identity | **green** | 2026-11-15 21:10 | merged 007ab43; CTO full-suite run on merge: 623 tests, 599 passed, 24 skipped, 0 failed |
| S2 roles | **green** | 2026-11-15 21:10 | merged 918d3e2; same CTO run; role table in `mcp/GrantResolver.kt`, enforced only in `routed()` |
| S3 attribution | pending | | after S1+S2 merge |
| S4 multi-user test | pending | | QA, worktree `s4-qa` |
| S5 runbook | **green (writer run)** | 2026-11-15 21:12 | merged ef981f6; fresh clone → /ready 200, two people's keys + agent tokens OK, no-token 401. nginx part unverified until S6 |
| S6 deploy svod.fleetq.net | pending | | DevOps, after S1–S5 gates |

Notes
- Friday quirk: `/etc/hosts` maps 127.0.0.1 → `localhost.localdomain`, so `McpTlsTest` and `AppApiTlsTest` fail on this host on clean `main` too (cert names `localhost`). Local runs use `JAVA_TOOL_OPTIONS=-Djdk.net.hosts.file=.tools/hosts`; CI (macOS) is unaffected.
- `svod.fleetq.net` does not resolve (checked 19:50); SSH to fleetq-01 works. Per plan: deploy, verify locally, flag DNS.
- Pushes go to remote branch `feat/team-sharing` (draft PR for CI), not to remote `main`.
- S5 run left a log at `~/.svod/logs/svod.log` (76 KB, created 20:54 by the gate run before it moved `user.home`). Not deleted (deletion needs the owner's OK). The live Friday engine (`/opt/svod`, `/etc/svod/config.json`) was never touched.
- Finding from S5: "merge authority" is only a label in shipped sync (symmetric `refs/svod/sync/<vaultId>`). ADR-0020 §5 corrected.
