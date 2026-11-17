package dev.svod.engine.sync

import dev.svod.engine.core.Author
import dev.svod.engine.core.SvodEngine
import dev.svod.engine.events.EventBus
import dev.svod.engine.lifecycle.BackupConfig
import dev.svod.engine.lifecycle.BackupConfigStore
import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.security.SecretScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val T = Author("tester", "t@svod.test")
private const val V = "v"
private const val BRANCH = "main"

/**
 * Sync through a plain branch (`syncBranch`): the remote is an ordinary git repository that a person also
 * works in with plain git. The engine fetches/merges/pushes `refs/heads/<branch>` non-force and never
 * mirrors to `main`.
 */
private class BranchRig(val syncBranch: String? = BRANCH) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val bare: Path = Files.createTempDirectory("svod-bare-").also {
        Git.init().setBare(true).setDirectory(it.toFile()).call().close()
    }
    val remote = bare.toString()
    val dir: Path = Files.createTempDirectory("svod-vault-")
    val engine = SvodEngine.open(dir, scope, SecretScanner.OFF, Author("machineA", "a@svod.local"))
    val conflicts = ConflictStore()
    val sync = SyncEngine(engine, SyncGit(dir), conflicts, EventBus(), V, "machineA")

    suspend fun cycle(): SyncEngine.Result = sync.sync(remote, syncBranch)

    fun remoteRef(name: String): String? = Git.open(bare.toFile()).use { it.repository.resolve(name)?.name }
    fun remoteRefNames(): List<String> = Git.open(bare.toFile()).use { g -> g.repository.refDatabase.refs.map { it.name } }

    fun isRemoteAncestor(commit: String, of: String): Boolean = Git.open(bare.toFile()).use { g ->
        RevWalk(g.repository).use { rw ->
            rw.isMergedInto(rw.parseCommit(g.repository.resolve(commit)), rw.parseCommit(g.repository.resolve(of)))
        }
    }

    /** A person's plain-git clone of the branch; [block] edits it, then it commits and pushes WITHOUT force. */
    fun personCommit(file: String, content: String, message: String = "person edits $file", branch: String = BRANCH): String {
        val work = Files.createTempDirectory("svod-person-")
        Git.cloneRepository().setURI(remote).setDirectory(work.toFile())
            .setBranchesToClone(listOf("refs/heads/$branch")).setBranch("refs/heads/$branch").call().use { g ->
                val f = work.resolve(file)
                Files.createDirectories(f.parent)
                Files.writeString(f, content)
                g.add().addFilepattern(file).call()
                val c = g.commit().setMessage(message).setAuthor("Pat Person", "pat@example.com").setCommitter("Pat Person", "pat@example.com").call()
                g.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch")).call()
                return c.name
            }
    }

    /** The branch as a plain clone sees it: path → content, and the commit list (newest first) with author names. */
    fun cloneLog(branch: String = BRANCH): Pair<Map<String, String>, List<Pair<String, String>>> {
        val work = Files.createTempDirectory("svod-look-")
        Git.cloneRepository().setURI(remote).setDirectory(work.toFile())
            .setBranchesToClone(listOf("refs/heads/$branch")).setBranch("refs/heads/$branch").call().use { g ->
                val files = Files.walk(work).use { s ->
                    s.filter { Files.isRegularFile(it) && !work.relativize(it).startsWith(".git") }
                        .toList().associate { work.relativize(it).toString() to Files.readString(it) }
                }
                val log = g.log().call().map { it.fullMessage.trim() to it.authorIdent.name }
                return files to log
            }
    }

    override fun close() {
        engine.close()
    }
}

class SyncBranchTest {

    @Test
    fun `an empty remote gets the branch created by the first cycle and no svod sync ref`() = runBlocking {
        BranchRig().use { r ->
            assertNull(r.remoteRef("refs/heads/$BRANCH"))
            val res = r.cycle()

            assertEquals(SyncEngine.Status.inSync, res.status)
            assertEquals(r.engine.head(), r.remoteRef("refs/heads/$BRANCH"), "first push creates refs/heads/$BRANCH")
            assertTrue(r.remoteRefNames().none { it.startsWith("refs/svod/") }, "no refs/svod/*: ${r.remoteRefNames()}")
            assertEquals(listOf("refs/heads/$BRANCH"), r.remoteRefNames(), "only the plain branch exists, no mirror")
            assertEquals(SyncEngine.Status.inSync, r.cycle().status, "a second cycle is idle and stays clean")
        }
    }

