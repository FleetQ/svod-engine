package dev.svod.engine.index

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.ndarray.NDList
import ai.djl.ndarray.types.Shape
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import ai.djl.translate.Batchifier
import ai.djl.translate.Translator
import ai.djl.translate.TranslatorContext
import ai.djl.util.PairList
import ai.djl.util.StringPair
import java.nio.file.Path

/**
 * In-process cross-encoder reranker: mmarco-mMiniLMv2-L12-H384 (Apache-2.0) run via DJL on the
 * ONNX Runtime engine. Same shape as [OnnxLocalEmbedder] — no external server, model resolved from
 * a local directory that [ModelManager] downloads-and-caches or the caller pre-places.
 *
 * A cross-encoder scores a (query, passage) PAIR in one forward pass, so it sees the interaction
 * between them that a bi-encoder's independent vectors cannot. That is why it earns a second stage:
 * it is far too expensive to run over the whole corpus, and far more accurate over ~50 candidates.
 *
 * Model facts verified against the artifacts themselves, not the model card:
 *  - graph inputs are `input_ids` + `attention_mask` only — **no `token_type_ids`**, so token types
 *    must stay OFF. The e5 embedder export needs them ON; copying that setting here fails at
 *    inference.
 *  - `XLMRobertaForSequenceClassification` with one output logit, so each pair yields a 1-element
 *    array. Raw logits, no sigmoid: only the ORDER matters and sigmoid is monotonic, so it would
 *    cost a call per pair and change nothing.
 *
 * DJL predictors are not thread-safe, so predictions are serialized on [lock]. Reranking runs
 * inline in `search()`, so concurrent searches contend here — one batched call per query keeps that
 * to a single lock acquisition rather than one per candidate.
 */
class OnnxLocalReranker private constructor(
    override val model: String,
    private val zoo: ZooModel<StringPair, FloatArray>,
) : Reranker, AutoCloseable {

    private val predictor = zoo.newPredictor()
    private val lock = Any()

    override val provider = PROVIDER

    override fun rerank(query: String, docs: List<String>): List<Float> {
        if (docs.isEmpty()) return emptyList()
        return synchronized(lock) {
            predictor.batchPredict(docs.map { StringPair(query, it) }).map { it[0] }
        }
    }

    override fun close() {
        predictor.close()
        zoo.close()
    }

    companion object {
        const val PROVIDER = "local-onnx"
        const val DEFAULT_MODEL = "mmarco-mMiniLMv2-L12-H384-v1"

        /** The model's own limit: `max_position_embeddings` is 514, so 512 real tokens. */
        const val MAX_SEQ_LEN = 512

        // @JvmStatic to match OnnxLocalEmbedder: loaded reflectively so DJL/ONNX stays off the
        // native-image path (ADR-0015).
        @JvmStatic
        fun load(config: OnnxConfig, modelsDir: Path): OnnxLocalReranker {
            val dir = ModelManager.resolve(config, modelsDir)
            val tokenizer = HuggingFaceTokenizer.newInstance(
                dir.resolve(ModelManager.TOKENIZER_FILE),
                mapOf("truncation" to "true", "maxLength" to MAX_SEQ_LEN.toString()),
            )
            val criteria = Criteria.builder()
                .setTypes(StringPair::class.java, FloatArray::class.java)
                .optModelPath(dir)
                .optModelName("model") // model.onnx
                .optEngine("OnnxRuntime")
                .optTranslator(PairScoringTranslator(tokenizer))
                .build()
            return OnnxLocalReranker(config.modelId, criteria.loadModel())
        }
    }
}

/**
 * Tokenises (query, passage) pairs and feeds the ONNX graph directly.
 *
 * DJL ships a [ai.djl.huggingface.translator.CrossEncoderTranslator] that looks like the obvious
 * choice, and it does not work here: on DJL 0.30.0 its tensors reach ONNX Runtime as **uint32**,
 * which `OrtUtils.toTensor` rejects outright —
 * `UnsupportedOperationException: Data type not supported: uint32`. Building the tensors here is
 * what makes the dtype explicit and checkable: **int64**, which is what the HF export declares.
 *
 * Batching is done here rather than by a [Batchifier] because the pairs have different lengths.
 * Returning `null` from [getBatchifier] tells `Predictor` to hand the whole list to
 * [batchProcessInput], so the batch is padded to the longest member instead of to
 * [OnnxLocalReranker.MAX_SEQ_LEN] — for typical chunk sizes that is several times less compute per
 * query.
 */
private class PairScoringTranslator(private val tokenizer: HuggingFaceTokenizer) : Translator<StringPair, FloatArray> {

    override fun getBatchifier(): Batchifier? = null

    override fun processInput(ctx: TranslatorContext, input: StringPair): NDList =
        batchProcessInput(ctx, listOf(input))

    override fun processOutput(ctx: TranslatorContext, list: NDList): FloatArray =
        batchProcessOutput(ctx, list).first()

    override fun batchProcessInput(ctx: TranslatorContext, inputs: List<StringPair>): NDList {
        val pairs = PairList<String, String>()
        inputs.forEach { pairs.add(it.key, it.value) }
        val encodings = tokenizer.batchEncode(pairs)
        val width = encodings.maxOf { it.ids.size }

        // Padded by hand: the pad id is only reachable through the embedding table, and attention
        // masks it out, so what matters is that every row is the same width and in vocabulary.
        val ids = LongArray(inputs.size * width) { PAD_TOKEN_ID }
        val mask = LongArray(inputs.size * width)
        encodings.forEachIndexed { row, e ->
            e.ids.copyInto(ids, row * width)
            e.attentionMask.copyInto(mask, row * width)
        }

        val shape = Shape(inputs.size.toLong(), width.toLong())
        val m = ctx.ndManager
        // ONNX binds inputs by NAME, not position — an unnamed array silently binds to the wrong slot.
        return NDList(
            m.create(ids, shape).apply { name = "input_ids" },
            m.create(mask, shape).apply { name = "attention_mask" },
        )
    }

    override fun batchProcessOutput(ctx: TranslatorContext, list: NDList): List<FloatArray> =
        list.singletonOrThrow().toFloatArray().map { floatArrayOf(it) }

    private companion object {
        // XLM-RoBERTa pad id, from the model's own config.json.
        const val PAD_TOKEN_ID = 1L
    }
}
