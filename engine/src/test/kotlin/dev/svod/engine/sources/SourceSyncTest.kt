package dev.svod.engine.sources

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceSyncTest {

    private fun engineOn(): SvodEngine {
        val vault = Files.createTempDirectory("svod-vault-")
        return SvodEngine.open(vault, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    private fun newSource(path: Path, into: String = "") =
        ExternalSource(id = ExternalSource.idFor(path.toString()), path = path.toString(), into = into)

    @Test
    fun `external-wins-unless-locally-edited across the full lifecycle`() = runBlocking {
        val ext = Files.createTempDirectory("ext-project-")
        Files.writeString(ext.resolve("a.md"), "# A\nv1\n")
        Files.writeString(ext.resolve("b.md"), "# B\nv1\n")

        engineOn().use { engine ->
            val store = ExternalSourceStore(engine.root)
            val sync = SourceSync(engine, store)
            val src = store.put(newSource(ext, into = "projects/demo"))

            // 1. first sync — both created under the prefix
            val r1 = sync.sync(src)
            assertEquals(listOf("projects/demo/a.md", "projects/demo/b.md"), r1.created)
            assertTrue(r1.error == null && r1.updated.isEmpty() && r1.conflicts.isEmpty())
            assertEquals("# A\nv1\n", engine.read("projects/demo/a.md")!!.text)

            // 2. re-sync, nothing changed — both unchanged, no new commit churn
            val r2 = sync.sync(store.get(src.id)!!)
            assertEquals(listOf("projects/demo/a.md", "projects/demo/b.md"), r2.unchanged)
            assertTrue(r2.created.isEmpty() && r2.updated.isEmpty())

            // 3. external edit to a.md flows in (vault copy untouched since last sync)
            Files.writeString(ext.resolve("a.md"), "# A\nv2\n")
            val r3 = sync.sync(store.get(src.id)!!)
            assertEquals(listOf("projects/demo/a.md"), r3.updated)
            assertEquals(listOf("projects/demo/b.md"), r3.unchanged)
            assertEquals("# A\nv2\n", engine.read("projects/demo/a.md")!!.text, "external edit materialized")

            // 4. local edit to the vault copy is PROTECTED — a later sync reports a conflict, no clobber
            val rev = engine.read("projects/demo/a.md")!!.revision
            engine.write("projects/demo/a.md", "# A\nlocal hand edit\n", rev, Author("me", "me@x"))
            val r4 = sync.sync(store.get(src.id)!!)
            assertEquals(listOf("projects/demo/a.md"), r4.conflicts, "locally-edited copy is a conflict")
            assertEquals("# A\nlocal hand edit\n", engine.read("projects/demo/a.md")!!.text, "local edit preserved")

            // 5. external file removed → orphaned (reported, left in the vault — no deletion in v1)
            Files.delete(ext.resolve("b.md"))
            val r5 = sync.sync(store.get(src.id)!!)
            assertTrue("projects/demo/b.md" in r5.orphaned)
            assertTrue(engine.read("projects/demo/b.md") != null, "orphan not deleted from the vault")
        }
    }

    @Test
    fun `prune deletes a vanished file, but never a locally-edited one`() = runBlocking {
        val ext = Files.createTempDirectory("ext-")
        Files.writeString(ext.resolve("keep.md"), "# Keep\n")
        Files.writeString(ext.resolve("gone.md"), "# Gone\n")
        Files.writeString(ext.resolve("edited.md"), "# Edited\n")

        engineOn().use { engine ->
            val store = ExternalSourceStore(engine.root)
            val sync = SourceSync(engine, store)
            val src = store.put(newSource(ext).copy(prune = true))
            sync.sync(src)

            // locally edit one vault copy, then remove BOTH gone.md and edited.md from the source
            val rev = engine.read("edited.md")!!.revision
            engine.write("edited.md", "# Edited\nlocal change\n", rev, Author("me", "me@x"))
            Files.delete(ext.resolve("gone.md"))
            Files.delete(ext.resolve("edited.md"))

            val r = sync.sync(store.get(src.id)!!)
            assertEquals(listOf("gone.md"), r.deleted, "untouched vanished file pruned")
            assertEquals(listOf("edited.md"), r.orphaned, "locally-edited vanished file kept (not deleted)")
            assertTrue(engine.read("gone.md") == null, "gone.md removed from the vault")
            assertEquals("# Edited\nlocal change\n", engine.read("edited.md")!!.text, "local edit preserved")
        }
    }

    @Test
    fun `a single-file source materializes under the into prefix`() = runBlocking {
        val dir = Files.createTempDirectory("ext-")
        val file = dir.resolve("spec.md")
        Files.writeString(file, "# Spec\n")

        engineOn().use { engine ->
            val store = ExternalSourceStore(engine.root)
            val sync = SourceSync(engine, store)
            val src = store.put(newSource(file, into = "imported"))

            val r = sync.sync(src)
            assertEquals(listOf("imported/spec.md"), r.created)
            assertEquals("# Spec\n", engine.read("imported/spec.md")!!.text)
        }
    }

    @Test
    fun `registration id is deterministic for the same path`() {
        val a = ExternalSource.idFor("/home/u/projects/alpha")
        val b = ExternalSource.idFor("/home/u/projects/alpha")
        val c = ExternalSource.idFor("/home/u/projects/beta")
        assertEquals(a, b, "same path ⇒ same id (idempotent re-register)")
        assertTrue(a != c, "different paths ⇒ different ids")
        assertTrue(a.startsWith("alpha-"))
    }
}
