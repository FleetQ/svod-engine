package dev.svod.engine.mcp

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.WriteOutcome
import dev.svod.engine.events.EventBus
import dev.svod.engine.index.IndexService
import dev.svod.engine.index.MarkdownChunker
import dev.svod.engine.index.NoneEmbedder
import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.memory.MemoryReview
import dev.svod.engine.security.PersonalDataScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `personalDataScanning`: an agent's write/edit/remember that adds personal data is stored
 * `provisional` and waits in the Memory review queue; the tool result names rule and line only.
 */
class PersonalDataReviewTest {

    private class Fixture(scanning: Boolean = true) : AutoCloseable {
        val root: Path = Files.createTempDirectory("svod-personal-data-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine: SvodEngine = SvodEngine.open(root, scope)
        val index: IndexService = IndexService(root, root.resolve(".svod").resolve("index"), NoneEmbedder).start()
        val auditPath: Path = root.resolve(".svod").resolve("audit").resolve("audit.log")
        val registry = AgentRegistry(listOf(WRITE_AGENT))
        val tools = SvodTools(
            engine, index, AuditLog(auditPath), RateLimiter.default(), EventBus(),
            requireReview = false, personalDataScanner = PersonalDataScanner(scanning),
        )

        init { engine.onCommit { index.onCommit(it) } }

        val agent: AgentIdentity get() = registry.byAgentId("scribe")!!

        fun text(path: String): String = runBlocking { engine.read(path)!!.text }
        fun status(path: String): String? = MarkdownChunker.parse(text(path)).status
        fun queued(): Set<String> = runBlocking {
            index.waitIdle()
            MemoryReview.list(engine, index, MemoryReview.MAX_LIMIT).first.map { it.path }.toSet()
        }
        fun audit(): String = if (Files.exists(auditPath)) Files.readString(auditPath) else ""

        override fun close() { index.close(); engine.close() }
    }

    private val email = "jane.doe@example.org"
    private val iban = "GB82 WEST 1234 5698 7654 32"
    private val card = "4111 1111 1111 1111"
    private val phone = "+44 20 7946 0958"

    private fun personalData(r: ToolResult): List<String> =
        r.data["personalData"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun assertNoValues(where: String, vararg values: String) {
        values.forEach { v -> assertFalse(v in where, "a matched value leaked: $where") }
    }

    @Test
    fun `a write without frontmatter that adds an email is provisional and queued`() {
        Fixture().use { fx ->
            val r = runBlocking { fx.tools.write(fx.agent, "notes/contact.md", "# Contact\nMail $email.\n", null) }
            assertEquals("ok", r.status)
            assertEquals(listOf("email (line 2)"), personalData(r))
            assertEquals("provisional", r.data["memoryStatus"]?.jsonPrimitive?.content)
            assertNoValues(r.data.toString(), email)
            assertEquals("provisional", fx.status("notes/contact.md"))
            assertTrue(fx.text("notes/contact.md").endsWith("# Contact\nMail $email.\n"), "body kept as written")
            assertEquals(setOf("notes/contact.md"), fx.queued())
            assertTrue("email" in fx.audit())
            assertNoValues(fx.audit(), email)
        }
    }

    @Test
    fun `a write with frontmatter keeps its keys and gains status provisional`() {
        Fixture().use { fx ->
            val content = "---\ntitle: Billing\ntags: [finance]\n---\nPay to $iban\n"
            val r = runBlocking { fx.tools.write(fx.agent, "notes/billing.md", content, null) }
            assertEquals(listOf("iban (line 5)"), personalData(r))
            val doc = MarkdownChunker.parse(fx.text("notes/billing.md"))
            assertEquals("provisional", doc.status)
            assertEquals("Billing", doc.frontmatter["title"])
            assertTrue("Pay to $iban" in doc.body)
        }
    }

    @Test
    fun `a clean write is unchanged and has no personalData`() {
        Fixture().use { fx ->
            val r = runBlocking { fx.tools.write(fx.agent, "notes/plain.md", "Version 1.35.0 shipped.\n", null) }
            assertNull(r.data["personalData"])
            assertEquals("Version 1.35.0 shipped.\n", fx.text("notes/plain.md"))
            assertTrue(fx.queued().isEmpty())
        }
    }

    @Test
    fun `scanning off - the same write is stored as written`() {
        Fixture(scanning = false).use { fx ->
            val r = runBlocking { fx.tools.write(fx.agent, "notes/contact.md", "Mail $email\n", null) }
            assertNull(r.data["personalData"])
            assertEquals("Mail $email\n", fx.text("notes/contact.md"))
            assertFalse(SvodConfig().personalDataScanning, "the setting is opt-in")
        }
    }

    @Test
    fun `an edit flags only personal data it adds`() {
        Fixture().use { fx ->
            // A person's own write is not scanned; the note already holds an email.
            val w = runBlocking { fx.engine.write("notes/team.md", "Lead: $email\nStatus: draft\n", null, Author("Person", "p@x")) }
            assertTrue(w is WriteOutcome.Success)
            val same = runBlocking { fx.tools.edit(fx.agent, "notes/team.md", "draft", "final", false, null) }
            assertEquals("ok", same.status)
            assertNull(same.data["personalData"], "the email was already there")
            assertNull(fx.status("notes/team.md"))

            val added = runBlocking { fx.tools.edit(fx.agent, "notes/team.md", "final", "final, call $phone", false, null) }
            assertEquals(listOf("phone (line 2)"), personalData(added))
            assertNoValues(added.data.toString(), phone, email)
            assertEquals("provisional", fx.status("notes/team.md"))
            assertEquals(setOf("notes/team.md"), fx.queued())
        }
    }

    @Test
    fun `remember with a card number is provisional whatever the status asked`() {
        Fixture().use { fx ->
            val r = runBlocking {
                fx.tools.remember(fx.agent, "Company card is $card", "preference", "billing", null, null, "active", null, null)
            }
            assertEquals("provisional", r.data["memoryStatus"]?.jsonPrimitive?.content)
            assertEquals(listOf("payment-card (line 1)"), personalData(r))
            assertNoValues(r.data.toString(), card, card.replace(" ", ""))
            val path = r.data["path"]!!.jsonPrimitive.content
            assertEquals("provisional", fx.status(path))
            assertEquals(setOf(path), fx.queued())
        }
    }

    @Test
    fun `approving a flagged note does not flag it again`() {
        Fixture().use { fx ->
            runBlocking { fx.tools.write(fx.agent, "notes/contact.md", "Mail $email\n", null) }
            val outcome = runBlocking {
                MemoryReview.apply(fx.engine, "notes/contact.md", MemoryReview.Action.APPROVE, null, Author("Person", "p@x"))
            }
            assertTrue(outcome is MemoryReview.ReviewOutcome.Written)
            assertEquals("active", fx.status("notes/contact.md"))
            assertTrue(fx.queued().isEmpty())
        }
    }
}
