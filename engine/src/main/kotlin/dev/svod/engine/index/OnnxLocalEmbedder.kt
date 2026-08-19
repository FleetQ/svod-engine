package dev.svod.engine.index

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import java.nio.file.Path

/**
 * In-process embedder: multilingual-e5-small (MIT) run via DJL on the ONNX Runtime engine.
 * No external server, no global install — the model is loaded from a local directory that
 * [ModelManager] downloads-and-caches or that the caller pre-places.
 *
 * e5 correctness (silent quality killers if wrong):
 *  - input prefixes: e5's "query: " / "passage: " when the model is an e5 (see [ModelPrefixes]);
 *  - attention-mask-weighted MEAN pooling of token embeddings, then L2 normalization
 *    (handled by DJL's TextEmbeddingTranslator with pooling=mean, normalize=true);
 *  - the model's own XLM-RoBERTa tokenizer (tokenizer.json), not a generic one.
 * Output is 384-dim, unit-length.
 *
 * DJL predictors are not thread-safe; predictions are serialized. Embedding is cheap
 * relative to git/Lucene I/O, and it never runs on the write path.
 */
class OnnxLocalEmbedder private constructor(
    override val model: String,
    override val dim: Int,
    private val zoo: ZooModel<String, FloatArray>,
) : Embedder, AutoCloseable {

    // Derived from the model, not fixed: the pinned default is e5, but OnnxConfig accepts any
    // modelId/localPath, and e5's prefixes actively hurt a model trained without them.
    override val passagePrefix: String = ModelPrefixes.passagePrefix(model)
    private val queryPrefix: String = ModelPrefixes.queryPrefix(model)

    private val predictor = zoo.newPredictor()
    private val lock = Any()

    override fun embedPassages(texts: List<String>): List<FloatArray> = synchronized(lock) {
        predictor.batchPredict(texts.map { passagePrefix + it })
    }

    override fun embedQuery(text: String): FloatArray = synchronized(lock) {
        predictor.predict(queryPrefix + text)
    }

    override fun close() {
        predictor.close()
        zoo.close()
    }

    companion object {
        // @JvmStatic so the factory can load this reflectively (keeps DJL/ONNX off a native image —
        // see Embedders.create + ADR-0015): a static `load(OnnxConfig, Path)` on the class itself.
        @JvmStatic
        fun load(config: OnnxConfig, modelsDir: Path): OnnxLocalEmbedder {
            val dir = ModelManager.resolve(config, modelsDir)
            val criteria = Criteria.builder()
                .setTypes(String::class.java, FloatArray::class.java)
                .optModelPath(dir)
                .optModelName("model") // model.onnx
                .optEngine("OnnxRuntime")
                .optTranslatorFactory(TextEmbeddingTranslatorFactory())
                .optArgument("pooling", "mean")
                .optArgument("normalize", "true")
                // Per EXPORT, not per architecture — see OnnxConfig.includeTokenTypes.
                .optArgument("includeTokenTypes", config.includeTokenTypes.toString())
                .build()
            val zoo: ZooModel<String, FloatArray> = criteria.loadModel()
            val probeDim = zoo.newPredictor().use {
                it.predict(ModelPrefixes.queryPrefix(config.modelId) + "dimension probe").size
            }
            return OnnxLocalEmbedder(config.modelId, probeDim, zoo)
        }
    }
}
