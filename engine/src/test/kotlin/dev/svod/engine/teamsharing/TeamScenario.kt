package dev.svod.engine.teamsharing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal data class Report(
    val notesPerAgent: Int, val sharedEditsPerAgent: Int, val writers: Int,
    val durationMs: Long, val successfulWrites: Int, val commits: Int, val conflicts: Int,
    val rateLimitedWaits: Int, val peakQueueDepth: Int, val drainMs: Long,
) {
    override fun toString() =
        "notes/agent=$notesPerAgent sharedEdits/agent=$sharedEditsPerAgent writers=$writers durationMs=$durationMs " +
            "successfulWrites=$successfulWrites commits=$commits conflicts=$conflicts rateLimitWaits=$rateLimitedWaits " +
            "peakQueueDepth=$peakQueueDepth drainMs=$drainMs"
}

private const val SHARED = "shared/log.md"
private const val END = "<!-- end -->"

/**
 * Every writer/maintainer agent concurrently writes [notesPerAgent] distinct notes and appends
 * [sharedEditsPerAgent] unique lines to ONE shared note through the expectedRevision flow, retrying on
 * conflict. Asserts zero loss, one commit per successful write, conflicts surfaced with `current` +
 * `currentContent`, attribution (person + Svod-Agent trailer), a drained write queue and a clean fsck.
 */
