package dev.svod.engine.memory

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.core.VaultFixture
import dev.svod.engine.core.WriteOutcome
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `svod-engine export-rules`: active policy/preference/procedure notes into a managed section of AGENTS.md. */
class RulesExportTest {

    private lateinit var fx: VaultFixture
    private lateinit var engine: SvodEngine
    private lateinit var outDir: Path

    private val alice = Author("alice", "alice@example.com")
    private val now = 1_800_000_000L

    @BeforeTest
    fun setUp() {
        fx = VaultFixture.create()
        engine = fx.open()
        outDir = Files.createTempDirectory("svod-rules-out-")
    }

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun write(path: String, content: String) {
        val current = engine.read(path)
        val out = engine.write(path, content, current?.revision, alice)
        assertTrue(out is WriteOutcome.Success, "write $path: $out")
    }

    private fun note(type: String, title: String?, body: String, extra: String = "") =
        "---\ntype: $type\n" + (title?.let { "title: $it\n" } ?: "") + extra + "---\n" + body + "\n"

    private suspend fun renderNow() = RulesExport.render(RulesExport.collect(engine, now))

    @Test
    fun `render keeps active rules and drops inactive ones`() = runBlocking {
        write("rules/p.md", note("policy", "Active policy", "Always test."))
        write("rules/pref.md", note("preference", "Tabs pref", "Use tabs."))
        write("rules/proc.md", note("procedure", "Release proc", "Tag then push."))
        write("rules/revoked.md", note("policy", "Revoked one", "x", "status: revoked\n"))
        write("rules/prov.md", note("policy", "Provisional one", "x", "status: provisional\n"))
        write("rules/sup.md", note("policy", "Superseded one", "x", "superseded_by: rules/p.md\n"))
        write("rules/exp.md", note("policy", "Expired one", "x", "expires_at: $now\n"))
        write("rules/future.md", note("policy", "Future expiry", "still here", "expires_at: ${now + 100}\n"))
        write("rules/priv.md", note("policy", "Private one", "x", "private: true\n"))
        write("messy/m.md", note("policy", "Messy one", "x"))
        write("rules/fact.md", note("fact", "A fact", "x"))

        val out = renderNow()
        for (t in listOf("Active policy", "Tabs pref", "Release proc", "Future expiry")) assertTrue("### $t" in out, out)
        for (t in listOf("Revoked one", "Provisional one", "Superseded one", "Expired one", "Private one", "Messy one", "A fact")) {
            assertFalse(t in out, "$t leaked: $out")
        }
        assertTrue("Always test." in out, out)
    }

    @Test
    fun `dot folders and private spans are left out`() = runBlocking {
        write("rules/p.md", note("policy", "Visible", "public part <private>secret bit</private> tail"))
        val dot = fx.root.resolve(".hidden/x.md")
        Files.createDirectories(dot.parent)
        Files.writeString(dot, note("policy", "Dot one", "x"))
        val out = renderNow()
        assertTrue("### Visible" in out, out)
        assertFalse("Dot one" in out, out)
        assertFalse("secret bit" in out, out)
        assertTrue("public part" in out && "tail" in out, out)
    }

    @Test
    fun `each rule cites path and last commit with a title fallback`() = runBlocking {
        write("rules/a.md", note("policy", "From frontmatter", "a"))
        write("rules/b.md", note("policy", null, "# From heading\nb"))
        write("rules/c-name.md", note("policy", null, "no heading here"))
        val out = renderNow()
        for (p in listOf("rules/a.md", "rules/b.md", "rules/c-name.md")) {
            val commit = assertNotNull(engine.history(p, 1).firstOrNull()).commit
            assertTrue("_Source: `$p` @ ${commit.take(12)}_" in out, out)
        }
        assertTrue("### From frontmatter" in out, out)
        assertTrue("### From heading" in out, out)
        assertTrue("### c-name" in out, out)
        assertFalse("---" in out, out)
    }

