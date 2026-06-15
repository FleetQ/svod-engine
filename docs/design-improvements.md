# Svod engine — improvements backlog (requirements)

Discovery date: 2026-06-15. Baseline: v1.2.1, contract 0.8.0, 138 tests.
Scope chosen by owner: **A) Reranking · B) Observability (/metrics) · C) Close deferred.**
macOS UI client explicitly deferred (separate repo, out of engine scope).

Guiding constraints (carry from existing work): additive only · loopback-only, no auth ·
old configs keep working · API keys are Secrets refs only (`env:`/`file:`/`keychain:`) ·
verify with `./gradlew build` (run gradle via `ctx_execute(language:shell)`) · never full-re-embed a
real vault in tests · bump `apiVersion` when the wire/contract surface changes.

---

## Track A — Reranking (largest)

**Why.** Hybrid search today = BM25 + ONNX-e5 kNN fused via RRF. A cross-encoder rerank pass over the
top-K fused candidates measurably lifts relevance for semantic queries.

**Functional**
- New optional rerank stage AFTER RRF fusion, over the top-K candidates only.
- Pluggable behind an interface (mirror the `Embedder` design):
  - `local-onnx` cross-encoder (e.g. bge-reranker / ms-marco-MiniLM), DJL/ONNX — JVM-only.
  - `remote` rerank endpoint (Cohere / Jina / TEI `/rerank`), `model` + `apiKeyRef`.
- Config-gated, **default OFF**: `reranker { provider, model, endpoint?, apiKeyRef?, topK }`.
- Robustness parity with embedder: reranker failure must **degrade to RRF order**, never error the query;
  no network in constructor; lazy init.

**Non-functional**
- Native-image: local cross-encoder is JVM-only (like onnx-local); remote works in native binary.
- Throttle/timeout reuse of the embedder pattern (`requestTimeoutSeconds`).

**Acceptance**
- Enabled → results return reranked; disabled/failed → identical to current RRF order.
- `GET /settings` exposes `reranker {...}`; `apiVersion` bumped; fallback + ordering tests.

**Open questions**
- Default local reranker model? (proposal: bge-reranker-base.)
- Remote rerank wire shape — standardize on TEI/Jina `/rerank` `{query, texts}` vs Cohere. (proposal: TEI-compatible.)

---

## Track B — Observability: `/metrics` (Prometheus)

**Why.** Only Sentry + logback today. No scrape-able runtime view of indexing/search.

**Functional**
- `GET /metrics` (loopback) in Prometheus text exposition format.
- Metrics: `svod_keyword_ready` (gauge/vault), `svod_embed_done`/`_total`, embed rate, embed queue depth,
  embedding state, per-vault doc counts, search count + latency histogram.
- Additive; feature-detect via `apiVersion`.

**Non-functional**
- Keep dep-light — **hand-rolled registry** (no Micrometer) to avoid a new dep + native-image pitfalls.
- Must build in native-image.

**Acceptance**
- `/metrics` returns valid exposition format; counters/gauges move under load; `apiVersion` bumped; test.

---

## Track C — Close deferred

1. **External-source `classify→batch-write` race** (ADR-0016 deferred window).
   Snapshot blob ids and write atomically on the exec/writer thread (or recheck-before-write) so a
   concurrent vault edit during sync never clobbers and never yields a false CONFLICT. + test.
2. **Relaxed-fsync bulk import** (perf floor). Optional fsync batching on the `POST /import` path;
   default durability unchanged; tradeoff documented. Acceptance: import throughput up, default behavior identical.
3. **Crash-mid-embed resume test.** Explicit test: interrupt mid-backlog, restart, verify resume from the
   committed-index scan (mechanism exists, currently uncovered).
4. **Housekeeping.** Refresh `mem:project-status` (stale: says 0.4.0/117/license-pending → actual
   0.8.0/138/Apache-2.0). Short ADR note for the v1.2.1 boot-robustness patch.

---

## Suggested sequencing
Track C first (small, de-risks + cleans drift) → Track B (self-contained, one endpoint) →
Track A (largest, new subsystem). Each is independently shippable as its own minor release.

**Next step:** `/sc:design` per track for architecture, or `/sc:workflow` to plan implementation.
