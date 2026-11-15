# Team sharing — overnight progress (CTO log)

Plan: `docs/plan-team-sharing.md` · ADR: `docs/adr/0020-team-sharing-users-and-roles.md` (plan's "ADR-0018"; renumbered, see ADR §Consequences 1).
Vault read OK (svod MCP reachable) at 2026-11-15 19:30 UTC. Host: friday. JDK 20 in untracked `.tools/`.

| Stage | Status | Updated (UTC) | Note |
|---|---|---|---|
| S0 schema + ADR | **green** | 2026-11-15 20:25 | full suite 574 passed / 0 failed / 25 skipped (perf, flag-gated). Legacy samples round-trip unchanged. |
| S1 identity | pending | | Dev A, worktree `s1-identity` |
| S2 roles | pending | | Dev B, worktree `s2-roles` |
| S3 attribution | pending | | after S1+S2 merge |
| S4 multi-user test | pending | | QA, worktree `s4-qa` |
| S5 runbook | pending | | tech writer |
| S6 deploy svod.fleetq.net | pending | | DevOps, after S1–S5 gates |

Notes
- Friday quirk: `/etc/hosts` maps 127.0.0.1 → `localhost.localdomain`, so `McpTlsTest` and `AppApiTlsTest` fail on this host on clean `main` too (cert names `localhost`). Local runs use `JAVA_TOOL_OPTIONS=-Djdk.net.hosts.file=.tools/hosts`; CI (macOS) is unaffected.
- `svod.fleetq.net` does not resolve (checked 19:50); SSH to fleetq-01 works. Per plan: deploy, verify locally, flag DNS.
- Pushes go to remote branch `feat/team-sharing` (draft PR for CI), not to remote `main`.
