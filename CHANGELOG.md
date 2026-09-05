# Changelog

All notable changes to the Svod engine. The App API contract (`contract/openapi.yaml`) is versioned
independently of the engine; each entry notes the contract version it ships.

## v1.21.0 — 2026-09-05 (App API contract 0.31.0)

### Security — the shared-vault review closed (`docs/security-shared-vault.md`)

- **Keyless loopback needs a loopback `Host`.** With `localAdmin: true` (every single-user Mac)
  a page whose DNS name is re-pointed at 127.0.0.1 was same-origin for the browser and reached
  the engine as the local admin. The keyless path now accepts only `Host: 127.0.0.1|localhost|[::1]`
  (any port); anything else is 401. Keyed requests are unaffected; the app and the MCP bridges
  already send a loopback `Host`.
- **Audit of people.** Every `/api` request by a keyed principal is one JSON line in
  `<configDir>/audit-api.log` (0600): ts, userId, method, canonical path, `vault`, status, ip.
  No bodies, no query strings, no keys. The loopback UI is not audited (nobody to tell apart).
- **Refusals are logged** (WARN, `AppApiAuth`): method, path, peer address and the reason —
  `key not accepted`, `no key`, `<user> is not an admin`, `… has no grant on vault …`. Never
  the key value.
- **`lastUsedAt` per person** in `GET /users` and `/me`: when the key last authenticated,
  persisted to `<configDir>/user-activity.json` at most once a minute per user, so a leaver's
  quiet key is visible in Members instead of relying on someone remembering to revoke it.
- **A member sees no server paths.** For a non-admin `GET /settings` returns `vaultPath`,
  `host` and `embedder.endpoint` empty; `GET /sources` shows a source's basename only.
  (`/sync/config` already redacted credentials.)
- **`/metrics` needs a key when `localAdmin` is off** (vault ids, document counts, queue depth).
  `/health` and `/ready` stay open: they carry no vault data.
- **A vault without a grant is a 404**, the same body as an unknown vault id — no enumeration.
  A reader's write stays 403.

Contract 0.30.0 → **0.31.0** (additive: `User.lastUsedAt`, `Me.lastUsedAt`; 404 semantics and
the `/metrics` rule documented on the error responses).

## v1.20.0 — 2026-09-05 (App API contract 0.30.0)

### Added — people as App API principals (ADR-0019): a shared engine for a company vault

The App API now knows **who** is calling. A person presents a personal key (`Authorization:
Bearer svk_…`), resolves to a principal with an `admin` flag and per-vault `reader` / `editor`
grants, and becomes the **git author** of every change they make — a shared vault's history names
Мария, not `svod-ui`. One interceptor (`AppApiAuth`) does all of it: 401 without a key, 403 on
engine-admin routes without `admin`, 403 on a vault without a grant, 403 on a write with only a
`reader` grant. `GET /vaults` is filtered to the caller's grants (and says the role), federated
search and the WebSocket event stream drop what the caller may not read.

