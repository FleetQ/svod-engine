# Team sharing — overnight progress (CTO log)

Plan: `docs/plan-team-sharing.md` · ADR: `docs/adr/0020-team-sharing-users-and-roles.md` (plan's "ADR-0018"; renumbered, see ADR §Consequences 1).
Vault read OK (svod MCP reachable) at 2026-11-15 19:30 UTC. Host: friday. JDK 20 in untracked `.tools/`.

| Stage | Status | Updated (UTC) | Note |
|---|---|---|---|
| S0 schema + ADR | **green** | 2026-11-15 20:25 | full suite 574 passed / 0 failed / 25 skipped (perf, flag-gated). Legacy samples round-trip unchanged. |
| S1 identity | **green** | 2026-11-15 21:10 | merged 007ab43; CTO full-suite run on merge: 623 tests, 599 passed, 24 skipped, 0 failed |
| S2 roles | **green** | 2026-11-15 21:10 | merged 918d3e2; same CTO run; role table in `mcp/GrantResolver.kt`, enforced only in `routed()` |
| S3 attribution | **green** | 2026-11-15 21:35 | merged 004ebf8 (Dev A); CTO run on merge: 629 tests, 605 passed, 24 skipped, 0 failed. MCP read latency 3.5–4.3 ms on 500 commits |
| S4 multi-user test | **green** | 2026-11-15 21:50 | merged 9f1d23d (QA); CTO ran core test 3× without cache: 3/3 green, 121 writes = 121 commits, 50–56 conflicts surfaced, queue drained. Heavy (-Dsvod.perf=true, QA run): 2401 writes = 2401 commits, 214 conflicts, 132 s |
| S5 runbook | **green (writer run)** | 2026-11-15 21:12 | merged ef981f6; fresh clone → /ready 200, two people's keys + agent tokens OK, no-token 401. nginx part unverified until S6 |
| S6 deploy svod.fleetq.net | **green locally, DNS missing** | 2026-11-15 22:10 | deployed on fleetq-01 (container + svod.service + vhost). Through the real proxy: /ready 200 with TLS verified vs Cloudflare Origin CA, MCP 200 with token / 401 without, survives `systemctl restart`, no existing service changed. `svod.fleetq.net` has no DNS record → public check impossible until a proxied Cloudflare record exists |

Notes
- Friday quirk: `/etc/hosts` maps 127.0.0.1 → `localhost.localdomain`, so `McpTlsTest` and `AppApiTlsTest` fail on this host on clean `main` too (cert names `localhost`). Local runs use `JAVA_TOOL_OPTIONS=-Djdk.net.hosts.file=.tools/hosts`; CI (macOS) is unaffected.
- `svod.fleetq.net` does not resolve (checked 19:50); SSH to fleetq-01 works. Per plan: deploy, verify locally, flag DNS.
- Pushes go to remote branch `feat/team-sharing` (draft PR for CI), not to remote `main`.
- S5 run left a log at `~/.svod/logs/svod.log` (76 KB, created 20:54 by the gate run before it moved `user.home`). Not deleted (deletion needs the owner's OK). The live Friday engine (`/opt/svod`, `/etc/svod/config.json`) was never touched.
- Finding from S5: "merge authority" is only a label in shipped sync (symmetric `refs/svod/sync/<vaultId>`). ADR-0020 §5 corrected.
- Adversarial review (Opus verifier) of the whole diff: FAIL on first pass — forged `Svod-Agent` via newline in path (fixed), stale MCP sessions keep revoked role (fixed), writer can do promote's move (documented: NFR-S1 forbids a path rule), vault role kept after vault delete (fixed). Fix commit 8839a46; tests fail without it, pass with it. Re-check by the same verifier in progress.
- Flaky pre-existing test: `PrincipalAuthTest` audit-order assertion fails under load (seen on the first baseline and once on merged main, different wrong value each time); our diff does not touch ApiAuditLog/AppApiAuth. Test not edited.
- Review round 2 + automated security review: CR line-ending bypass of the trailer filter (fixed 8ab38e2), opted-in agent falling back to legacy on vault removal (fixed 8ab38e2), session id usable with another agent's token (pre-existing, fixed ae21fa1). Review stopped after 2 rounds (no high severity).
- **Final local suite on ae21fa1 (2026-11-15 22:05): 641 tests, 615 passed, 25 skipped, 1 failed = `PrincipalAuthTest` audit-order.** Same suite on untouched origin/main under the same parallel load: 583 tests, 1 failed = the same test. Pre-existing timing race (audit written in `finally` after the response is flushed); passes 3/3 in isolation. Not edited.
