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
    fun `conflicts persist on the source and resolve clears them`() = runBlocking {
        val ext = Files.createTempDirectory("ext-")
        Files.writeString(ext.resolve("doc.md"), "# Doc\nexternal v1\n")

        engineOn().use { engine ->
            val store = ExternalSourceStore(engine.root)
            val sync = SourceSync(engine, store)
            val src = store.put(newSource(ext, into = "proj"))
            sync.sync(src)

            // local (agent) edit → conflict, persisted on the registration
            val rev = engine.read("proj/doc.md")!!.revision
            engine.write("proj/doc.md", "# Doc\nagent edit\n", rev, Author("agent", "a@x"))
            sync.sync(store.get(src.id)!!)
            assertEquals(listOf("proj/doc.md"), store.get(src.id)!!.conflicts, "conflict persisted on the source")

            // takeExternal → vault copy overwritten, conflict cleared, next sync clean
            val r = sync.resolve(store.get(src.id)!!, "proj/doc.md", ResolveStrategy.TAKE_EXTERNAL)
            assertTrue(r.error == null)
            assertEquals("# Doc\nexternal v1\n", engine.read("proj/doc.md")!!.text)
            assertTrue(store.get(src.id)!!.conflicts.isEmpty(), "conflict cleared")
            assertTrue(sync.sync(store.get(src.id)!!).conflicts.isEmpty())
        }
    }

    @Test
    fun `keepVault accepts the local edit and never lets the OLD external clobber it`() = runBlocking {
        val ext = Files.createTempDirectory("ext-")
        Files.writeString(ext.resolve("doc.md"), "# Doc\nexternal v1\n")

        engineOn().use { engine ->
            val store = ExternalSourceStore(engine.root)
            val sync = SourceSync(engine, store)
            val src = store.put(newSource(ext))
            sync.sync(src)

            val rev = engine.read("doc.md")!!.revision
            engine.write("doc.md", "# Doc\nkept local edit\n", rev, Author("agent", "a@x"))
            sync.sync(store.get(src.id)!!)

            val r = sync.resolve(store.get(src.id)!!, "doc.md", ResolveStrategy.KEEP_VAULT)
            assertTrue(r.error == null)
            assertTrue(store.get(src.id)!!.conflicts.isEmpty())

            // external UNCHANGED → the kept edit stays quiet (no conflict, no clobber)
            val quiet = sync.sync(store.get(src.id)!!)
            assertTrue(quiet.conflicts.isEmpty() && quiet.updated.isEmpty(), "kept divergence is quiet")
            assertEquals("# Doc\nkept local edit\n", engine.read("doc.md")!!.text)

            // external CHANGES later → surfaces as a conflict again (not a silent overwrite)
            Files.writeString(ext.resolve("doc.md"), "# Doc\nexternal v2\n")
            val again = sync.sync(store.get(src.id)!!)
            assertEquals(listOf("doc.md"), again.conflicts, "new external change re-surfaces")
            assertEquals("# Doc\nkept local edit\n", engine.read("doc.md")!!.text, "kept edit still intact")
        }
    }

    @Test
    fun `writeBack pushes a vault edit out to the external file`() = runBlocking {
        val ext = Files.createTempDirectory("ext-")
        Files.writeString(ext.resolve("doc.md"), "# Doc\nexternal v1\n")

        engineOn().use { engine ->
            val store = ExternalSourceStore(engine.root)
            val sync = SourceSync(engine, store)
            val src = store.put(newSource(ext, into = "proj").copy(writeBack = true))
            sync.sync(src)

            // vault (agent) edit → flows OUT to the external file, no conflict
            val rev = engine.read("proj/doc.md")!!.revision
            engine.write("proj/doc.md", "# Doc\nagent edit\n", rev, Author("agent", "a@x"))
            val r = sync.sync(store.get(src.id)!!)
            assertEquals(listOf("proj/doc.md"), r.pushed, "vault edit pushed")
            assertTrue(r.conflicts.isEmpty())
            assertEquals("# Doc\nagent edit\n", Files.readString(ext.resolve("doc.md")), "external file updated")

            // next sync is fully quiet
            val quiet = sync.sync(store.get(src.id)!!)
            assertTrue(quiet.pushed.isEmpty() && quiet.conflicts.isEmpty() && quiet.updated.isEmpty())
        }
    }

    @Test
    fun `writeBack never clobbers an external file that also changed — both-moved is a conflict`() = runBlocking {
        val ext = Files.createTempDirectory("ext-")
        Files.writeString(ext.resolve("doc.md"), "# Doc\nexternal v1\n")

        engineOn().use { engine ->
            val store = ExternalSourceStore(engine.root)
            val sync = SourceSync(engine, store)
            val src = store.put(newSource(ext).copy(writeBack = true))
            sync.sync(src)

            val rev = engine.read("doc.md")!!.revision
            engine.write("doc.md", "vault edit\n", rev, Author("agent", "a@x"))
            Files.writeString(ext.resolve("doc.md"), "external v2\n")   // both sides moved

            val r = sync.sync(store.get(src.id)!!)
            assertEquals(listOf("doc.md"), r.conflicts, "both-moved is a conflict")
            assertTrue(r.pushed.isEmpty())
            assertEquals("external v2\n", Files.readString(ext.resolve("doc.md")), "external file untouched")
            assertEquals("vault edit\n", engine.read("doc.md")!!.text, "vault copy untouched")
        }
    }

    @Test
    fun `writeBack does not materialize vault-created files externally`() = runBlocking {
        val ext = Files.createTempDirectory("ext-")
        Files.writeString(ext.resolve("doc.md"), "# Doc\n")

        engineOn().use { engine ->
            val store = ExternalSourceStore(engine.root)
            val sync = SourceSync(engine, store)
            val src = store.put(newSource(ext, into = "proj").copy(writeBack = true))
            sync.sync(src)

            engine.write("proj/new-note.md", "created in the vault\n", null, Author("agent", "a@x"))
            val r = sync.sync(store.get(src.id)!!)
            assertTrue(r.pushed.isEmpty())
            assertTrue(!Files.exists(ext.resolve("new-note.md")), "vault-created file stays vault-only")
        }
    }

    @Test
    fun `legacy single-blob manifests load as a clean two-sided baseline`() = runBlocking {
        val ext = Files.createTempDirectory("ext-")
        Files.writeString(ext.resolve("a.md"), "# A\nv1\n")

        engineOn().use { engine ->
            val store = ExternalSourceStore(engine.root)
            val sync = SourceSync(engine, store)
            val src = store.put(newSource(ext))
            sync.sync(src)

            // Rewrite the manifest in the LEGACY format (path → blob id string).
            val mf = engine.root.resolve(".svod/source-manifests/${src.id}.json")
            val blob = store.loadManifest(src.id)["a.md"]!!.ext
            Files.writeString(mf, "{\"a.md\": \"$blob\"}")

            assertEquals(SyncedState(blob, blob), store.loadManifest(src.id)["a.md"], "legacy value → both sides")
            // and behavior is unchanged: an external edit still flows in
            Files.writeString(ext.resolve("a.md"), "# A\nv2\n")
            assertEquals(listOf("a.md"), sync.sync(store.get(src.id)!!).updated)
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
