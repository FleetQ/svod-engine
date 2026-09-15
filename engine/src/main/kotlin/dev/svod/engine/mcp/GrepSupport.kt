package dev.svod.engine.mcp

internal const val TREE_MAX_DEPTH = 10
internal const val TREE_MAX_FOLDERS = 500
internal const val GREP_MAX_LIMIT = 500
internal const val GREP_TEXT_CHARS = 240
internal const val GREP_BUDGET_NANOS = 2_000_000_000L

/** Thrown by [DeadlineCharSequence] once a grep call's time budget is spent. No stack trace: it is control flow. */
internal class GrepDeadline : RuntimeException(null, null, false, false)

/**
 * A Java regex cannot be interrupted, and a pattern like `(a+)+$` backtracks exponentially — on a shared
 * engine one agent's bad pattern would pin a CPU for as long as it likes. The matcher reads its input
 * through `charAt`, so checking the clock there bounds every match, however pathological the pattern.
 */
internal class DeadlineCharSequence(private val inner: CharSequence, private val deadlineNanos: Long) : CharSequence {
    private var reads = 0

    override val length: Int get() = inner.length

    override fun get(index: Int): Char {
        if ((++reads and 0xFFF) == 0 && System.nanoTime() > deadlineNanos) throw GrepDeadline()
        return inner[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineCharSequence(inner.subSequence(startIndex, endIndex), deadlineNanos)

    override fun toString(): String = inner.toString()
}
