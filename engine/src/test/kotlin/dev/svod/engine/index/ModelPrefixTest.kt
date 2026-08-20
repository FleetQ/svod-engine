package dev.svod.engine.index

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the input-prefix contract.
 *
 * `OllamaEmbedder` hardcoded e5's `query: ` / `passage: ` for every model it was pointed at. That is
 * correct for its default (multilingual-e5-large) and wrong for bge-m3, which BGE's own README says
 * needs no instruction — and the failure is entirely silent: retrieval still works, just worse,
 * with nothing in any log to say so.
 *
 * Nothing here needs a model or a network: the prefix decision is a pure function of the model name,
 * and the request body is the observable side of it.
 */
class ModelPrefixTest {

    @Test
    fun `e5 models keep their prefixes and BGE gets none`() {
        for (e5 in listOf("multilingual-e5-small", "intfloat/multilingual-e5-large", "e5-base-v2")) {
            assertEquals("query: ", ModelPrefixes.queryPrefix(e5), e5)
            assertEquals("passage: ", ModelPrefixes.passagePrefix(e5), e5)
        }
        for (other in listOf("bge-m3", "BAAI/bge-m3", "bge-large-en-v1.5", "nomic-embed-text")) {
            assertEquals("", ModelPrefixes.queryPrefix(other), other)
            assertEquals("", ModelPrefixes.passagePrefix(other), other)
        }
    }

    @Test
    fun `the Ollama request body carries a prefix only for e5`() {
        val e5 = OllamaEmbedder(model = "multilingual-e5-large")
        assertTrue(e5.requestBody(listOf("passage: hello")).contains("passage: hello"))
        assertEquals("passage: ", e5.passagePrefix)

        // The actual defect: the text sent to bge-m3 must be the text itself, unadorned.
        val bge = OllamaEmbedder(model = "bge-m3")
        val body = bge.requestBody(listOf("хибридно търсене"))
        assertFalse(body.contains("passage: "), "bge-m3 must not be sent e5's passage prefix: $body")
        assertFalse(body.contains("query: "), "bge-m3 must not be sent e5's query prefix: $body")
        assertEquals("", bge.passagePrefix)
    }

    @Test
    fun `an index built with a different prefix is incompatible`() {
        val built = IndexMeta(IndexMeta.SCHEMA_VERSION, "bge-m3", 1024, "abc", passagePrefix = "passage: ")
        assertFalse(
            built.compatibleWith(IndexMeta.SCHEMA_VERSION, "bge-m3", 1024, passagePrefix = ""),
            "same model and dim, but the passages were embedded with a prefix the queries no longer use",
        )
        assertTrue(built.compatibleWith(IndexMeta.SCHEMA_VERSION, "bge-m3", 1024, passagePrefix = "passage: "))
    }

    @Test
    fun `metadata written before the field existed reads as e5-prefixed`() {
        // Every such index WAS built with the hardcoded prefix, so that — not "" — is the truthful
        // default. It is what makes the migration precise instead of re-embedding every vault.
        val legacy = """{"schemaVersion":1,"embeddingModel":"bge-m3","embeddingDim":1024,"headCommit":"abc"}"""
        val meta = Json { ignoreUnknownKeys = true }.decodeFromString(IndexMeta.serializer(), legacy)
        assertEquals("passage: ", meta.passagePrefix)
        assertFalse(meta.compatibleWith(1, "bge-m3", 1024, passagePrefix = ""), "must migrate")
        assertTrue(meta.compatibleWith(1, "bge-m3", 1024, passagePrefix = "passage: "), "must not migrate needlessly")
    }

    @Test
    fun `a lexical-only index is not wiped by the new prefix field`() {
        // A BM25-only vault stores dim 0 and holds no vectors, so it has no prefix to disagree
        // about. Comparing one anyway would have re-indexed every lexical vault on upgrade — a
        // full git re-read for a difference that cannot exist in its data.
        val legacy = """{"schemaVersion":1,"embeddingModel":"none","embeddingDim":0,"headCommit":"abc"}"""
        val meta = Json { ignoreUnknownKeys = true }.decodeFromString(IndexMeta.serializer(), legacy)
        assertEquals("passage: ", meta.passagePrefix)
        assertTrue(
            meta.compatibleWith(1, "none", 0, passagePrefix = ""),
            "a lexical-only index must survive the upgrade untouched",
        )
    }

    @Test
    fun `changing only the prefix re-embeds the vault`() = runBlocking {
        IndexFixture.create().use { fx ->
            fx.seed("one.md", "# One\nphotosynthesis converts light to energy")
            fx.seed("two.md", "# Two\nmitochondria are the powerhouse of the cell")

            val prefixed = FakeEmbedder("some-model", dim = 64, passagePrefix = "passage: ")
            val total: Int
            fx.newIndex(prefixed).use { idx ->
                total = prefixed.passageCalls.get()
                assertTrue(total >= 2)
                assertEquals(total, idx.docCount())
            }

            // Same model name, same dimension — only the prefix changed. The vectors are no longer
            // comparable, so this has to be treated exactly like a model change.
            val bare = FakeEmbedder("some-model", dim = 64, passagePrefix = "")
            fx.newIndex(bare).use { idx ->
                assertEquals(total, bare.passageCalls.get(), "a prefix change must re-embed every chunk")
                assertEquals(total, idx.docCount())
            }
        }
    }
}