- `GET /api/v1/me` — who am I (the app's connection test for a central engine).
- `GET/POST /api/v1/users`, `PUT/DELETE /api/v1/users/{id}`, `POST /api/v1/users/{id}/key` — admin
  creates a person, the engine generates the key (stored `0600` next to the config, referenced as
  `file:`), the raw key is returned **once**; rotate/revoke take effect on the next call, no restart.
- `POST /api/v1/secrets` — upload a secret once to the engine host, get back a `file:` ref (the
  company GitHub token never travels twice and never appears in a response).
- `Vault.role` on every `/vaults` row.

**Nothing changes for a single-user install.** `localAdmin: true` (default) keeps a loopback request
without a key as the local UI — admin, author `svod-ui`, exactly as before. The whole existing suite
runs in that mode.

**Leaving loopback** (`host: "0.0.0.0"`) is allowed only with `appApiTls`, `mcpTls`, at least one
admin user and `localAdmin: false`; config validation refuses anything less. The App API serves HTTPS
through Netty when `appApiTls` is set (CIO cannot). `localAdmin: false` is also what a shared engine
on loopback behind a TLS-terminating reverse proxy needs — on a shared host every other shell
account is a loopback caller too.

**Hardening found by review before release** (all covered by tests in `PrincipalAuthTest`,
`SharedEngineConfigTest`, `UserAdminTest`):
- The auth interceptor matches the path Ktor's router will match — empty segments dropped, each
  segment percent-decoded — and answers 400 when the request path is not already canonical.
  Previously `//api/v1/users` skipped authentication and `/api/v1/%75sers` skipped the admin table.
- A route reached without a principal fails closed (500) instead of falling back to the local admin.
- `isLoopback` reads only the socket's peer address as an IP literal: no reverse/forward DNS on the
  request path, nothing a PTR record can influence.
- A move rewrites `[[vault:note]]` links only in vaults the mover can WRITE; `/file/links` returns
  cross-vault backlinks only from vaults the caller can READ.
- Every vault-scoped event is tagged (`data.vault`): MCP conflicts, watcher `file.changed` and
  `commit.created`. The `/events` filter no longer forwards vault content to the wrong reader.
- With `localAdmin: false` the last admin cannot be deleted or demoted (400); `email: ""` clears the
  email instead of persisting an empty git author; a user whose key file went missing is skipped
  with a warning instead of blocking every admin operation.
- `users[].keyRef` must be `env:`/`file:`/`keychain:` — a pasted key would have been echoed by
  `GET /users`. `dist/secrets/` is git-ignored: the live config lives in this repo, and so would
  every key file next to it.

Contract 0.29.0 → **0.30.0** (additive: five paths, `Vault.role`, 401/403, a bearer security scheme).

## v1.19.1 — 2026-08-27 (App API contract 0.29.0)

### Fixed — a vault created at runtime was only half-wired until the next restart

`POST /api/v1/vaults` hot-adds a vault to the `VaultManager`, so `GET /vaults` lists it and
`?vault=<id>` routes to it immediately. But three registries that keep their OWN per-vault state
were each built **once** at startup from `vaults.contexts()` and never updated:

| registry | symptom for a vault created at runtime |
|---|---|
| `BackupService.byId` | `PUT /settings/backup` answered **200** and silently discarded the config; `POST /backup/now` then 409'd `no_backup_remote` |
| MCP `toolsByVault` | every tool call answered `{"status":"not_found","message":"vault: <id>"}` — for a vault the same engine was listing |
| `SourceWatchManager.byId` | `autoSync` sources registered fine but were never actually watched |

All three now subscribe to a `VaultManager.Listener`, so create/delete keeps them in step with no
restart. Found while connecting a GitHub backup remote to a vault created from the app: the PUT
reported success three times in a row and nothing was ever saved.

The 200-on-a-silent-no-op is the part worth naming — the success code was measured on the wrong
subject (the request parsed fine; the write went nowhere). `BackupService.configure` now returns
whether it applied, and the route answers **409 `vault_not_bound`** rather than a reassuring 200.
That response is newly declared in the contract, hence 0.28.0 → **0.29.0** (additive).

### Fixed — `SourceWatchManager.kt` was not a text file

Its composite watcher key used **raw NUL bytes** as the separator, committed literally into the
source. `file(1)` reported the source as `data` and **`grep` silently matched nothing in it** — a
whole file invisible to text search, which cost real time during this fix. Now `\u0000` escapes:
byte-identical at runtime, plain UTF-8 on disk.

## v1.18.1 — 2026-08-18 (App API contract 0.27.0)

Cold start: **~52 s → 13.4 s** on the operator's 3,096-note vault. Contract unchanged, no reindex,
no behaviour change beyond speed.

v1.18.0 added per-phase boot timings precisely so this could be aimed rather than guessed. The first
reading named two phases and disproved the standing hypothesis on the way:

| phase (`personal`) | before | after |
|---|---|---|
| engine open | 15,340 ms | **1,044–1,128 ms** |
| file watcher start | 34,491 ms | **1,748–2,568 ms** |
| index start | 1 ms | 2 ms |
| graph start | 2,380 ms | 2,188–3,051 ms |
| **`/ready`** | **~52 s** | **13.4 s** |

### Fixed — the file watcher hashed 936 MB it never looks at

`DirectoryWatcher` was registered with its default **content** hasher, which reads every byte under
the watched root at registration. On this vault that is `.git` (97 MB) plus `.svod` (839 MB of Lucene
index) — neither of which the watcher ever acts on, since the listener discards both by path.

The tell was `work`: a **two-note** vault paid 4,717 ms, so the cost could not be about notes. Sizing
it directly: 839 MB / 34.5 s ≈ 25 MB/s, and the three vaults' times track their `.svod` size almost
exactly.

Measured A/B on the real vault before choosing (warm cache, `watchAsync` registration):

| | `personal` | `lukanet` | `work` |
|---|---|---|---|
| content hashing (was) | 6,395 ms | 393 ms | 810 ms |
| **`FileHasher.LAST_MODIFIED_TIME`** | **152 ms** | **16 ms** | ~1 ms |
| `fileHashing(false)` | 104 ms | 12 ms | 6 ms |

Now hashes on (mtime, size). Deliberately **not** `fileHashing(false)`, which is marginally faster:
hashing is what suppresses a duplicate event for a file that was touched but not changed, and the
stat-based hasher keeps that for the price of a stat the watcher already does. Also deliberately
still watching the vault ROOT rather than a hand-picked list of subdirectories — excluding
`.git`/`.svod` by watching specific children was 9× faster too, but silently stops watching new
top-level files and folders.

The trade is that two writes are told apart by timestamp and size rather than by content, so there is
a test pinning the case that would break first: a same-length rewrite immediately after the previous
one.

### Fixed — every boot ran a full-tree git commit to handle a rare crash

`recover()` called `commitAll` unconditionally. Its jgit `add`/`status` run a `FileTreeIterator` that
stats every tracked file — a cost this codebase had already documented and routed around for the
write path, but not for boot. Native `git status --porcelain` answers the same question in **20 ms**
against 15.3 s, so it is asked first and the walk is skipped when the tree is provably clean.

**Recovery is not narrowed.** An edit made while the engine was down is an uncommitted working-tree
change, `status` reports it, and the commit still happens. Only a definitively clean tree is skipped,
and any failure to answer — git missing, non-zero exit, timeout — falls through to the full path. The
fail-safe direction is "do more", never "skip". Guarded by `CrashRecoveryTest` plus a new
`ColdStartTest` asserting that a file written while the engine was down is committed at the next open;
both fail if the skip is made unconditional.

### Not fixed

`graph start` (~2–3 s) is now the largest remaining phase and was not touched.

Suite 357 → **361**, 0 failures, 2 pre-existing skips.

## v1.18.0 — 2026-08-17 (App API contract 0.27.0)

Hardening sprint, from an internal survey run against the live engine
(`claudedocs/research_svod-improvements_2026-08-17.md`). Nothing here was a bug report — the engine
carries **zero `TODO`/`FIXME` markers and zero open issues**. These are a defect, a missing mechanism,
and the quality lever the design named a sprint ago.

### Fixed — an unknown `/api` path answered `200 text/html`

Measured on the live engine: `/api/v1/does-not-exist`, `/api/v1/graph/nope` and `/api/v9/settings`
all returned **200 with the web viewer's index page**, because the SPA fallback caught everything the
explicit routes did not. A client could not tell "no such endpoint" from "endpoint returned a page",
and a typed client raised a *decoding* error rather than a not-found one — which is exactly how this
project twice ended up debugging the wrong thing.

Unmatched paths under `/api/` now return `404` with the standard `ErrorDto` body, regardless of
whether the viewer is configured. Real routes and the viewer's own client-side routes are unaffected
(Ktor scores explicit segments above a tailcard). The house rule stands — feature-detect on
`apiVersion`, never on a 404 — but the engine is no longer the reason it had to.

**Behaviour change on undefined paths.** The contract never specified them, so this is not a contract
break; it is called out here because a client relying on the old 200 would now see a 404.

### Added — `GraphScheduler`: the periodic rebuild the drift trade depends on

v1.17.0's incremental attachment explicitly bets that "a periodic full rebuild restores the truth".
Nothing performed one: `rebuildOnStartup` is off by default, there was no timer, and the only triggers
were a button and an endpoint. The bet had no counterparty.

`graph.rebuildAfterAttached` (attached-note threshold) and `graph.rebuildIntervalMinutes` (elapsed
time) — either fires a rebuild; both null (the default) and the scheduler never starts. Mirrors
`SourceScheduler` exactly, down to the "log the failure and keep ticking" behaviour. The threshold is
the honest trigger, since a rebuild costs minutes of local LLM time and firing it on a vault that has
not moved is waste; the interval is the safety net for slow, steady drift.

### Added — `driftRatio`, so "stale" is a quantity

`attachedCount` counts notes, not divergence. `GraphStatus.driftRatio` is the fraction of attached
notes whose placement vote no longer names the community they sit in, measured against the **finest**
level (coarse communities absorb almost anything) over a bounded sample of up to 50, taken evenly
across attachment history.

Explicitly a **proxy**, with two stated blind spots: `0.0` means no sampled attachment has drifted,
never that the partition is still what a fresh Louvain would produce; and a note whose neighbourhood
has thinned below the threshold casts no vote at all, so a corpus drifting by *losing* cohesion also
reads as `0.0`. The sampled note's own vector is re-read from the index rather than taken from the
cache — an edited note keeps its cached vector, and a note whose content moved to another topic is
precisely the drift this exists to catch.

The measure covers the attachments made by the pass computing it. An earlier cut read the *pre-pass*
`attachedPaths` and was therefore structurally blind to everything it had just attached — caught in
review, and the reason there is now a test pinning the denominator (one drifted note plus one
freshly attached note must read exactly 0.5, not 1.0).

Cost note: measuring drift after a restart materialises the note-vector cache (a few seconds on ~3k
notes) even when there is nothing to attach. It runs on the MIN_PRIORITY attach thread, once.

### Added — hierarchical summarisation (`graph.hierarchicalSummaries`, off by default)

Counted on the real 3,096-note vault:

| level | communities | with ≥3 members | median (≥3) | largest | **summarised** |
|---|---|---|---|---|---|
| 0 | 809 | **258** | **7** | 103 | **0** |
| 1 | 566 | 58 | 25 | 315 | **0** |
| 2 | 546 | 38 | 44 | **320** | **38** |

`summariseTopLevels: 1` summarises only the coarsest level — median 44 notes, largest 320 — while the
prompt budget fits under ten. Every one of those 38 summaries was therefore written from a sample,
honestly disclosed and thin. One level down, 258 communities have a median of **7** members, which fit
entirely, and none of them was summarised. **The flat path summarises the level where a summary is
least trustworthy and leaves unsummarised the level where it would be most accurate.**

With the flag on: level 0 is summarised from raw excerpts as before, and each coarser community is
summarised from its **children's titles and summaries** — a compressed view of its whole membership
instead of eight raw notes out of 320. Children are matched by membership subset rather than by
assuming Louvain nesting; an unresolvable child set falls back to the raw path. Composed prompts read
no note text at all, and the sample-disclosure footer is emitted only when children were actually cut
off — a footer that always claimed a sample would be a fabrication in the other direction.

Off by default because it takes the build from 38 calls to ~354 (2–4 hours on `qwen2.5:7b-instruct`).
`summariseTopLevels` does not apply when it is on: a coarse level with no summarised children has
nothing to compose from.

### Added — boot phases are timed

"Cold start is 25 s – 7.5 min" was a number with no breakdown behind it, and an optimisation without a
breakdown is a guess. `VaultContext.open` now logs the elapsed time of engine open, index start, file
watcher start and graph start, per vault.

### Contract

0.26.0 → **0.27.0**, additive: one new `GraphStatus` field (`driftRatio`), also on the `graph_status`
MCP tool.

## v1.17.0 — 2026-08-17 (App API contract 0.26.0)

The thematic map stops describing only the vault as it was at the last build.

### The gap, measured

The index is incremental; the graph was not. Verified live on a 3,096-note vault against a note
written after the build: **search found it within seconds, and it was in no community at any level**.
Nothing auto-rebuilt it — `rebuildOnStartup` is off, there is no timer and no commit hook — and a full
rebuild is **~15 minutes**, almost entirely the Ollama summary calls. So the honest statement was that
the thematic layer described the vault as of the last build, and the pane's only signal was a bare
"stale" badge.

### Added — incremental attachment, with no model call anywhere

`graph.incremental` (**off by default**). On each index sync, notes that appeared since the last full
build are placed into the communities that already exist:

- the note's vector is **already in Lucene** (`IndexService.noteVector` mean-pools stored chunk
  vectors) — no embedder call, and certainly no LLM call;
