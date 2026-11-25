package dev.svod.engine.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns a run exported from a tracing platform (LangSmith runs, Braintrust log rows) into the plain
 * `role: text` transcript `extract_memories` takes. The export may be one JSON object, a JSON array
 * of them, or JSONL (one object per line). Roles are normalised to user/assistant/system/tool.
 * Only plain text is kept: tool-call arguments, images and other non-text parts are dropped.
 * Never throws; an export it cannot read is a [Result.failure] with a readable reason. Reasons
 * never quote the export, which has not been secret-scanned yet.
 */
object TraceTranscripts {

    /** The export formats [toTranscript] reads. */
    val FORMATS = listOf("langsmith", "braintrust")

    private val json = Json { isLenient = true }

    private data class Msg(val role: String, val text: String)

    fun toTranscript(format: String, export: String): Result<String> = runCatching {
        val fmt = format.trim().lowercase()
        if (fmt !in FORMATS) fail("unknown format '${format.trim().take(40)}'; use text, ${FORMATS.joinToString(" or ")}")
        val records = parseRecords(export)
        val emitted = mutableListOf<Msg>()
        for (record in records) {
            val messages = when (fmt) {
                "langsmith" -> langsmith(record)
                else -> braintrust(record)
            }
            appendWithoutReplay(emitted, messages)
        }
        if (emitted.isEmpty()) fail("no messages found in the $fmt export")
        emitted.joinToString("\n") { "${it.role}: ${it.text}" }
    }.recoverCatching { e -> throw (e as? ReadFailure ?: ReadFailure("unreadable export (${e::class.simpleName})")) }

    private class ReadFailure(message: String) : IllegalArgumentException(message)

    private fun fail(reason: String): Nothing = throw ReadFailure(reason)

    // ---- input shapes ----

    private fun parseRecords(export: String): List<JsonObject> {
        val text = export.trim()
        if (text.isEmpty()) fail("the export is empty")
        val whole = runCatching { json.parseToJsonElement(text) }.getOrNull()
        val elements: List<JsonElement> = when {
            whole is JsonArray -> whole.toList()
            whole != null -> listOf(whole)
            else -> text.lines().mapIndexedNotNull { i, line ->
                if (line.isBlank()) null
                else runCatching { json.parseToJsonElement(line.trim()) }.getOrNull()
                    ?: fail("not valid JSON or JSONL (line ${i + 1} does not parse)")
            }
        }
        val records = elements.filterIsInstance<JsonObject>()
        if (records.isEmpty()) fail("the export holds no JSON objects")
        return records
    }

    /** A later run repeats the conversation so far as its history: emit only what is new. */
    private fun appendWithoutReplay(emitted: MutableList<Msg>, messages: List<Msg>) {
        val max = minOf(emitted.size, messages.size)
        var skip = 0
        for (k in max downTo 1) {
            val head = messages.subList(0, k)
            if (head == emitted.subList(0, k) || head == emitted.subList(emitted.size - k, emitted.size)) { skip = k; break }
        }
        emitted += messages.drop(skip)
    }

    // ---- LangSmith ----

    private fun langsmith(run: JsonObject): List<Msg> {
        val out = mutableListOf<Msg>()
        (run["inputs"] as? JsonObject)?.let { inputs ->
            val msgs = inputs["messages"]
            when {
                msgs is JsonArray -> out += messageList(msgs, "user")
                else -> (inputs["input"] ?: inputs["question"])?.let { out += anyAsMessages(it, "user") }
            }
        }
        // LangGraph-style outputs carry the whole message state, inputs included: keep only what is new.
        (run["outputs"] as? JsonObject)?.let { outputs ->
            appendWithoutReplay(out, when {
                outputs["messages"] is JsonArray -> messageList(outputs["messages"] as JsonArray, "assistant")
                (outputs["message"] ?: JsonNull) !is JsonNull -> anyAsMessages(outputs["message"]!!, "assistant")
                outputs["generations"] is JsonArray -> generations(outputs["generations"] as JsonArray)
                outputs["choices"] is JsonArray -> choices(outputs["choices"] as JsonArray)
                else -> (outputs["output"] ?: outputs["answer"])?.let { anyAsMessages(it, "assistant") } ?: emptyList()
            })
        }
        return out
    }

