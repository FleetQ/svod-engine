package dev.svod.engine.index

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Resolves the local files an ONNX embedder needs (model + tokenizer), either from a
 * pre-placed directory or by downloading — once, checksum-verified — into the cache.
 *
 * No global install is required: first run fetches the model into `.svod/models/<id>/`
 * (atomic write, SHA-256 pinned), and every later run reuses it.
 */
object ModelManager {

    const val MODEL_FILE = "model.onnx"
    const val TOKENIZER_FILE = "tokenizer.json"

    /** Bundled ONNX embedding models (downloadable without a localPath): id → vector dimension. */
    val BUNDLED: Map<String, Int> = mapOf("multilingual-e5-small" to 384)

    private data class Pinned(
        val modelUrl: String,
        val modelSha256: String,
        val tokenizerUrl: String,
        val tokenizerSha256: String,
    )

    // Pinned to exact artifacts. multilingual-e5-small is MIT-licensed (Apache-compatible);
    // the int8-quantized ONNX export comes from the Xenova mirror.
    private val PINS: Map<String, Pinned> = mapOf(
        "multilingual-e5-small" to Pinned(
            modelUrl = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx",
            modelSha256 = "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193",
            tokenizerUrl = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json",
            tokenizerSha256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",
        ),
    )

    /** Directory containing [MODEL_FILE] + [TOKENIZER_FILE], ready for DJL to load. */
    fun resolve(config: OnnxConfig, modelsDir: Path): Path {
        config.localPath?.let { dir ->
            require(Files.isRegularFile(dir.resolve(MODEL_FILE)) && Files.isRegularFile(dir.resolve(TOKENIZER_FILE))) {
                "localPath $dir must contain $MODEL_FILE and $TOKENIZER_FILE"
            }
            return dir
        }
        val pin = PINS[config.modelId] ?: error("no pinned download for model '${config.modelId}'; supply OnnxConfig.localPath")
        val dir = modelsDir.resolve(config.modelId)
        Files.createDirectories(dir)
        ensureFile(dir.resolve(MODEL_FILE), pin.modelUrl, pin.modelSha256)
        ensureFile(dir.resolve(TOKENIZER_FILE), pin.tokenizerUrl, pin.tokenizerSha256)
        return dir
    }

    private fun ensureFile(target: Path, url: String, expectedSha: String) {
        if (Files.isRegularFile(target) && sha256(target) == expectedSha) return

        val tmp = target.resolveSibling("${target.fileName}.download")
        Files.deleteIfExists(tmp)
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp))
        check(resp.statusCode() == 200) { "download failed for $url: HTTP ${resp.statusCode()}" }

        val actual = sha256(tmp)
        check(actual == expectedSha) {
            Files.deleteIfExists(tmp)
            "checksum mismatch for $url: expected $expectedSha got $actual"
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