    @Test
    fun `a commit a person pushes with plain git is fast-forwarded in and readable`() = runBlocking {
        BranchRig().use { r ->
            r.cycle()
            r.personCommit("notes/from-pat.md", "---\ntitle: Pat\n---\nhello from plain git\n")

            val res = r.cycle()

            assertEquals(SyncEngine.Status.inSync, res.status)
            assertTrue("hello from plain git" in r.engine.read("notes/from-pat.md")!!.text, "readable through the engine")
            assertEquals(r.remoteRef("refs/heads/$BRANCH"), r.engine.head(), "fast-forward, no merge commit")
        }
    }

    @Test
    fun `an engine write is pushed as a normal commit with its author`() = runBlocking {
        BranchRig().use { r ->
            r.cycle()
            r.engine.write("notes/from-engine.md", "---\ntitle: E\n---\nbody\n", null, T)

            assertEquals(SyncEngine.Status.inSync, r.cycle().status)

            val (files, log) = r.cloneLog()
            assertTrue("notes/from-engine.md" in files, "the file is in the branch: ${files.keys}")
            assertTrue(log.any { (_, author) -> author == "tester" }, "the engine author is on a commit: $log")
        }
    }

    @Test
    fun `changes to different files on both sides merge cleanly and the merge is pushed`() = runBlocking {
        BranchRig().use { r ->
            r.cycle()
            r.engine.write("a.md", "# a\nfrom engine\n", null, T)
            r.personCommit("b.md", "# b\nfrom person\n")

            val res = r.cycle()

            assertEquals(SyncEngine.Status.inSync, res.status)
            assertTrue(r.conflicts.isEmpty())
            assertNotNull(r.engine.read("a.md")); assertNotNull(r.engine.read("b.md"))
            assertEquals(r.engine.head(), r.remoteRef("refs/heads/$BRANCH"), "the merge commit is on the branch")
            val (files, _) = r.cloneLog()
            assertTrue("a.md" in files && "b.md" in files, "both files present for a plain clone: ${files.keys}")
        }
    }

    @Test
    fun `both sides changing the same file raises a conflict and pushes nothing over the remote change`() = runBlocking {
        BranchRig().use { r ->
            r.engine.write("shared.md", "---\ntitle: base\n---\nline\n", null, T)
            r.cycle()
            val rev = r.engine.read("shared.md")!!.revision
            r.engine.write("shared.md", "---\ntitle: engine\n---\nline\n", rev, T)
            val personCommit = r.personCommit("shared.md", "---\ntitle: person\n---\nline\n")

            val res = r.cycle()

            assertEquals(SyncEngine.Status.conflicts, res.status)
            assertEquals(personCommit, r.remoteRef("refs/heads/$BRANCH"), "the remote branch still points at the person's commit")
            assertTrue(r.conflicts.all().any { it.path == "shared.md" })
            assertTrue("engine" in r.engine.read("shared.md")!!.text, "the local file is untouched")
        }
    }

    @Test
    fun `keepMine settles a conflict by keeping the engine version and the person's commit stays an ancestor`() = runBlocking {
        BranchRig().use { r ->
            r.engine.write("shared.md", "# s\nbase\n", null, T)
            r.cycle()
            val rev = r.engine.read("shared.md")!!.revision
            r.engine.write("shared.md", "# s\nengine\n", rev, T)
            val personCommit = r.personCommit("shared.md", "# s\nperson\n")
            assertEquals(SyncEngine.Status.conflicts, r.cycle().status)

            r.conflicts.resolve("shared.md", ConflictStore.Choice.ours)
            assertEquals(SyncEngine.Status.inSync, r.cycle().status)

            assertTrue("engine" in r.engine.read("shared.md")!!.text)
            assertTrue(r.isRemoteAncestor(personCommit, "refs/heads/$BRANCH"), "the person's commit is still in the branch history")
            assertEquals(r.engine.head(), r.remoteRef("refs/heads/$BRANCH"))
        }
    }

