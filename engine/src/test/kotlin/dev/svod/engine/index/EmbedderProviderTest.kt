package dev.svod.engine.index

import dev.svod.engine.lifecycle.SvodConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Provider selection + backward-compatible config mapping (STEP 2). */
class EmbedderProviderTest {

    private fun cfg(settings: SvodConfig.EmbedderSettings) =
        SvodConfig(vaultPath = "/tmp/vault", embedder = settings)

    @Test
    fun `provider names map (incl both spellings)`() {
        fun providerOf(p: String) = cfg(SvodConfig.EmbedderSettings(provider = p)).toEmbedderConfig().provider
        assertEquals(EmbedderProvider.ONNX_LOCAL, providerOf("onnx-local"))
        assertEquals(EmbedderProvider.ONNX_LOCAL, providerOf("local-onnx"))
        assertEquals(EmbedderProvider.OLLAMA, providerOf("ollama"))
        assertEquals(EmbedderProvider.OLLAMA, providerOf("local-ollama"))
        assertEquals(EmbedderProvider.OPENAI, providerOf("openai"))
        assertEquals(EmbedderProvider.OPENAI, providerOf("remote-openai"))
        assertEquals(EmbedderProvider.NONE, providerOf("none"))
    }

    @Test
    fun `legacy config (onnxModelId only) still works`() {
        val ec = cfg(SvodConfig.EmbedderSettings(onnxModelId = "multilingual-e5-small")).toEmbedderConfig()
        assertEquals(EmbedderProvider.ONNX_LOCAL, ec.provider)
        assertEquals("multilingual-e5-small", ec.onnx.modelId)
        assertNull(ec.openaiApiKeyRef)
        // and validates clean
        assertEquals(emptyList(), cfg(SvodConfig.EmbedderSettings(onnxModelId = "multilingual-e5-small")).validate())
    }

    @Test
    fun `generic endpoint and model override the provider defaults`() {
        val openai = cfg(SvodConfig.EmbedderSettings(
            provider = "remote-openai", endpoint = "https://api.together.xyz", model = "BAAI/bge-large-en-v1.5", apiKeyRef = "env:TOGETHER_KEY",
        )).toEmbedderConfig()
        assertEquals("https://api.together.xyz", openai.openaiEndpoint)
        assertEquals("BAAI/bge-large-en-v1.5", openai.openaiModel)
        assertEquals("env:TOGETHER_KEY", openai.openaiApiKeyRef)

        val ollama = cfg(SvodConfig.EmbedderSettings(
            provider = "local-ollama", endpoint = "http://127.0.0.1:9999", model = "nomic-embed-text",
        )).toEmbedderConfig()
        assertEquals("http://127.0.0.1:9999", ollama.ollamaEndpoint)
        assertEquals("nomic-embed-text", ollama.ollamaModel)
    }

    @Test
    fun `validation accepts the new provider names and rejects unknown`() {
        assertEquals(emptyList(), cfg(SvodConfig.EmbedderSettings(provider = "remote-openai")).validate())
        assertEquals(emptyList(), cfg(SvodConfig.EmbedderSettings(provider = "local-ollama")).validate())
        assertTrue(cfg(SvodConfig.EmbedderSettings(provider = "wizardry")).validate().any { it.contains("provider must be one of") })
    }
}
