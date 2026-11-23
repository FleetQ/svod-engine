package dev.svod.engine.index

import dev.svod.engine.lifecycle.SvodConfig
import org.apache.lucene.store.LockObtainFailedException
import java.nio.file.Path

/**
 * `svod-engine eval`: scores search on the user's own vault against a golden set.
 *
 * Runs with the engine stopped. It never opens [dev.svod.engine.core.SvodEngine], so no vault lock
 * is taken and nothing is committed; the only write is the index catching up to HEAD, exactly as
 * it does when the engine starts. Embedder and reranker come from the config, built the same way
 * the engine builds them, so the numbers are about the search the engine actually serves.
 */
object VaultEval {

    /** Metrics per mode; a null value means the mode was skipped (SEMANTIC without an embedder). */
    data class Report(val metrics: Map<SearchMode, EvalMetrics?>)

    private val MODES = listOf(SearchMode.HYBRID, SearchMode.KEYWORD, SearchMode.SEMANTIC)

    fun run(config: SvodConfig, golden: Path, out: (String) -> Unit = ::println): Report {
        val queries = GoldenSetFile.load(golden)
        val vault = config.vault()
        val ec = config.toEmbedderConfig()
        val rc = config.toRerankerConfig()
        val embedder = Embedders.create(ec, vault)
        try {
            val reranker = Rerankers.create(rc, vault)
            try {
                openIndex(config, vault, embedder, reranker, ec, rc).use { index ->
                    return evaluate(index, queries, headNotes(vault), embedder, out)
                }
            } finally {
                (reranker as? AutoCloseable)?.let { runCatching { it.close() } }
            }
        } finally {
            (embedder as? AutoCloseable)?.let { runCatching { it.close() } }
        }
    }

    private fun openIndex(
        config: SvodConfig,
        vault: Path,
        embedder: Embedder,
        reranker: Reranker,
        ec: EmbedderConfig,
        rc: RerankerConfig,
    ): IndexService {
        val index = try {
            IndexService(
                vault, vault.resolve(".svod").resolve("index"), embedder,
                blockStartup = true,
                maxThreads = ec.maxThreads,
                batchSize = ec.batchSize,
                reranker = reranker,
                rerankTopK = rc.topK,
                includeMessyInRecall = config.includeMessyInRecall,
            )
        } catch (e: Exception) {
            throw lockError(e) ?: e
        }
        try {
            return index.start()
        } catch (e: Exception) {
            runCatching { index.close() }
            throw lockError(e) ?: e
        }
    }

    private fun lockError(e: Throwable): IllegalStateException? =
        generateSequence(e) { it.cause }.take(10).firstOrNull { it is LockObtainFailedException }?.let {
            IllegalStateException("index is in use — stop the engine first (svod-engine eval runs with the engine stopped)", it)
        }

    /** Markdown paths at the vault's HEAD, read through git (no writes); empty for a vault with no commit. */
    private fun headNotes(vault: Path): Set<String> = GitReader(vault).use { git ->
        git.headCommit()?.let { git.listMarkdown(it).keys } ?: emptySet()
    }

    private fun evaluate(
        index: IndexService,
        queries: List<GoldenQuery>,
        known: Set<String>,
        embedder: Embedder,
        out: (String) -> Unit,
    ): Report {
        // A mistyped path silently becomes an unreachable relevant note and depresses recall forever.
        queries.flatMap { q -> q.gains.keys.filter { it !in known }.map { q.text to it } }.forEach { (q, path) ->
            out("warning: '$path' (query '$q') is not a note at the vault's HEAD — it can never be found")
        }

        val metrics = linkedMapOf<SearchMode, EvalMetrics?>()
        for (mode in MODES) {
            if (mode == SearchMode.SEMANTIC && !embedder.isActive) {
                out("$mode skipped: the embedder provider is none (keyword-only search)")
                metrics[mode] = null
                continue
            }
            val misses = mutableListOf<String>()
            val m = RetrievalEval.run(index, queries, mode) { q, ranked ->
                if (RetrievalEval.recallAt(ranked, q, 5) == 0.0) misses += RetrievalEval.explain(q, ranked)
            }
            metrics[mode] = m
            out(m.format(mode.toString()))
            queries.filter { it.group.isNotEmpty() }.groupBy { it.group }.forEach { (group, qs) ->
                out("  " + RetrievalEval.run(index, qs, mode).format("$mode [$group]"))
            }
            misses.forEach(out)
        }
        return Report(metrics)
    }
}
