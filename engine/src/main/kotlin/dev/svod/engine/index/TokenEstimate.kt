package dev.svod.engine.index

/** Cheap token estimate (~4 chars/token); avoids a tokenizer dependency, consistent with chunking. */
fun estimateTokens(text: String): Int = Math.ceil(text.length / 4.0).toInt()