    @Test
    fun `render is deterministic and ordered by type then path`() = runBlocking {
        write("z/proc.md", note("procedure", "Proc", "p"))
        write("b/pol.md", note("policy", "Pol B", "b"))
        write("a/pref.md", note("preference", "Pref", "r"))
        write("a/pol.md", note("policy", "Pol A", "a"))
        val first = renderNow()
        assertEquals(first, renderNow())
        val order = listOf("## Policies", "### Pol A", "### Pol B", "## Preferences", "### Pref", "## Procedures", "### Proc")
            .map { first.indexOf(it) }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), first)
        assertTrue(first.startsWith(RulesExport.BEGIN + "\n"), first)
        assertTrue(first.endsWith(RulesExport.END), first)
        assertFalse(Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(first), first)
        assertFalse("generated at" in first.lowercase(), first)
    }

    @Test
    fun `splice handles empty, markerless and marked files`() {
        val section = "${RulesExport.BEGIN}\nnew\n${RulesExport.END}"
        assertEquals(section + "\n", RulesExport.splice(null, section))
        assertEquals(section + "\n", RulesExport.splice("", section))
        val plain = "# My agents\n\nkeep me  \n\n\n"
        val appended = RulesExport.splice(plain, section)
        assertEquals("# My agents\n\nkeep me  \n\n$section\n", appended)
        assertTrue(appended.startsWith("# My agents\n\nkeep me  \n"))
        val marked = "head\r\n${RulesExport.BEGIN}\nold stuff\n${RulesExport.END}\r\ntail  \n"
        assertEquals("head\r\n$section\r\ntail  \n", RulesExport.splice(marked, section))
        assertEquals(marked.replace("old stuff", "new"), RulesExport.splice(marked, section))
    }

    @Test
    fun `malformed markers are refused`() {
        val section = "${RulesExport.BEGIN}\nnew\n${RulesExport.END}"
        assertFailsWith<IllegalArgumentException> { RulesExport.splice("a\n${RulesExport.BEGIN}\nb\n", section) }
        assertFailsWith<IllegalArgumentException> {
            RulesExport.splice("${RulesExport.BEGIN}\n${RulesExport.BEGIN}\n${RulesExport.END}\n", section)
        }
        assertFailsWith<IllegalArgumentException> { RulesExport.splice("${RulesExport.END}\n${RulesExport.BEGIN}\n", section) }
    }

    @Test
    fun `export with malformed markers leaves the file unchanged`() = runBlocking {
        write("rules/p.md", note("policy", "P", "x"))
        val target = outDir.resolve("AGENTS.md")
        val bad = "intro\n${RulesExport.BEGIN}\nno end\n"
        Files.writeString(target, bad)
        assertFailsWith<IllegalArgumentException> { RulesExport.export(engine, fx.root, target, now) }
        assertEquals(bad, Files.readString(target))
    }

    @Test
    fun `target inside the vault is refused`() = runBlocking {
        write("rules/p.md", note("policy", "P", "x"))
        assertFailsWith<IllegalArgumentException> { RulesExport.export(engine, fx.root, fx.root.resolve("AGENTS.md"), now) }
        assertFailsWith<IllegalArgumentException> {
            RulesExport.export(engine, fx.root, fx.root.resolve("sub/../docs/CLAUDE.md"), now)
        }
        assertFalse(Files.exists(fx.root.resolve("AGENTS.md")))
    }

    @Test
    fun `export is idempotent and follows revocation`() = runBlocking {
        write("rules/p.md", note("policy", "Keep me", "k"))
        write("rules/q.md", note("policy", "Drop me", "d"))
        val target = outDir.resolve("nested/CLAUDE.md")
        val r1 = RulesExport.export(engine, fx.root, target, now)
        assertTrue(r1.changed)
        assertEquals(2, r1.rules)
        val bytes = Files.readAllBytes(target)
        val old = FileTime.fromMillis(1_000_000_000_000L)
        Files.setLastModifiedTime(target, old)

        val r2 = RulesExport.export(engine, fx.root, target, now)
        assertFalse(r2.changed)
        assertTrue(bytes.contentEquals(Files.readAllBytes(target)))
        assertEquals(old, Files.getLastModifiedTime(target))

        write("rules/q.md", note("policy", "Drop me", "d", "status: revoked\n"))
        val r3 = RulesExport.export(engine, fx.root, target, now)
        assertTrue(r3.changed)
        assertEquals(1, r3.rules)
        val text = Files.readString(target)
        assertTrue("Keep me" in text, text)
        assertFalse("Drop me" in text, text)
    }

    @Test
    fun `surrounding text is kept on export`() = runBlocking {
        write("rules/p.md", note("policy", "P", "x"))
        val target = outDir.resolve("AGENTS.md")
        Files.writeString(target, "# Agents\n\nhand-written\n")
        RulesExport.export(engine, fx.root, target, now)
        val text = Files.readString(target)
        assertTrue(text.startsWith("# Agents\n\nhand-written\n\n${RulesExport.BEGIN}\n"), text)
        Files.writeString(target, text + "\nfooter\n")
        write("rules/p2.md", note("policy", "P2", "y"))
        RulesExport.export(engine, fx.root, target, now)
        val again = Files.readString(target)
        assertTrue(again.startsWith("# Agents\n\nhand-written\n\n${RulesExport.BEGIN}\n"), again)
        assertTrue(again.endsWith("${RulesExport.END}\n\nfooter\n"), again)
        assertTrue("### P2" in again, again)
    }

    @Test
    fun `no rules still writes the section`() = runBlocking {
        write("notes/x.md", note("fact", "Just a fact", "x"))
        val out = renderNow()
        assertTrue(out.startsWith(RulesExport.BEGIN), out)
        assertTrue(out.endsWith(RulesExport.END), out)
        assertTrue("_No active rules._" in out, out)
    }
}
