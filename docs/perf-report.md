# Svod Engine — Scale & Soak Performance Report

Validation of the Svod engine (Kotlin/JVM, git-backed markdown vault + Lucene index)
at scale. All numbers below are **real, measured** values from a single authoritative
run of `LargeVaultPerfTest` at 5,000 notes; the 1,000-note row is from a calibration run.

## Methodology

- **Test:** `engine/src/test/kotlin/dev/svod/engine/perf/LargeVaultPerfTest.kt` — opt-in,
  gated on `-Dsvod.perf=true` via JUnit `Assumptions.assumeTrue`, so it is **skipped in the
  normal suite** (verified: `tests="1" skipped="1" failures="0"` with no flag).
- **Embedder:** `NoneEmbedder` (BM25-only). No model download, no ONNX. The numbers reflect
  the engine write path, per-write git commit, and Lucene lexical indexing — exactly the
  components we want to characterise.
- **Workload:** N notes written through the engine's normal `write()` path (each write =
  one atomic file write + one `git commit`). Each note has YAML frontmatter (`tags`,
  `created`), an `H1` plus two `H2` sections (⇒ ~3 Lucene chunks/note), mixed ASCII +
  Cyrillic prose, and `[[wikilinks]]` to neighbouring notes. A unique `tok<i>` token per
  note makes every note individually retrievable.
- **Run host:** macOS (darwin), JDK 20, Gradle test JVM `maxHeapSize = 2g`.
- **Command (run from `engine/`):**
  ```
  JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test \
    --tests "dev.svod.engine.perf.LargeVaultPerfTest" -Dsvod.perf=true --rerun-tasks
  ```
  Parameterizable: `-Dsvod.perf.notes=N` (default 5000), `-Dsvod.perf.writers=N` (default 64).

### Measurement details

- **Write throughput:** wall-clock around the full write phase; writes issued from an
  8-way coroutine pool to keep the single-writer actor saturated. notes ÷ seconds.
- **Index catch-up:** the engine fires `onCommit` after every commit, which enqueues an
  incremental index sync. We `waitIdle()` + bounded-poll until the index reaches the
  expected chunk count (3·N), then report that tail time. `docCount()`/`numDocs()` counts
  **Lucene chunks**, not files, so the target is 3·N (file-level coverage is checked via
  `engine.list()`).
- **Search latency:** 80 varied queries (unique-token keyword lookups, common-vocabulary
  HYBRID/KEYWORD queries with large candidate sets, tag + path-prefix filtered queries,
  `created`-range filtered queries; ASCII and Cyrillic). 10 warm-up queries excluded.
  p50/p95/p99 from `System.nanoTime()`.
- **Memory:** `System.gc()` twice + settle, then used heap = `totalMemory − freeMemory`,
  with the full vault + index resident.
- **Queue depth:** a burst of 64 concurrent `write()` calls; a sampler thread polls
  `queueDepth()` while in flight, and we also read the actor's own `peakQueueDepth()`.

## Results

### 5,000 notes (authoritative run)

| Metric | Value |
|---|---|
| Bulk write+commit throughput | **6.1 notes/sec** (165.2 ms/note; 826 s wall for 5,000) |
| Index files / chunks | 5,000 files / 15,000 chunks (3/note) — exact |
| Index build / catch-up time | **~0.00 s** (fully overlapped with writes; live-consistent) |
| Search p50 | **0.62 ms** |
| Search p95 | **3.89 ms** |
| Search p99 | **10.09 ms** |
| Search max | 23.63 ms |
| Used heap (post-GC, 5k vault+index) | **12.8 MB** (2.6 KB/note); max heap 2,048 MB |
| Write-actor peak queue depth (64-writer burst) | **64** (actor + sampled); drained to 0 |
| Test result | `tests=1 skipped=0 failures=0 errors=0` (PASS) |

### Throughput vs. scale (write path)

| Notes | Throughput | ms/note |
|---|---|---|
| 1,000 | 15.9 notes/sec | 62.8 |
| 5,000 | 6.1 notes/sec | 165.2 |

Throughput degrades ~2.6× between 1k and 5k notes — see bottleneck #1.