    private fun generations(gens: JsonArray): List<Msg> = gens.flatMap { g ->
        val items = (g as? JsonArray)?.toList() ?: listOf(g)
        items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            o["message"]?.let { message(it, "assistant") } ?: textOf(o["text"])?.let { Msg("assistant", it) }
        }
    }

    // ---- Braintrust ----

    private fun braintrust(row: JsonObject): List<Msg> {
        val out = mutableListOf<Msg>()
        row["input"]?.let { out += anyAsMessages(it, "user") }
        row["output"]?.let { appendWithoutReplay(out, anyAsMessages(it, "assistant")) }
        return out
    }

    // ---- shared message shapes ----

    /** A string, a message, a messages array, or an object holding `messages` or OpenAI `choices`. */
    private fun anyAsMessages(e: JsonElement, defaultRole: String): List<Msg> = when (e) {
        is JsonNull -> emptyList()
        is JsonPrimitive -> textOf(e)?.let { listOf(Msg(defaultRole, it)) } ?: emptyList()
        is JsonArray -> messageList(e, defaultRole)
        is JsonObject -> when {
            e["choices"] is JsonArray -> choices(e["choices"] as JsonArray)
            e["messages"] is JsonArray -> messageList(e["messages"] as JsonArray, defaultRole)
            else -> listOfNotNull(message(e, defaultRole))
        }
    }

    private fun choices(choices: JsonArray): List<Msg> = choices.mapNotNull { c ->
        val o = c as? JsonObject ?: return@mapNotNull null
        o["message"]?.let { message(it, "assistant") } ?: textOf(o["text"])?.let { Msg("assistant", it) }
    }

    /** A list of messages, or a list of such lists (LangSmith chat-model inputs). Only one level deep. */
    private fun messageList(list: JsonArray, defaultRole: String): List<Msg> = list.flatMap { item ->
        val items = (item as? JsonArray)?.toList() ?: listOf(item)
        items.mapNotNull { message(it, defaultRole) }
    }

    private fun message(e: JsonElement, defaultRole: String): Msg? {
        if (e is JsonPrimitive) return textOf(e)?.let { Msg(defaultRole, it) }
        val o = e as? JsonObject ?: return null
        // Serialized LangChain message: {lc, type: "constructor", id: [..., "HumanMessage"], kwargs: {content}}.
        val kwargs = o["kwargs"] as? JsonObject
        if (kwargs != null && o["id"] is JsonArray) {
            val cls = ((o["id"] as JsonArray).lastOrNull() as? JsonPrimitive)?.content.orEmpty()
            val role = (kwargs["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: cls
            return textOf(kwargs["content"])?.let { Msg(normaliseRole(role, defaultRole), it) }
        }
        // An OpenAI choice ({index, message, finish_reason}), e.g. a Braintrust output logged as `choices`.
        val inner = o["message"]
        if (inner is JsonObject && o["content"] == null && o["role"] == null) return message(inner, "assistant")
        // {type: "human", data: {content}} (LangChain message dict).
        val data = o["data"] as? JsonObject
        val role = str(o, "role") ?: str(o, "type") ?: data?.let { str(it, "role") ?: str(it, "type") }
        val content = o["content"] ?: data?.get("content") ?: o["text"]
        return textOf(content)?.let { Msg(normaliseRole(role, defaultRole), it) }
    }

    private fun str(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** Plain text of a content value: a string, or the text parts of a parts array. */
    private fun textOf(e: JsonElement?): String? {
        val text = when (e) {
            null, is JsonNull -> null
            is JsonPrimitive -> if (e.isString) e.content else null
            is JsonArray -> e.mapNotNull { part ->
                when (part) {
                    is JsonPrimitive -> if (part.isString) part.content else null
                    is JsonObject -> {
                        val type = str(part, "type")
                        if (type == null || type == "text" || type == "input_text" || type == "output_text") str(part, "text") else null
                    }
                    else -> null
                }
            }.joinToString("\n").takeIf { it.isNotEmpty() }
            is JsonObject -> null
        }
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun normaliseRole(raw: String?, defaultRole: String): String {
        val r = raw?.trim()?.lowercase()?.removeSuffix("chunk")?.takeIf { it.isNotEmpty() } ?: return defaultRole
        return when (r) {
            "user", "human", "humanmessage" -> "user"
            "assistant", "ai", "aimessage", "model", "bot" -> "assistant"
            "system", "systemmessage", "developer" -> "system"
            "tool", "toolmessage", "function", "functionmessage" -> "tool"
            // Anything else (a part type like "text", a custom role, one with line breaks) must not
            // reach the transcript as its own "role:" prefix.
            else -> defaultRole
        }
    }
}