- its k nearest already-placed notes are found, and it joins whichever community **dominates among
  them, weighted by similarity** — at every level, since each level is a partition;
- a similarity floor applies, so a note with nothing close enough stays **pending** and is counted
  rather than filed into whichever community happened to be least far away;
- **Louvain is not re-run and no summary is regenerated.** The community records
  `addedSinceSummary`, so a reader can see that the summary describes slightly fewer notes than the
  size counts.

Runs on a MIN_PRIORITY daemon thread, writes only `communities.json` + `meta.json` (not the 12 MB
`graph.json`), and touches neither the Lucene index nor the vault. Every failure degrades to "not
attached", never to a broken build.

**`graph.attachThreshold` (default: reuse `simThreshold`).** The first cut reused the build's edge
threshold and was measured against the live vault: at the operator's tuned `simThreshold: 0.88` the
real new note had **no neighbour above the bar and never appeared** — attachment had inherited exactly
the 17% of notes a 0.88 build leaves uncovered. Bisected live, its nearest neighbour is in
**[0.70, 0.80)**, and at 0.70 it attaches to a coarse theme that does describe it. The two thresholds
answer different questions: `simThreshold` decides whether an edge is worth CLUSTERING on, where a
weak edge may not survive modularity optimisation anyway; attachment is classification against a
partition that already exists and changes no community. The live config now sets **0.75** — the value
the earlier sweep measured at 95% edge coverage.

**Accepted drift:** neighbour attachment does not recompute the partition, so after enough new notes
the structure diverges from what a full Louvain would produce. Incremental attachment keeps notes
reachable *between* builds; the periodic full rebuild restores the truth. A stated trade, not a defect
— which is exactly why the next item exists.

### Added — staleness you can act on

`GET /api/v1/graph/status` and the `graph_status` tool gain `incremental`, `attachedCount` and
`pendingCount`. `attachedCount + pendingCount` is how many notes arrived since the last full build,
split by whether they are on a theme yet — a client can now offer a rebuild against a number instead
of showing a badge. `incremental` must be read first: with the feature off the two counts are never
computed, and 0 would otherwise read as "nothing has changed".

The counts are computed on the attach thread and only read from `status()`; enumerating indexed paths
is a full stored-field sweep and this feature does not put one on a request path.

### Contract

0.25.0 → **0.26.0**, additive: three new `GraphStatus` fields and one new `GraphCommunity` field.

## v1.16.0 — 2026-08-17 (App API contract 0.25.0)

Makes the thematic graph usable by agents. The feature worked; the tool shape made it impractical to
call.

### Fixed — one listing cost more context than it returned

Measured on a 3,096-note vault: a default `graph_communities` call returned **≈44,300 tokens**, of
which **97% were member paths** and only ~1,200 were the titles and summaries a caller reasons over.
No amount of prompt guidance fixes a tool that expensive — an agent is right to avoid it.

- `graph_communities` now returns a **`sampleMembers` preview (5 paths) plus `moreMembers`**, never
  the full lists. `size` remains the true count, so a 588-note theme still reports 588.
- New **`graph_community(id)`** tool and `GET /api/v1/graph/community` route return the complete
  membership of one theme. `GraphService.community(id)` already existed but was unreachable — the
  targeted accessor was written and never exposed, which is why the bulk call had to carry everything.
- App API `GET /api/v1/graph/communities` gains `members=full|sample|none`, defaulting to **`full`**
  so app v0.2.16 in the field is unaffected. New callers should pass `sample`.

### Added — the tools now say when to use them