    @Test
    fun `acceptIncoming settles a conflict by taking the person's version`() = runBlocking {
        BranchRig().use { r ->
            r.engine.write("shared.md", "# s\nbase\n", null, T)
            r.cycle()
            val rev = r.engine.read("shared.md")!!.revision
            r.engine.write("shared.md", "# s\nengine\n", rev, T)
            val personCommit = r.personCommit("shared.md", "# s\nperson\n")
            assertEquals(SyncEngine.Status.conflicts, r.cycle().status)

            r.conflicts.resolve("shared.md", ConflictStore.Choice.incoming)
            assertEquals(SyncEngine.Status.inSync, r.cycle().status)

            assertTrue("person" in r.engine.read("shared.md")!!.text)
            assertTrue(r.isRemoteAncestor(personCommit, "refs/heads/$BRANCH"))
        }
    }

    @Test
    fun `a force-push never happens - the person's commit stays an ancestor of the remote branch after every cycle`() = runBlocking {
        BranchRig().use { r ->
            r.cycle()
            val first = r.personCommit("p1.md", "# p1\n")
            r.engine.write("e1.md", "# e1\n", null, T)           // diverged: person commit + engine commit
            assertEquals(SyncEngine.Status.inSync, r.cycle().status)
            assertTrue(r.isRemoteAncestor(first, "refs/heads/$BRANCH"), "after the merge cycle")

            assertEquals(SyncEngine.Status.inSync, r.cycle().status)          // idle cycle: this is where the old mirror force-pushed
            assertTrue(r.isRemoteAncestor(first, "refs/heads/$BRANCH"), "after an idle cycle")

            val second = r.personCommit("p2.md", "# p2\n")
            assertEquals(SyncEngine.Status.inSync, r.cycle().status)          // remote ahead: fast-forward
            assertTrue(r.isRemoteAncestor(first, "refs/heads/$BRANCH") && r.isRemoteAncestor(second, "refs/heads/$BRANCH"), "after a fast-forward")

            r.engine.write("e2.md", "# e2\n", null, T)                        // local ahead: plain push
            assertEquals(SyncEngine.Status.inSync, r.cycle().status)
            assertTrue(r.isRemoteAncestor(first, "refs/heads/$BRANCH") && r.isRemoteAncestor(second, "refs/heads/$BRANCH"), "after a local push")
            assertEquals(listOf("refs/heads/$BRANCH"), r.remoteRefNames(), "no mirror and no svod ref")
        }
    }

    @Test
    fun `unrelated histories merge to the union of the files and a same-path difference is a conflict`() = runBlocking {
        BranchRig().use { r ->
            // The person's repository already has its own history on main, with no common ancestor with the vault.
            val person = Files.createTempDirectory("svod-existing-")
            Git.init().setDirectory(person.toFile()).call().use { g ->
                Files.writeString(person.resolve("readme.md"), "# their readme\n")
                Files.writeString(person.resolve("same.md"), "# same\nfrom the repository\n")
                g.add().addFilepattern(".").call()
                g.commit().setMessage("their first commit").setAuthor("Pat Person", "pat@example.com").setCommitter("Pat Person", "pat@example.com").call()
                g.push().setRemote(r.remote).setRefSpecs(RefSpec("HEAD:refs/heads/$BRANCH")).call()
            }
            val theirs = r.remoteRef("refs/heads/$BRANCH")!!
            r.engine.write("vault-only.md", "# vault only\n", null, T)
            r.engine.write("same.md", "# same\nfrom the vault\n", null, T)

            val res = r.cycle()

            assertEquals(SyncEngine.Status.conflicts, res.status)
            assertEquals(listOf("same.md"), r.conflicts.all().map { it.path }, "only the differing path conflicts")
            assertEquals(theirs, r.remoteRef("refs/heads/$BRANCH"), "nothing pushed over the repository")

            r.conflicts.resolve("same.md", ConflictStore.Choice.ours)
            assertEquals(SyncEngine.Status.inSync, r.cycle().status)
            assertNotNull(r.engine.read("readme.md"), "the repository's file arrived in the vault")
            assertNotNull(r.engine.read("vault-only.md"))
            assertTrue(r.isRemoteAncestor(theirs, "refs/heads/$BRANCH"), "their first commit is still in history")
            val (files, _) = r.cloneLog()
            assertTrue("readme.md" in files && "vault-only.md" in files, "union visible to plain git: ${files.keys}")
        }
    }

    @Test
    fun `a branch name with a slash works`(): Unit = runBlocking {
        BranchRig(syncBranch = "team/notes").use { r ->
            assertEquals(SyncEngine.Status.inSync, r.cycle().status)
            assertEquals(r.engine.head(), r.remoteRef("refs/heads/team/notes"))
            r.personCommit("x.md", "# x\n", branch = "team/notes")
            assertEquals(SyncEngine.Status.inSync, r.cycle().status)
            assertNotNull(r.engine.read("x.md"))
        }
    }