## Bottlenecks

1. **Per-write git commit cost dominates, and it grows with vault size (headline).**
   Every `write()` performs `git.commitAll(...)` — stage + write tree + commit. At 1,000
   notes the vault sustains 15.9 notes/sec; at 5,000 it drops to 6.1 notes/sec (62.8 →
   165.2 ms/note). The super-linear slowdown points at git work that scales with the number
   of tracked files (index refresh / tree construction over a growing working tree), not at
   the actor or Lucene. Writes are the only slow part of the system at scale.

2. **Single-commit-per-write granularity.** Bulk ingestion (5,000 notes) is 5,000 separate
   commits. This is correct for the product's per-edit-history guarantee, but it makes
   large imports (e.g. the Obsidian import path) slow and produces a very deep history.

3. **Search, indexing, and memory are NOT bottlenecks.** Index catch-up is effectively free
   (incremental sync rides each commit and stays live-consistent — 0.00 s tail at 5k).
   Search p95 is 3.89 ms (≈50× under the 200 ms risk line). Heap is 12.8 MB for the whole
   5k-note vault + index with no growth signal.

## Risks

- **No p95/memory risk.** p95 search 3.89 ms ≪ 200 ms; heap 12.8 MB with a flat, GC-collapsible
  footprint — no leak signal across a full build + 80 searches + a write burst.
- **Write-throughput risk for bulk import.** 6.1 notes/sec means a 50k-note import would take
  ~2.3 hours at current per-commit cost. This is a real product risk for large-vault onboarding.
- **Queue depth is bounded and healthy.** A 64-writer burst peaks at exactly 64 and drains to
  0 — the actor applies natural back-pressure (callers suspend on the channel) rather than
  dropping or unbounded-buffering. No starvation observed.
- **Test-harness caveat (not an engine risk).** The opt-in flag only reaches the forked test
  JVM if `engine/build.gradle.kts` forwards `svod.perf*` system properties. The current
  `tasks.test {}` forwards only `svod.ollama.it`. See "Tuning recommendations" #5.

## Tuning recommendations (report-only — no engine source changed)

1. **Batch-commit path for bulk ingestion.** Add an engine API that writes M files and emits a
   **single** commit (e.g. `writeBatch(...)` or an explicit `beginImport()/commitImport()`
   bracket). This would collapse 5,000 commits into a handful and is the highest-leverage win
   for import throughput, with no change to the per-edit guarantee for interactive writes.

2. **Reduce per-commit git overhead.** Profile `git.commitAll` at 5k vs 1k notes to confirm the
   working-tree-scan hypothesis. Options: stage only the changed path instead of `add -A`/full
   scan; reuse a cached tree builder; or keep an in-memory index that's flushed periodically.

3. **Decouple durability from commit frequency for imports.** Atomic file writes already give
   crash-safety (the engine completes interrupted writes on recovery); imports could land all
   files atomically first and commit once at the end, getting durability without N commits.

4. **Keep the index design as-is.** Incremental `onCommit` sync is already optimal here —
   catch-up is free and search is sub-4 ms p95 at 15k chunks. No tuning needed; revisit only
   when an active (ONNX/Ollama) embedder is in the write loop, where embedding cost will
   dominate index time (out of scope for this BM25-only run).

5. **Forward the perf flag in Gradle (harness fix).** Add `svod.perf`, `svod.perf.notes`,
   `svod.perf.writers` to the `systemProperty` forwarding in `engine/build.gradle.kts`'s
   `tasks.test {}` block, mirroring the existing `svod.ollama.it` line. Without it the
   documented `-Dsvod.perf=true` command silently skips (the property never reaches the test
   JVM). This report's run used exactly that one-line forwarding addition in a throwaway
   worktree; the engine source and the committed test are unchanged.

## Reproduction notes

- Run from `engine/`. Default 5,000 notes ≈ 14 min wall (dominated by the write phase).
  For a fast smoke run use `-Dsvod.perf.notes=1000` (~1 min).
- Normal `./gradlew test` (no flag) skips this test — confirmed `skipped="1"`.
