# Svod engine — improvements backlog v2 (requirements)

Discovery: 2026-06-15. Baseline: v1.2.4, App API contract 0.9.0, 153 tests.
Scope chosen by owner: **1) Incremental link/tag index · 2) Context-pack MCP tool · 3) Local reranker
+ exposure · 4) Embedding throughput + ETA.**

Constraints (carry from the engine): additive · App API loopback-only, no auth · MCP keeps per-agent
auth · API keys are Secrets refs only · single-writer integrity (Lucene writes + git reads on the one
`exec` indexer thread) · native-image binary serves only non-DJL providers · verify with
`./gradlew build` (gradle via `ctx_execute`) · never full-re-embed a real vault in tests · bump the
contract apiVersion only when the wire surface changes (then the 4 release spots + README contract line).

---

## Track 1 — Incremental link + tag index (efficiency)

**Why.** The link graph + tag counts are rebuilt in full on every HEAD change (cached by HEAD, but
`O(notes)` on each miss; agents commit often). `IndexService.sync` already parses every *changed* file
on commit — the graph/tags can ride that same diff and be maintained incrementally, like the Lucene
index.

**Functional**
- Maintain outlinks, backlinks (reverse index), and tag counts incrementally from the commit diff
  (added / modified / deleted / renamed), on the `exec` thread.
- `/file/links`, `/graph`, `/tags`, and cross-vault backlinks served from the maintained structure.
- Survive restart: rebuild once on boot (or persist), then maintain.

**Non-functional / the hard part**
- **Resolution is not purely per-file**: adding/removing a note can resolve previously-broken links
  elsewhere, or make a basename ambiguous (the `unique basename` rule). Incremental update must
  re-resolve every link that targets an affected basename/path, not just the changed file's own links.
- Must match a full `LinkGraph.build` exactly across a write/rename/delete sequence.

**Acceptance**
- Graph/tags identical to full-rebuild after an arbitrary mutation sequence (property-style test).
- `/file/links` is `O(changed)` not `O(notes)`; restart preserves correctness.

**Open questions**
- Persist the index, or rebuild-on-boot-then-maintain? (proposal: rebuild-on-boot, in-memory + maintain —
  simpler, boot already reads all notes.)
- Affected-basename recomputation strategy (re-resolve the bucket vs full re-resolve)?

---

## Track 2 — Context-pack MCP tool (usefulness — biggest lever)

**Why.** Svod is *auditable agent memory*. A single call that returns an assembled, budgeted, cited
context block is far more useful to an agent than raw search hits.

**Functional**
- New MCP tool `context_pack` (App API optional): input = query, token budget, optional filters/vault.
- Pipeline: hybrid search (BM25 + kNN + RRF) → optional rerank → dedup near-duplicates → assemble to
  the token budget, preferring source diversity → return blocks each with **provenance** (path,
  heading, commit, author, score).
- Degrade to keyword-only when the embedder is down (mirror search).

**Non-functional**
- Deterministic; no network beyond the embedder/reranker.
- Token estimate reuses the chunker's char-budget heuristic (no per-token tokenizer dependency).

**Acceptance**
- Output ≤ budget tokens, near-duplicates collapsed, every block carries provenance.
- Empty/embedder-down still returns a useful keyword pack.

**Open questions**
- Token estimation: char heuristic vs a real tokenizer? (proposal: char heuristic, consistent with chunking.)
- Dedup threshold (cosine) and whether to return chunk vs surrounding note context?
- Expose on the App API too, or MCP-only first?

---

## Track 3 — Local reranker + exposure (usefulness)

**Why.** Reranking is remote-only today; close the story with an in-process option and make it visible.

**Functional**
- `LocalOnnxReranker` (DJL/ONNX cross-encoder, bge-reranker-base), `RerankerProvider.LOCAL_ONNX`,
  config `reranker.provider = "local-onnx"`. Model download/cache via the `ModelManager` pin pattern.
- Expose the active reranker in `GET /settings` (and `index/status` if useful).

**Non-functional**
- JVM-only: exclude from `nativeImageClasspath` like the DJL embedder; a native binary serves
  remote/none rerankers only. No network in ctor; lazy model load; degrade-to-fused on failure (as today).

**Acceptance**
- Local rerank reorders results; `/settings` reports the reranker; native build still compiles/runs.

**Open questions**
- Pin which bge-reranker ONNX artifact/mirror (SHA-pinned, download-on-first-use)?
- Contract bump to **0.10.0** for the `/settings` reranker field — acceptable? (additive, same major.)
- Config-only, or also a `PUT /reranker` runtime control (like `/embedder`)?

---

## Track 4 — Embedding throughput + ETA (efficiency)

**Why.** Local ONNX embedding is CPU-bound (the original multi-minute re-embed), and there's no
progress ETA for the UI.

**Functional**
- `index/status` + the `index.progress` event report a rolling **chunks/sec** rate and an estimated
  **remaining seconds**.
- Throughput: opt-in GPU execution provider for onnxruntime when available (CoreML on macOS arm64 /
  CUDA on Linux), auto-detect with clean CPU fallback; tunable worker concurrency (already `maxThreads`).

**Non-functional**
- GPU strictly optional, graceful fallback, no hard dependency or build-size regression on CPU users.
- ETA cheap (rolling window over recent commits); no extra passes.

**Acceptance**
- `index/status` shows rate + eta during a pass; GPU path used when present, falls back cleanly when not.

**Open questions**
- First GPU EP to target (CoreML mac-arm64 vs CUDA linux) and the DJL onnxruntime-GPU packaging cost?
- ETA smoothing window size?

---

## Contract impact
Tracks 3 (`/settings` reranker) and 4 (`index/status` rate/eta), and Track 2 if exposed on the App API,
are additive → bump the App API contract to **0.10.0** (same major; existing clients unaffected). Track 1
has no wire change.

## Suggested sequencing
Quick-win first, biggest-design-surface last: **4 (ETA portion) → 3 (local reranker) → 1 (incremental
index, careful correctness) → 2 (context-pack)**. Or front-load **2** if agent usefulness is the priority.

**Next step:** `/sc:design` per track for architecture, or `/sc:workflow` to plan implementation.