Guidance lives in the tool descriptions, so every agent gets it without per-project setup:
- **when to prefer this over `search`** — corpus-level questions ("what do I know about X", "what have
  I been working on") rather than finding one note;
- **what `level` means** — omit for a broad overview, pass `0` for the finest level, which measurably
  ranks a specific query better;
- **`NOT_BUILT` is not "no themes"** — the empty response now carries a `hint` saying so, because an
  agent that reads it as "this vault has no structure" reports a conclusion it has no basis for;
- **`stale` means usable but possibly missing the newest notes**;
- `levelCount` and the served `level` are returned, so a finer level can be requested deliberately.

`context_pack`'s `graphExpand` description now states when it earns its cost: when the *context around*
an answer matters, not when the matching text is enough.

### Contract

0.24.0 → **0.25.0**, additive: one new route, one new optional parameter, two new response fields.

## v1.15.1 — 2026-08-17 (App API contract 0.24.0)

Summary quality. Contract unchanged; the graph itself is unchanged. Only the wording of the summary
prompt and the parsing of the model's reply differ, so this needs no reindex and no graph schema
change — but it does need a `POST /api/v1/graph/rebuild` to take effect on an already-built sidecar.

Measured on the real vault with `qwen2.5:7b-instruct`: the first build produced **12 of 21 usable
summaries**. Two distinct causes, both fixed:

### Fixed — a bolded label was never parsed
The model answered `**TITLE: …**` for 2 of 21 communities. The matcher anchored on `^\s*TITLE:`, so it
missed them entirely: the label text leaked into the summary body and the title silently fell back to
a folder name. Labels now tolerate leading markdown/bullet/quote characters, and surrounding emphasis
is stripped from the captured value.

### Fixed — the model continued the documents instead of summarising them
6 of 21 summaries came back as a verbatim continuation of the pasted notes. The instruction was placed
**before** ~12,000 characters of source text, which a 7B model simply reads as more document. Now:
- the excerpts are fenced by explicit `=== НАЧАЛО/КРАЙ НА ИЗВАДКАТА ===` markers,
- the instruction is emitted **after** them, and
- the role instruction moves out of the prompt entirely into Ollama's `system` field, so it cannot be
  mistaken for input at all (`SummaryLlm.summarise` gained a `system` parameter).

### Fixed — the model answered in Chinese
Fixing the two above surfaced a third failure that had been hidden behind them: once the model was
actually generating rather than copying, `qwen2.5:7b` (a Chinese-origin model) answered in **Chinese
for 8 of 21** communities whose notes contain none. The instruction had asked it to "write in the
language predominant in the notes" — a judgement a 7B model does not make reliably.

The judgement now happens in code: `dominantLanguage()` counts Cyrillic against Latin in the excerpts
and emits one unambiguous instruction, carried identically by the prompt footer and the system clause.
`buildPrompt` returns the language alongside the prompt so the two cannot diverge — deriving it from
the assembled prompt instead would read this file's own Cyrillic instruction text as content and
classify every vault as Bulgarian.

### Measured end to end, on the real vault

| | usable summaries | build |
|---|---|---|
| v1.15.0 | 12 / 21 | 17 min |
| after the parsing + prompt-order fix | 13 / 21 (8 answered in Chinese) | 3.5 min |
| **v1.15.1** | **21 / 21** | **7.2 min** |

"Usable" means structurally parsed *and* in a language the operator reads — the first metric only
checked structure, which is why the middle row initially looked like 21/21.

Each fix is guarded by a test, including one that asserts the instruction's *position* relative to the
excerpts rather than merely its presence.

Suite: 313 tests, 311 passed, 2 skipped.

## v1.15.0 — 2026-08-17 (App API contract 0.24.0)

Graph-aware recall, in two layers. Deliberately **not** full GraphRAG: there is no LLM entity
extraction over chunks, and none is stubbed for.

### Added — Ниво 1: recall expansion
- `context_pack(graphExpand=true)` appends the 1-hop wikilink neighbourhood of the top ranked hits
  into whatever token budget is left over. Expanded blocks carry `viaGraph: true` and `viaPath`, so an
  agent can separate primary evidence from the context pulled in around it. Default off; a link-graph
  failure leaves the ranked blocks untouched.

### Added — Ниво 2: thematic communities
- A note-level graph built from resolved wikilinks **plus kNN similarity** over vectors already stored
  in Lucene, persisted to a sidecar at `.svod/graph/`, clustered by Louvain into a hierarchy.
  `IndexService.noteVector` mean-pools existing chunk vectors, so this costs **zero embedder calls and
  zero LLM calls**.
  Measured on a 3,096-note vault: wikilinks alone give 732 edges over 19% of notes; with similarity,
  **14,315 edges over 100%**.
- Optional per-community summaries via a pluggable `SummaryLlm` (`none` by default, Ollama
  implementation provided) — generated at **build time only**. Query time consults no model, so the
  engine remains fully functional with no LLM at all.
- New MCP tools `graph_communities` and `graph_status`; new App API routes
  `GET /api/v1/graph/communities`, `GET /api/v1/graph/status`, `POST /api/v1/graph/rebuild`. The
  existing `GET /api/v1/graph` is unchanged.
- New config block `graph` — **`enabled` is false and `summaryProvider` is "none" by default**, so a
  fresh install builds nothing and calls nothing.

### Safety
`search()` is untouched (asserted identical with the graph off vs. built), the Lucene index is never
written and needs no reindex, the vault is never written, and every failure path degrades: build
failure, corrupt sidecar, missing vectors, unreachable or throwing summariser.

### Known limitation
The coarsest hierarchy level can hold hundreds of notes while the summary prompt fits fewer than ten.
The prompt states explicitly that it is seeing N of M, so a summary cannot be silently fabricated from
a sample — but summarising a community that large properly needs hierarchical summarisation, which
this release does not implement.

Suite: 308 tests, 306 passed, 2 skipped (37 new).

## v1.14.1 — 2026-08-14 (App API contract 0.23.0)

Test-only. **No production code changed**, so this release is behaviourally identical to
v1.14.0 — it exists so the guard below is traceable to a version.

### Fixed — five agent-auth tests had never run
- `AgentAdminTest` declared 10 tests and ran **5**. A Kotlin `@Test` whose body is an expression
  returning something other than `Unit` compiles to a non-void method, and JUnit Jupiter does not
  collect it: no failure, no skip, no warning — it simply never runs while the suite reports green.
- All five silent ones ended in `assertFailsWith`, which returns the Throwable, so
  `= runBlocking { … }` returned `Throwable`. The collected five happened to end in
  `assertEquals`/`assertTrue`; that was the only thing separating them.
- All five were the **negative agent-auth cases** — duplicate agent id → `Conflict`, invalid id
  pattern → `InvalidRequest`, raw token instead of a Secrets ref → `NotARef`, double delete →
  `UnknownAgent`, update unknown agent → `UnknownAgent`. Exactly the assertions least safe to have
  quietly absent.
- Fixed with `(): Unit = runBlocking { … }`. All ten now run and **all ten pass** — the auth logic
  was correct, it had simply never been exercised.

### Added — a guard so it cannot recur silently
- `TestMethodCollectionTest` reflects over every compiled test class and fails on any `@Test`
  method with a non-void return type, naming the method and the type it returns. A class it cannot
  introspect is also a failure, since an unreadable class is a hole in the guard rather than a pass.
- Mutation-tested rather than merely observed green: reverting one call site drops `AgentAdminTest`
  from 10 collected to 9 and the guard fails by name.
- Repo-wide sweep: declared `@Test` count now equals collected count for every test class.
  `AgentAdminTest` was the only instance. Suite is **271 tests** (was 265: +5 restored, +1 guard).

## v1.14.0 — 2026-08-14 (App API contract 0.23.0)

Search-latency work. The contract is unchanged — this is all internal to the index and the
embedder clients, so no client change is needed to benefit.

The premise came from measuring rather than assuming: of a ~157 ms warm semantic search on a
79,178-chunk vault, **~112 ms was the Ollama query-embedding round-trip** and only ~45 ms the
HNSW lookup. The ANN index was never the bottleneck, so all three changes target the embed path.

### Added — query-embedding cache
- **`CachingEmbedder`**, a bounded LRU (256 entries) over `embedQuery`, applied in
  `Embedders.create()` for every active provider. A repeated semantic query drops **~157 ms →
  ~20 ms** while returning identical hits, measured end-to-end on the live vault. Ollama does not
  itself cache identical embed inputs, so this removes a genuinely repeated cost.
- Only **queries** are cached — `embedPassages` delegates straight through, since chunk texts are
  effectively unique and caching them would only grow the heap.
- Invalidation is structural, not explicit: a provider/model swap builds a fresh instance via
  `Embedders.create()`, so a vector from a previous model can never be served.
- The wrapper is `AutoCloseable` and closes its delegate — provider swaps dispose the previous
  embedder through `(previous as? AutoCloseable)`, so a non-closeable decorator would have
  stranded `OnnxLocalEmbedder`'s native ONNX session on every swap.

### Changed — the Ollama model stays resident
- `OllamaEmbedder` now sends **`keep_alive` (default `30m`)** on `/api/embed`. Ollama evicts a
  model 5 minutes after last use, so the first search of a session paid a full reload: 0.93–2.39 s,
  median ~1.5 s with the model file warm in the page cache (pooled n=9); one fully-cold observation
  reached 6.0 s. That is ~5x a normal semantic search at the low end. Residency costs ~0.6 GiB for
  bge-m3 (the larger `multilingual-e5-large` default would be ~2.1 GiB).
- Note that `keep_alive` and the cache fix **disjoint** paths and do not compound: a repeat query
  never touches Ollama at all, while a cache miss against an evicted model still pays the reload.

### Fixed — `truncate` was never actually sent
- `kotlinx.serialization` omits a property equal to its declared default, so `EmbedRequest`'s
  `truncate = true` had never reached the wire. Harmless in itself (Ollama also defaults it to
  `true`), but the same mechanism would have silently voided `keep_alive` and made this release a
  no-op. `EmbedRequest` now carries no defaults; both fields are regression-tested. Every other
  `@Serializable` request DTO in the repo was swept — no sibling has the same defect.

### Performance — cheaper embedding-backlog scan
- `LuceneIndex.pathsMissingVectors()` now finds the backlog by negating `FieldExistsQuery` over the
  kNN field instead of scanning every document and decompressing its stored fields. Stored fields
  are read only for chunks actually missing a vector — none once embedding has caught up, rather
  than all 79,178. This runs twice on the boot path.
- Equivalence with the old `vecBytes`-null scan was verified against Lucene 9.12.0 outside the
  repo, running both implementations on multi-segment indexes where a segment carries no `vec`
  FieldInfo at all, on indexes with deletes plus re-upsert without vectors, after `forceMerge(1)`,
  and on the `n == matches == numDocs` boundary. Identical counts throughout.
- The field name is now the constant `LuceneIndex.VEC_FIELD`, because this query detects work by
  **negation**: a rename missing one site would match nothing, the `MUST_NOT` would then match
  everything, and the engine would silently re-embed the whole vault on every boot with no error.

### Upgrade notes
- **No reindex.** `model` and `knownDim()` delegate unchanged, so `IndexMeta` identity is identical
  and no re-embed is triggered. Verified live: `docCount` unchanged, embedding idle after restart.
- Design and test plan: `docs/architecture-search-latency.md`, `docs/test-plan-search-latency.md`.
  The research that produced the premise: `claudedocs/research_hnsw-chromadb_2026-08-14.md`.

## v1.13.0 — 2026-08-13 (App API contract 0.23.0)

### Added — MCP spec 2026-07-28 served alongside 2025-11-25 on the same endpoint
- **The handshake is now optional (SEP-2575).** `initialize`/`initialized` were removed from the
  spec, so a 2026-07-28 client sends `protocolVersion`, `clientInfo` and `clientCapabilities` in
  `params._meta` (keys `io.modelcontextprotocol/protocolVersion`, `.../clientInfo`,
  `.../clientCapabilities`) on **every** request. `/mcp` reads them per request and answers the new
  `server/discover` method with the server's implementation info and capabilities.
- **Sessions are no longer required (SEP-2567).** A stateless request carries no `Mcp-Session-Id`
  and creates none; the bearer token alone resolves the agent, so identity — and therefore the git
  commit author, the role check, the vault grant and the rate limit — is unchanged. This also
  removes the failure mode logged in v1.11.x, where an engine restart invalidated in-memory session
  ids and clients got a 404 until they re-initialized.
- **Routing headers are validated, not trusted (SEP-2243).** `MCP-Protocol-Version`, `Mcp-Method`
  and `Mcp-Name` are optional, but a request whose headers disagree with its JSON body is rejected
  with `400` and JSON-RPC `-32600` rather than served from either half. The point of the headers is
  that a proxy can route without parsing the body, which only holds if they cannot lie.
- **`tools/list` carries cache hints (SEP-2549)**: `ttlMs` (24h) and `cacheScope: "server"`. Svod's
  15 tools are fixed at build time and identical for every agent, so a client can cache the
  catalogue and skip the call entirely.
- **Backward compatibility is per request, not per deployment.** The format is chosen from the
  request itself: a `Mcp-Session-Id` header or an `initialize` body takes the 2025-11-25 path
  through the SDK's streamable-HTTP transport; everything else is served statelessly. Both formats
  work against one running engine at the same instant — covered by a test that drives an SDK
  handshake client and a raw stateless client against a single server and has the second read what
  the first wrote.
- Internally the tool catalogue moved behind one `ToolDef` list that both paths render, so the two
  formats cannot drift into advertising or accepting different tools.

## v1.12.1 — 2026-08-01 (App API contract 0.23.0)

### Fixed — the contract version drifted between the gate and what clients are told
- **`AppApiServer.Config.apiVersion` was a second hardcoded copy of the contract version.** v1.12.0
  bumped `ApiCompatibility.CURRENT_CONTRACT_VERSION` to 0.23.0 (and `contract/openapi.yaml` with
  it), but `/api/v1/settings` kept advertising **0.22.0** — so the engine gated self-update on one
  version while telling the macOS app, which feature-detects on `apiVersion`, a different one. The
  same shape as the `currentAppVersion` drift fixed in v1.8.1 and v1.11.3: independent literals for
  one fact. `Config` now derives its value from `ApiCompatibility`, so there is one source.
- **`VersionConsistencyTest` extended to cover the contract**, comparing all three publication
  points — the self-update gate, `/settings`, and `contract/openapi.yaml` — so a one-sided contract
  bump fails the build instead of shipping.

## v1.12.0 — 2026-08-01 (App API contract 0.23.0)

### Added — fact classification on the `remember` promotion gate
- **`remember` now classifies an incoming memory against what is already stored** before persisting
  it, instead of only hashing for literal duplication. Candidates come from the existing hybrid
  path (BM25 + kNN + RRF) filtered to the same `type` with `includeAll=true` — `fact`/`policy`
  memories are `provisional` and hidden from ordinary recall, yet they are exactly what an incoming
  fact must be compared against — and are then scoped to the same `subject`. No new index.
- **Deterministic rules first, LLM only in the ambiguous middle band**: normalized-text equality →
  token overlap (Jaccard) → embedding cosine → optional adjudicator. The new `MemoryAdjudicator`
  interface has no implementation and is `null` by default, so the engine stays LLM-free and adds
  no dependency; absent, unreachable, or declining, the ambiguous band resolves to `UNCERTAIN`
  rather than guessing a confident class.
- **Behavior per class**: `NEW` writes as before; `DUPLICATE` is a no-op returning the existing
  note; `UPDATE` writes the successor with `supersedes:` and revokes + links the predecessor in the
  same commit (history preserved); `CONTRADICTION` persists **both** sides linked by `contradicts:`
  and never overwrites either; `UNCERTAIN` persists with `needs-review: true`.
- The MCP `remember` response gains `classification`, `relatedNote`, `confidence` (plus `rationale`
  and, where they apply, `contradicts` / `needsReview`). Every pre-existing field keeps its meaning
  and value — no breaking change for existing callers.

### Added — `SvodEngine.writeGuarded`, a guarded multi-file commit
- New write primitive: on the write-actor, re-validate a `path → expected revision` guard map
  against live blob ids, then write every file in **one** commit. A mismatch is
  `GuardedWrite.Stale` — nothing is written and the caller re-plans, never a silent clobber.
- This is what lets classification hold the single-writer invariant without stalling it. Planning
  is impure and slow (Lucene, a possibly-remote embed, possibly an LLM call), and ADR-0017
  established that writes never wait on embedding; so the plan is built **off** the actor and
  validated + applied **inside** it — the same shape as `writeBatch(expected=)` and
  `applyMerge(expectedHead=)`. Concurrent identical `remember` calls now provably collapse to one
  note: the losers hit the guard, re-plan, and dedup.

### Fixed — secret content could reach a remote embedder before being refused
- The secret scanner ran at write time, at the end of `remember`. With classification in front of
  the write, content would be sent to a **remote** embedder or an LLM and only then blocked from
  disk. `remember` now scans up front and refuses before any planning runs; the engine still
  re-scans on write. Regression-tested: the adjudicator never observes secret content.

### Contract
- App API contract **0.23.0** (additive): three new reserved frontmatter keys documented on
  `/search` — `contradicts`, `supersedes`, `needs-review`. All informational to that endpoint; they
  do not affect filtering or lifecycle visibility.

See `docs/adr/0018-memory-fact-classification.md` for the threshold choices and the explicit
non-goal (per-subject fact consistency only — not knowledge-graph entity resolution).

## v1.11.3 — 2026-07-25 (App API contract 0.22.0)

### Fixed — the self-reported engine version drifted again
- **`UpdateService.currentAppVersion` was left at `"1.11.1"` when v1.11.2 was cut**, so the running
  engine reported itself as 1.11.1 and advertised a phantom "1.11.2 available" update that could
  never clear — the same failure as v1.8.1, recurring because the constant in `SvodNode` and the
  Gradle `version` are two independent sources of truth with no consistency test. Both are now
  1.11.3.

### Fixed — release assets were all labelled 1.6.4
- **`release.yml` hardcoded `jpackage --app-version 1.6.4`**, so every app-image and installer
  published since v1.6.4 carried that version regardless of the release tag. It now derives from
  the pushed tag (`${GITHUB_REF_NAME#v}`, pre-release suffix stripped, since jpackage accepts only
  numeric `MAJOR[.MINOR[.PATCH]]`).
- **`dist/package.sh` carried the same stale `1.6.4`** and was broken by it: `MAIN_JAR` pointed at
  `svod-engine-1.6.4.jar`, which no longer exists, so the local packaging script failed its jar
  check before ever reaching jpackage. It now reads the Gradle `version` as the single source.

### Changed
- `CHANGELOG.md` backfilled for v1.7.0 → v1.11.2, which had been abandoned after v1.6.4.
- No engine behaviour or contract change; the App API stays at 0.22.0.

## v1.11.2 — 2026-07-25 (App API contract 0.22.0)

### Fixed — silent note corruption in the MCP `edit` tool
- **`edit` now refuses to persist a note when the post-edit length invariant fails or `newString`
  is missing** — what used to be silent corruption is a loud error. A malformed/partial edit is
  rejected before the write instead of being committed as truncated content. No contract change.

## v1.11.1 — 2026-07-15 (App API contract 0.22.0)

### Fixed — `GET /file/history` was ~12.7 s on a large vault
- **Native `git log` for path-scoped history (~12.7 s → <0.1 s** on the 77k-doc personal vault).
  Root cause: jgit's path-filtered `LogCommand` re-diffs every commit's tree along the path and
  cannot use commit-graph / changed-path bloom filters, so it walks the entire ancestry.
  `GitRepo.log()` now shells out to `git log -n <max> --format=… -- <path>` (the cap pushed down
  as `-n`), falling back to the jgit walk when the subprocess is unavailable. Renames are still
  not followed (unchanged behaviour).
- Default history cap raised 50 → 100, so an unset `max` never walks full history.
- No response-shape or contract change.

## v1.11.0 — 2026-07-15 (App API contract 0.22.0)

### Added — recall subsystem (session capture → distill → proposals)
- **Session capture.** `POST /api/v1/memory/capture` stores a raw transcript into
  `messy/sessions/`, idempotent on `sessionId` (written via `writeBytes`, bypassing the secret
  scan, so transcripts are kept verbatim).
- **Session inventory + distill marking.** `GET /api/v1/memory/sessions` (newest first,
  `?distilled` filter) and `POST /api/v1/memory/sessions/mark-distilled` (flips `distilled:true`
  and records `noteRefs`).
- **Proposals inbox.** `GET/POST /api/v1/memory/proposals` and `POST /…/proposals/{id}` — a
  suggestions queue with status transitions only; accepting a proposal never auto-creates a
  skill or tool.
- **Dashboard.** `GET /api/v1/memory/dashboard` — capture/distill counts plus byte-compression
  math.
- **Recall guard:** `messy/sessions/` is **unconditionally** excluded from recall in
  `LuceneIndex.buildFilter` — no `includeAll` / `includeMessy` / prefix-browse escape hatch, so
  raw transcripts stay out of recall the same way `<private>` does.

### Changed
- App API **contract 0.22.0** (additive): 7 new `/api/v1/memory/*` routes. Enum wire convention
  for `kind`/`scope`/`status` is **lowercase**.

### Out of scope
Distillation itself (LLM compression) is external — the engine only stores, serves, and marks.

## v1.10.0 — 2026-07-07 (App API contract 0.21.0)

### Added — retrieval cost + privacy controls
- **Token cost in search results** — `SearchHitDto.tokens`, so a client can show what a hit
  actually costs before packing it into a prompt.
- **`agentId` on MCP `commit.created` events** — writes are attributable to the agent that made
  them.
- **`<private>` index-exclusion** — content marked private is guarded out at prepare, plan-embed
  and `context_pack`, not just filtered at query time.
- **`messy/` recall quarantine** — drafts stay out of recall by default (`includeMessyInRecall`
  config to opt back in).

### Fixed — MCP sessions wedging after an engine restart
- **Own stdio↔HTTP MCP bridge replaces `mcp-remote` for Claude Desktop.** `mcp-remote` 0.1.38
  wedges permanently after an engine restart: SSE reconnect gives up after two attempts, then
  every POST hits a `404 Session not found` that it swallows without answering the client, so
  each tool call hangs until the 4-minute client timeout. The bridge caps every request (60 s
  default), returns structured JSON-RPC errors instead of hanging, re-initializes transparently
  on a 404 and retries once, and skips SSE entirely.
- **MCP session-lifecycle logging** — session create/close and unknown-session 404s are now
  logged; during the 2026-07-03 incident the engine-side view of the failure was invisible.

### Changed
- App API **contract 0.21.0** (additive).

## v1.9.0 — 2026-07-02 (App API contract 0.20.0)

### Added — partial edits, source conflict resolution, two-way write-back
- **MCP `edit` tool (partial edit).** Agents editing large notes had to resend the full content
  verbatim (transcription-error risk). `edit` replaces an exact substring: `oldString` must occur
  exactly once (`bad_request` when absent or ambiguous, `replaceAll` opt-in), with optimistic
  concurrency via `expectedRevision` or the revision read at edit time — a concurrent writer
  surfaces as the standard conflict shape, never a clobber. Tool count 14 → **15**.
- **Persisted source conflicts + resolve endpoint (contract 0.19.0).** A locally-edited synced
  file used to conflict forever with no way to see or settle it. `ExternalSource.conflicts` is
  persisted after each sync and exposed in `GET /sources`; `POST /api/v1/sources/{id}/resolve`
  takes `takeExternal` (external wins once) or `keepVault` (accept the local edit as the new
  baseline). The manifest was upgraded to a two-sided baseline (`SyncedState{ext,vault}`, legacy
  single-blob manifests load as `(v,v)`), which is what makes `keepVault` sound: the kept edit
  stays quiet while the external side is still, is never clobbered by the *old* external
  content, and a *new* external change re-surfaces as a conflict.
- **Opt-in two-way write-back (contract 0.20.0).** With `writeBack` on, a vault edit to an
  already-synced path flows *out* to the external file (atomic temp + rename) instead of
  conflicting, and is reported under `pushed`. Both-sides-changed stays a conflict — an external
  file that also changed is never clobbered. Vault-created files are not materialized
  externally; only tracked paths flow back. A debounced sync on `commit.created` lands the edit
  in the project file within ~a second, skipping the engine's own sync commits (no loops).
- **Browsable `main` mirror.** GitHub's web UI and desktop git clients list only heads + tags,
  never the `refs/svod/sync|backup` refs a vault rides on, so a fully backed-up vault looked
  empty in the browser. The canonical head is now best-effort force-pushed to `refs/heads/main`
  after sync and after a one-way backup push.

### Changed
- App API **contract 0.20.0** (additive): `writeBack` on `ExternalSource`, `pushed` on
  `SourceSyncResult`, `conflicts` + `POST /sources/{id}/resolve` (0.19.0).

## v1.8.1 — 2026-07-01 (App API contract 0.18.0)

### Fixed — phantom "update available" that never cleared
- The self-update check read `currentVersion` from a **hardcoded constant in `SvodNode`** that
  was left at `"1.7.0"` when v1.8.0 was cut, so the engine perpetually saw itself as 1.7.0 and
  advertised a 1.8.0 update that could never be satisfied. The constant and the Gradle version
  are now bumped together.

## v1.8.0 — 2026-06-29 (App API contract 0.18.0)

### Added — engine self-update
- **`GET /update/check` + `POST /update/apply`.** `UpdateService` checks GitHub
  `releases/latest` and gates on `ApiCompatibility` semver (`updateAvailable` = newer app
  version; `compatible` = same major). `apply()` spawns the detached `self-update.sh`
  (download → sha256 → atomic swap → `launchctl kickstart`) and is **opt-in**: 501 unless
  `SVOD_SELF_UPDATE_SCRIPT` is set, 409 when there is no compatible update. A failed GitHub
  fetch returns `200 updateAvailable=false` — never a 500.
- Pairs with the macOS Settings → Updates panel.

### Changed
- App API **contract 0.18.0** (additive): `/update/check`, `/update/apply`.

## v1.7.0 — 2026-06-29 (App API contract 0.17.0)

### Added — runtime vault CRUD + LLM-access (agent) management
- **Create/delete vaults at runtime** — `POST /api/v1/vaults` (contract 0.15.0) and
  `DELETE /api/v1/vaults/{id}` (contract 0.16.0), no restart required.
- **Manage MCP agents at runtime** — `GET/POST/PUT/DELETE /api/v1/agents` (contract 0.17.0).
  `AgentController` mutates the persistent config through `ConfigStore` and hot-reloads
  `AgentRegistry` atomically (build-then-swap), so granting or revoking an LLM's access takes
  effect on its next MCP call with no restart. Raw tokens are rejected — a secret must be given
  as a `Secrets` reference. `AgentsDto` carries `mcpPort` + `mcpUrl`.
- Pairs with the macOS Settings → LLM Access panel.

### Changed
- App API **contract 0.17.0** (additive, via 0.15.0 → 0.16.0 → 0.17.0).

## v1.6.4 — 2026-06-17 (App API contract 0.14.0)

### Fixed — Windows release no longer drops all its assets when MSVC setup breaks
- The release's Windows job died at `Set up MSVC` (`ilammy/msvc-dev-cmd`) after the GitHub runner
  shipped a new Visual Studio (VS 18 moved `vcvarsall.bat` to a path the action doesn't probe),
  which failed the whole job and dropped *both* Windows assets — including the app-image `.zip`
  that had already built. Marked the MSVC step `continue-on-error` (the native binary was always
  best-effort): the reliable Windows app-image now ships even when the native build can't link.
  Engine code unchanged from v1.6.3; this is a CI/release-workflow fix only.

## v1.6.3 — 2026-06-17 (App API contract 0.14.0)

### Fixed — O(vault) git operations made every write multi-second on large vaults
On a large vault (~10k+ tracked files) external-source auto-sync appeared not to run at all. The
watcher, debounce and worker were fine (confirmed by DEBUG logs) — the cost was **two O(working-tree)
git operations** on the single write-actor, each walking/statting *every* tracked file (~3–4 ms/file,
i.e. tens of seconds), so every change blocked the actor and the synced file only appeared much later.
Two fixes, both making the cost **O(changed paths)** instead of O(working tree):

- **Path-scoped commits.** Writes committed via `git add .` + a full `git status()`. New
  `GitRepo.commitPaths(paths, …)` edits the index (DirCache) directly — reads only the changed files,
  splices their blobs, writes the tree, moves HEAD — no working-tree walk. All path-aware write paths
  (single write, batch/source-sync, delete, move, move-with-backlinks, restore) use it; `.gitignore`
  handling and no-op detection are unchanged. Full-tree `commitAll` is kept only where semantically
  required (post-sync merge ingest, crash recovery, vault init).
- **Path-scoped external-change ingest.** The vault `FileWatcher` ran a full-tree `commitAll` on
  *every* change — including the engine's own writes — finding nothing new to commit (so no duplicate
  commit) but still burning the full-tree walk on the actor, blocking the next read/write. It now
  ingests only the paths its FS events actually touched (`ingestExternalChanges(paths)`), so an
  engine-written path is a cheap no-op and a genuine external edit still commits.
- **Measured on the live vault: steady-state auto-sync dropped from ~8–37 s to ~0.55 s (median);** a
  burst and a series of spaced edits converge in <1 s.
- Added DEBUG logging for external-source auto-sync (`fs event` / `auto-sync starting|done`) under the
  `dev.svod.engine.sources` logger, so event delivery and sync runs are visible in the log.

## v1.6.2 — 2026-06-17 (App API contract 0.14.0)

### Fixed — external-source auto-sync reliability under load
- **Auto-sync no longer stalls or cancels itself.** The per-source sync worker is now **decoupled from
  the watch lifecycle**. Previously the sync coroutine ran on the watcher's own scope, so when the
  native FSEvents watch died under load (its future completes) the 30s supervisor tore the whole
  watcher down and **cancelled the in-flight sync** (`WARN ... auto-sync ... failed: Job was
  cancelled`), and while dead it missed events — a series of edits could never converge.
- **Self-healing watch + trailing debounce (single-pending).** The DirectoryWatcher now re-arms
  itself (with backoff, plus a catch-up sync) without touching the worker; a running sync is **never
  cancelled** by a restart. The worker coalesces a burst into one sync `debounceMs` after the *last*
  event, and an edit arriving during a sync schedules exactly one follow-up — so every burst yields a
  completed sync, never a cancel-chain.
- **Expected cancellation is no longer logged as an error** — `CancellationException` is propagated
  (DEBUG), and the manager no longer tears a watcher down for a transient `!isAlive`. Real IO/scan
  failures still WARN. No API/contract change.

## v1.6.1 — 2026-06-17 (App API contract 0.14.0)

### Changed — external-source auto-sync latency
- **Sub-second auto-sync.** Lowered the per-source watcher debounce from 700 ms to 250 ms, cutting the
  measured edit→vault-update latency from ~757 ms to ~285 ms median (5 runs) — comfortably under 1 s
  even against FSEvents' 0.5 s worst-case coalescing window. The watcher already used the native
  FSEvents-backed `io.methvin:directory-watcher` (not the JDK `WatchService` polling fallback), so
  detection was never the bottleneck — the debounce was. Atomic-save (temp+rename) coalescing and the
  burst→single-sync behavior are unchanged (the existing coalescing test already ran at 250 ms).
  No API/contract change.

## v1.6.0 — 2026-06-17 (App API contract 0.14.0)

### Added — memory-system primitives (borrowed from "RAG → Memory Systems")
- **Memory typing** — a reserved frontmatter `type` (`policy|preference|fact|episode|note`, free-form)
  is indexed and filterable (`GET /search?type=`, MCP `search`/`context_pack` `type`), so the vault is
  no longer one undifferentiated corpus.
- **Lifecycle hiding** — frontmatter `status` (`active|provisional|revoked`), `superseded_by`, and
  `expires_at` are honored as retrieval filters: recall excludes revoked / provisional / superseded /
  past-expiry memories by default (`includeAll=true` to see them, or filter `status=` explicitly).
  Notes without these keys are **completely unaffected** (backward compatible).
- **Path A enumeration** — `context_pack` with `enumerate=true` returns *every* note matching a
  `type`/`tags` filter **in full, unranked, deterministic** (capped at 500), ignoring the token budget:
  the "rule book" (all active policies/preferences) loaded verbatim every turn, vs. ranked semantic
  recall (Path B).
- **`remember` MCP tool (promotion gate)** — turns an observation into a durable typed memory note:
  classify + scope (to the vault), dedup by normalized content hash (`memory/<type>/<hash>.md`), set
  `status` by type (fact/policy → `provisional`, preference/episode → `active`), and optionally
  `supersedes` a prior memory (revoke + link). Keeps an agent-written KB from poisoning its own recall.
  MCP tool count 13 → **14**.

### Changed
- App API **contract 0.14.0** (additive): `/search` gains `type`, `status`, `includeAll` query params
  and documents the reserved frontmatter keys + default lifecycle visibility.

### Not adopted / deferred
Episodic distillation (consolidation/summarization) deferred. Did **not** adopt the article's SQL
backend, multi-tenant row scoping (vault = scope), or its linear 0.4/0.6 fusion (Svod's RRF+reranker
stands). All new behavior is frontmatter conventions + Lucene filters — no datastore change.

## v1.5.0 — 2026-06-16 (App API contract 0.13.0)

### Added — per-source filesystem auto-sync
- **Auto-sync external sources on change.** A registered source can now opt into `autoSync`: a native
  FSEvents-backed filesystem watcher re-syncs it into the vault ~1s after its files settle, so an
  external project's docs flow in automatically without a manual sync. Events are debounced/coalesced
  (an editor's atomic temp-file-then-rename becomes a single sync) and noisy temp/dot files are
  ignored. Sync semantics are unchanged (it reuses `SourceSync`): external-wins-unless-locally-edited,
  conflict-preserve, prune soft-delete, secret-scanner skip, incremental reindex.
- **Runtime control, no restart**: `autoSync` on `POST /api/v1/sources` (idempotent by path) and a new
  **`PATCH /api/v1/sources/{id}`** `{ autoSync?, followSymlinks?, prune? }` that starts/stops the
  watcher immediately. Each source response carries `autoSync` and a live read-only `watching` flag.
- **`source.synced` event** (`{ vault, sourceId, created, updated, conflicts, deleted }`) emitted on
  each auto-sync, for the UI to reflect.
- The existing global scheduled `sourceSync` (config polling) stays as a coarse safety-net for hosts
  where native watching doesn't fire. A vanished source path stops its watcher (logged, no crash) and
  is re-watched when it reappears.

### Changed
- App API **contract 0.13.0** (additive): `autoSync`/`watching` on `ExternalSource`, `autoSync` on
  `RegisterSourceRequest`, new `PATCH /sources/{id}` + `PatchSourceRequest`, `source.synced` event.

### Out of scope (documented)
Watching remote/network paths; two-way (vault → source) flow — sources stay input-only.

## v1.4.0 — 2026-06-16 (App API contract 0.12.0)

### Added — two-way multi-machine sync
- **Multi-machine sync** over git as a bidirectional bus. A single user's machines edit the same
  vault and converge with no manual git. Symmetric topology (no authority/replica): one shared
  canonical ref `refs/svod/sync/<vaultId>` on the same remote used for backup. The sync cycle —
  commit pending → fetch → fast-forward / 3-way merge → non-force push (bounded retry on a
  non-fast-forward) — runs on a background poll (`syncIntervalMinutes`, default 3), on
  `POST /api/v1/sync/now`, and debounced after local writes settle.
- **Conflict handling**: a clean merge (structural YAML frontmatter + git line-level body, via
  `FrontmatterMerge`) commits automatically; a real overlap (or modify/delete) aborts the merge,
  leaves the local tree untouched, and records base/ours/theirs in the conflict queue. The user
  resolves via `POST /api/v1/conflicts/resolve`, which finalizes the merge commit. Nothing is lost.
- **Defense-in-depth**: an incoming file that trips the secret scanner is quarantined as a conflict,
  never written into the vault.
- **Persistent host identity** at `~/.config/svod/host-id`, recorded as the git committer (author
  stays the agent/UI) so history and the conflict UI can show "edited on machineA vs machineB".
- **`svod-engine clone <remote> <dest> <vaultId>`** CLI subcommand to bootstrap a new machine into an
  existing synced vault.
- **Scheduled auto-backup** (UI-controllable): `backupOnStartup` / `backupIntervalMinutes` /
  `backupOnChange`, with observable `lastBackupAt` / `lastBackupHead`.

### Changed
- Sync subsumes one-way backup: when a vault is synced, the one-way `refs/svod/backup/<vaultId>`
  push is retired (the canonical sync ref is the off-site disaster-recovery copy).
- `POST /api/v1/backup/now` reports "nothing to push / already up to date" as success
  (`ok:true`, `noChange:true`); `ok:false` is reserved for real failures.
- App API **contract 0.12.0** (additive, same major — compatible with existing clients): `syncEnabled`,
  `syncIntervalMinutes`, `syncStatus` (`inSync|syncing|conflicts|offline|error`), `lastSyncedAt`, and
  role `synced` on the sync config / status surface; `POST /sync/now` runs the real cycle.

### Out of scope (documented)
Real-time collaboration, multi-user permissions, a central server, P2P/LAN discovery, CRDTs, and Git
LFS / large binaries (a binary changed on both machines surfaces as a conflict).

## v1.3.0 — App API contract 0.10.0
Embedding ETA/rate, incremental link/tag index, `context_pack` MCP tool, reranker in `/settings`,
filter-only tag browse.

## v1.2.x — App API contract 0.8.0–0.9.0
Non-blocking keyword-first indexing + pluggable embedders, remote-embedder boot robustness,
`POST /embedder/models`, link-graph perf/parse fixes, oversized-chunk fault tolerance.

## v1.1.0 — App API contract 0.7.0
External sources (symlink import, prune, auto-sync).

## v1.0.0 / v1.0.1
First production release; GraalVM `native-image` binaries.