    @Test
    fun `the default mode is unchanged - svod sync ref plus the main mirror`() = runBlocking {
        BranchRig(syncBranch = null).use { r ->
            r.engine.write("n.md", "# n\n", null, T)
            assertEquals(SyncEngine.Status.inSync, r.cycle().status)

            assertEquals(r.engine.head(), r.remoteRef("refs/svod/sync/$V"))
            assertEquals(r.engine.head(), r.remoteRef("refs/heads/main"), "the cosmetic mirror still runs by default")
        }
    }

    @Test
    fun `an engine that switches a vault from the default ref to a branch keeps working`() = runBlocking {
        BranchRig(syncBranch = null).use { r ->
            r.cycle()
            assertNotNull(r.remoteRef("refs/svod/sync/$V"))
            val res = r.sync.sync(r.remote, BRANCH)
            assertEquals(SyncEngine.Status.inSync, res.status)
            assertEquals(r.engine.head(), r.remoteRef("refs/heads/$BRANCH"))
        }
    }

    @Test
    fun `clone with a sync branch lands the branch on the local one`() = runBlocking {
        BranchRig().use { r ->
            r.engine.write("n.md", "# n\n", null, T)
            r.cycle()
            val dest = Files.createTempDirectory("svod-clone-").also { Files.delete(it) }

            SyncBootstrap.clone(r.remote, dest, V, syncBranch = BRANCH)

            assertEquals("# n\n", Files.readString(dest.resolve("n.md")))
            val bad = assertFailsWith<IllegalArgumentException> {
                SyncBootstrap.clone(r.remote, Files.createTempDirectory("svod-clone-").also { Files.delete(it) }, V, syncBranch = "a..b")
            }
            assertTrue("a..b" in bad.message!!)
        }
    }

    // ---- validation and persistence ----

    @Test
    fun `unsafe sync branch names are rejected and ordinary ones accepted`() {
        val bad = listOf(
            "", "..", "a..b", "-x", "--force", "HEAD", "a b", " main", "main ", "a\tb", "a\nb", "a\u0000b", "a\u007fb",
            "x@{1}", "feature/", "x.lock", "a/x.lock", "a~b", "a^b", "a:b", "a?b", "a*b", "a[b", "a\\b", "/a", "a//b", "a.", ".a",
            "@", "+main", "+refs/heads/x", "refs/heads/main", "refs/svod/sync/v", "заметки", "café", "a\u00a0b", "main\u200b",
        )
        for (name in bad) assertNotNull(SvodConfig.syncBranchError(name), "should reject ${name.replace("\n", "\\n").replace("\t", "\\t")}")
        for (name in listOf("main", "master", "svod-sync", "team/notes", "release-1.2", "a.b", "x_y", "user@host", "a+b", "my-refs/x")) {
            assertNull(SvodConfig.syncBranchError(name), "should accept $name")
        }
    }

    @Test
    fun `a bad syncBranch in the config is an error at load`() {
        val ok = SvodConfig.default(Files.createTempDirectory("svod-cfg-"))
            .copy(backup = SvodConfig.BackupSettings("https://git.example.com/r.git", syncEnabled = true, syncBranch = "main"))
        assertEquals(emptyList(), ok.startupErrors())
        val bad = ok.copy(backup = ok.backup!!.copy(syncBranch = "a..b"))
        assertTrue(bad.startupErrors().any { "backup.syncBranch" in it }, bad.startupErrors().toString())
        val empty = ok.copy(backup = ok.backup!!.copy(syncBranch = ""))
        assertTrue(empty.startupErrors().any { "backup.syncBranch" in it }, empty.startupErrors().toString())
    }

    @Test
    fun `the sync branch is persisted by the backup store and absent for old files`() {
        val root = Files.createTempDirectory("svod-store-")
        val store = BackupConfigStore(root)
        store.save(BackupConfig(remote = "https://git.example.com/r.git", syncEnabled = true, syncBranch = "main"))
        assertEquals("main", store.load()!!.syncBranch)

        val legacy = root.resolve(".svod").resolve("backup.json")
        Files.writeString(legacy, """{"remote":"https://git.example.com/r.git","enabled":true,"syncEnabled":true}""")
        assertNull(store.load()!!.syncBranch, "a file written before 1.32.0 loads with the default")
        assertNotEquals("", Files.readString(legacy))
    }
}