internal suspend fun runConcurrentScenario(fx: TeamFixture, notesPerAgent: Int, sharedEditsPerAgent: Int): Report = coroutineScope {
    val baselineCommits = fx.commits().map { it.name }.toSet()
    val writers = fx.writers
    val clients = writers.map { fx.connect(it) }
    try {
        // commit id -> agent that got it back from a successful call
        val commitOwner = ConcurrentHashMap<String, String>()
        val successes = AtomicInteger()
        val conflicts = AtomicInteger()
        val conflictsMissingFields = AtomicInteger()
        val failures = ConcurrentHashMap<String, String>()

        val seed = clients.first().call("write", mapOf("path" to SHARED, "content" to "# shared\n$END\n"))
        assertEquals("ok", seed.status, "seed shared note: ${seed.text}")
        commitOwner[seed.str("commit")!!] = clients.first().def.id
        successes.incrementAndGet()

        // The first round of shared edits is released only after every agent has READ the same revision,
        // so at least (writers - 1) of those writes must lose: the 409 path is exercised by construction.
        val arrived = AtomicInteger()
        val gate = CompletableDeferred<Unit>()

        suspend fun sharedEdit(c: McpAgent, j: Int, firstRound: Boolean) {
            val line = "- ${c.def.id} edit $j"
            var attempts = 0
            var gated = !firstRound
            while (true) {
                check(++attempts < 5000) { "${c.def.id} could not append '$line'" }
                val cur = c.call("read", mapOf("path" to SHARED))
                val rev = cur.str("revision") ?: error("read $SHARED: ${cur.text}")
                val content = cur.str("content")!!
                if (!gated) {
                    gated = true
                    if (arrived.incrementAndGet() == writers.size) gate.complete(Unit)
                    gate.await()
                }
                val r = if (j % 2 == 0) {
                    c.call("write", mapOf("path" to SHARED, "content" to content.replace(END, "$line\n$END"), "expectedRevision" to rev))
                } else {
                    c.call("edit", mapOf("path" to SHARED, "oldString" to END, "newString" to "$line\n$END", "expectedRevision" to rev))
                }
                when (r.status) {
                    "ok" -> { commitOwner[r.str("commit")!!] = c.def.id; successes.incrementAndGet(); return }
                    "conflict" -> {
                        conflicts.incrementAndGet()
                        val current = r.str("current"); val currentContent = r.str("currentContent")
                        if (current.isNullOrBlank() || currentContent.isNullOrBlank() || !currentContent.contains("# shared")) {
                            conflictsMissingFields.incrementAndGet()
                        }
                    }
                    else -> { failures["${c.def.id}/shared/$j"] = r.text; return }
                }
            }
        }

        val started = System.nanoTime()
        withTimeout(if (notesPerAgent > 100) 900_000 else 120_000) {
            clients.map { c ->
                async(Dispatchers.Default) {
                    val every = maxOf(1, notesPerAgent / maxOf(1, sharedEditsPerAgent))
                    var shared = 0
                    for (i in 0 until notesPerAgent) {
                        val path = "notes/${c.def.id}/n$i.md"
                        val r = c.call("write", mapOf("path" to path, "content" to noteBody(c.def.id, i)))
                        if (r.status == "ok") { commitOwner[r.str("commit")!!] = c.def.id; successes.incrementAndGet() }
                        else failures["${c.def.id}/note/$i"] = r.text
                        if (i % every == 0 && shared < sharedEditsPerAgent) sharedEdit(c, shared, firstRound = shared++ == 0)
                    }
                    while (shared < sharedEditsPerAgent) sharedEdit(c, shared, firstRound = shared++ == 0)
                }
            }.awaitAll()
        }
        val durationMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(failures.isEmpty(), "unexpected tool results: ${failures.entries.take(5)}")

        // drain to 0: the engine's write actor reports no queued or running task
        val drainStart = System.nanoTime()
        withTimeout(20_000) { while (fx.node.engine.queueDepth() != 0) delay(20) }
        val drainMs = (System.nanoTime() - drainStart) / 1_000_000
        assertEquals(0, fx.node.engine.queueDepth(), "write queue drained")

        // 3. conflicts surfaced, not swallowed
        assertTrue(conflicts.get() > 0, "the 409 path was not exercised (0 conflicts)")
        assertEquals(0, conflictsMissingFields.get(), "every conflict carries current + currentContent")

        // 2. zero loss
        for (c in clients) for (i in 0 until notesPerAgent) {
            val path = "notes/${c.def.id}/n$i.md"
            assertEquals(noteBody(c.def.id, i), fx.node.engine.read(path)?.text, "note $path lost or altered")
        }
        val sharedText = fx.node.engine.read(SHARED)!!.text
        val lines = sharedText.lines().filter { it.startsWith("- ") }
        val expected = writers.flatMap { w -> (0 until sharedEditsPerAgent).map { "- ${w.id} edit $it" } }
        assertEquals(expected.sorted(), lines.sorted(), "the shared note holds every agent's contribution exactly once")
        assertTrue(sharedText.startsWith("# shared\n") && sharedText.trimEnd().endsWith(END), "shared note structure intact")

        val fresh = fx.commits().filter { it.name !in baselineCommits }
        val expectedWrites = writers.size * (notesPerAgent + sharedEditsPerAgent) + 1
        assertEquals(expectedWrites, successes.get(), "client-side successful writes")
        assertEquals(successes.get(), fresh.size, "one commit per successful write (conflicts committed nothing)")
        assertEquals(commitOwner.keys, fresh.map { it.name }.toSet(), "every commit was returned to exactly one successful call")

        // 4. attribution: author is the person, Svod-Agent trailer is the agent that made the call
        val byId = fx.agents.associateBy { it.id }
        for (commit in fresh) {
            val agentId = commitOwner.getValue(commit.name)
            val person = byId.getValue(agentId).person
            assertEquals(person.name, commit.authorIdent.name, "author of ${commit.name}")
            assertEquals(person.email, commit.authorIdent.emailAddress, "author email of ${commit.name}")
            assertEquals(listOf(agentId), commit.getFooterLines("Svod-Agent"), "Svod-Agent trailer of ${commit.name}")
        }

        val (code, out) = fx.fsck()
        assertEquals(0, code, "git fsck: $out")

        Report(
            notesPerAgent, sharedEditsPerAgent, writers.size, durationMs, successes.get(), fresh.size,
            conflicts.get(), fx.rateLimited.get(), fx.node.engine.peakQueueDepth(), drainMs,
        )
    } finally {
        clients.forEach { it.close() }
    }
}

private fun noteBody(agent: String, i: Int) = "# $agent note $i\n\nunique-${agent}-$i\n"
