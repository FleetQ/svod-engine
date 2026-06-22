package dev.svod.engine.lifecycle

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiVaultTest {

    private val http: HttpClient = HttpClient.newHttpClient()
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun put(port: Int, path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun twoVaultConfig(personal: Path, work: Path) = SvodConfig(
        vaults = listOf(
            SvodConfig.VaultSettings("personal", personal.toString(), name = "Personal"),
            SvodConfig.VaultSettings("work", work.toString(), name = "Work"),
        ),
        defaultVault = "personal",
        appApiPort = 0,
        mcpPort = 0,
        embedder = SvodConfig.EmbedderSettings(provider = "none"),
        agents = listOf(SvodConfig.AgentSettings("t", "scribe", "WRITE")),
    )

    @Test
    fun `two vaults open, route by vault param, writes are isolated`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val node = SvodNode.start(twoVaultConfig(personal, work))
        val port = node.appApiPort
        try {
            assertEquals(200, put(port, "/api/v1/file?path=p.md&vault=personal", """{"content":"# personal"}""").statusCode())
            assertEquals(200, put(port, "/api/v1/file?path=w.md&vault=work", """{"content":"# work"}""").statusCode())

            // each vault sees only its own file
            val pTree = get(port, "/api/v1/tree?vault=personal").body()
            val wTree = get(port, "/api/v1/tree?vault=work").body()
            assertTrue(pTree.contains("p.md") && !pTree.contains("w.md"), "personal tree: $pTree")
            assertTrue(wTree.contains("w.md") && !wTree.contains("p.md"), "work tree: $wTree")

            // omitted vault ⇒ default (personal)
            assertEquals(200, get(port, "/api/v1/file?path=${enc("p.md")}").statusCode())
            // p.md does not exist in work ⇒ 404
            assertEquals(404, get(port, "/api/v1/file?path=${enc("p.md")}&vault=work").statusCode())
            // unknown vault ⇒ 404
            assertEquals(404, get(port, "/api/v1/file?path=${enc("p.md")}&vault=bogus").statusCode())

            // /vaults lists both, personal flagged default
            val vaults = get(port, "/api/v1/vaults").body()
            assertTrue(vaults.contains("\"id\":\"personal\"") && vaults.contains("\"id\":\"work\""), vaults)
            assertTrue(vaults.contains("\"default\":true"), vaults)

            // the two repos are genuinely separate git histories
            assertFalse(Files.readString(work.resolve("w.md")).isEmpty())
            assertFalse(Files.exists(work.resolve("p.md")), "personal write must not touch the work vault")
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `shutdown drains every vault and data survives restart`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val node = SvodNode.start(twoVaultConfig(personal, work))
        try {
            assertEquals(200, put(node.appApiPort, "/api/v1/file?path=p.md&vault=personal", """{"content":"keep personal"}""").statusCode())
            assertEquals(200, put(node.appApiPort, "/api/v1/file?path=w.md&vault=work", """{"content":"keep work"}""").statusCode())
        } finally {
            node.shutdown()
        }

        // fresh node re-opens BOTH vaults (every lock released) and reads each write back
        val node2 = SvodNode.start(twoVaultConfig(personal, work))
        try {
            assertTrue(get(node2.appApiPort, "/api/v1/file?path=${enc("p.md")}&vault=personal").body().contains("keep personal"))
            assertTrue(get(node2.appApiPort, "/api/v1/file?path=${enc("w.md")}&vault=work").body().contains("keep work"))
        } finally {
            node2.shutdown()
        }
    }

    @Test
    fun `cross-vault qualified links surface as cross-vault backlinks via the API`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val node = SvodNode.start(twoVaultConfig(personal, work))
        val port = node.appApiPort
        try {
            assertEquals(200, put(port, "/api/v1/file?path=project.md&vault=work", """{"content":"# Project"}""").statusCode())
            // a personal note linking the work note with a qualified [[work:project]]
            assertEquals(200, put(port, "/api/v1/file?path=note.md&vault=personal", """{"content":"see [[work:project]]"}""").statusCode())

            val links = get(port, "/api/v1/file/links?path=${enc("project.md")}&vault=work").body()
            assertTrue(links.contains("personal:note.md"),
                "work:project should list a cross-vault backlink from personal:note.md — got: $links")
        } finally {
            node.shutdown()
        }
    }

    private fun post(port: Int, path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun delete(port: Int, path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).DELETE().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `cross-vault rename rewrites qualified backlinks in the other vault`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val node = SvodNode.start(twoVaultConfig(personal, work))
        val port = node.appApiPort
        try {
            assertEquals(200, put(port, "/api/v1/file?path=project.md&vault=work", """{"content":"# Project"}""").statusCode())
            assertEquals(200, put(port, "/api/v1/file?path=note.md&vault=personal", """{"content":"track [[work:project]] here"}""").statusCode())

            val rev = Regex("\"revision\":\"([^\"]+)\"").find(get(port, "/api/v1/file?path=${enc("project.md")}&vault=work").body())!!.groupValues[1]
            val moved = post(port, "/api/v1/file/move?vault=work", """{"from":"project.md","to":"proj.md","expectedRevision":"$rev"}""")
            assertEquals(200, moved.statusCode())

            // the qualified backlink in the personal vault was rewritten to the new name
            val note = get(port, "/api/v1/file?path=${enc("note.md")}&vault=personal").body()
            assertTrue(note.contains("[[work:proj]]"), "cross-vault backlink rewritten: $note")
            assertTrue(!note.contains("[[work:project]]"), "old qualified link gone: $note")
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `backup remote set via the API persists across a restart`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val cfg = twoVaultConfig(personal, work)

        val node = SvodNode.start(cfg)
        try {
            // ssh with key auth (no inline password) is accepted; an http(s) userinfo would be rejected
            val r = put(node.appApiPort, "/api/v1/settings/backup", """{"remote":"ssh://git@example.com/svod-backup.git","enabled":true}""")
            assertEquals(200, r.statusCode())
        } finally { node.shutdown() }

        // a fresh node on the same vaults loads the persisted backup remote (redacted in the view)
        val node2 = SvodNode.start(cfg)
        try {
            val syncCfg = get(node2.appApiPort, "/api/v1/sync/config").body()
            assertTrue(syncCfg.contains("svod-backup.git"), "persisted backup remote must load on restart: $syncCfg")
        } finally { node2.shutdown() }
    }

    @Test
    fun `config validation rejects bad multi-vault setups`() {
        val dupIds = SvodConfig(vaults = listOf(
            SvodConfig.VaultSettings("a", "/tmp/a"), SvodConfig.VaultSettings("a", "/tmp/b"),
        ))
        assertTrue(dupIds.validate().any { it.contains("unique") }, dupIds.validate().toString())

        val badDefault = SvodConfig(vaults = listOf(SvodConfig.VaultSettings("a", "/tmp/a")), defaultVault = "nope")
        assertTrue(badDefault.validate().any { it.contains("defaultVault") }, badDefault.validate().toString())

        val badGrant = SvodConfig(
            vaults = listOf(SvodConfig.VaultSettings("a", "/tmp/a")),
            agents = listOf(SvodConfig.AgentSettings("t", "x", "READ_ONLY", vaults = listOf("ghost"))),
        )
        assertTrue(badGrant.validate().any { it.contains("unknown vault 'ghost'") }, badGrant.validate().toString())

        // legacy single-vault config still validates clean (back-compat)
        assertTrue(SvodConfig(vaultPath = "/tmp/legacy").validate().isEmpty())
    }

    @Test
    fun `create vault hot-adds it, routes by vault param, and survives a restart`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val freshDir = Files.createTempDirectory("svod-fresh-parent-").resolve("fresh")
        val cfgFile = Files.createTempFile("svod-config-", ".json")
        val cfg = twoVaultConfig(personal, work)
        Files.writeString(cfgFile, SvodConfig.toJson(cfg))

        val node = SvodNode.start(cfg, configPath = cfgFile)
        val port = node.appApiPort
        try {
            val created = post(port, "/api/v1/vaults", """{"id":"fresh","path":"$freshDir"}""")
            assertEquals(201, created.statusCode(), created.body())
            // 201 body is a Vault row: {id,name,default=false,sync}
            assertTrue(created.body().contains("\"id\":\"fresh\""), created.body())
            assertTrue(created.body().contains("\"name\":\"fresh\""), created.body())
            assertTrue(created.body().contains("\"default\":false"), created.body())

            // the dir + git repo exist on disk
            assertTrue(Files.isDirectory(freshDir), "vault dir created")
            assertTrue(Files.isDirectory(freshDir.resolve(".git")), "git repo initialised")

            // GET /vaults now lists it alongside the originals
            assertTrue(get(port, "/api/v1/vaults").body().contains("\"id\":\"fresh\""))

            // ?vault=fresh routes: a write then read in the new vault works
            assertEquals(200, put(port, "/api/v1/file?path=hello.md&vault=fresh", """{"content":"# fresh"}""").statusCode())
            assertTrue(get(port, "/api/v1/file?path=${enc("hello.md")}&vault=fresh").body().contains("# fresh"))
        } finally {
            node.shutdown()
        }

        // a fresh node loads the persisted vault from the config file (no longer in the startup `cfg` object)
        val node2 = SvodNode.start(SvodConfig.load(cfgFile), configPath = cfgFile)
        try {
            assertTrue(get(node2.appApiPort, "/api/v1/vaults").body().contains("\"id\":\"fresh\""),
                "created vault must survive a node restart")
            assertTrue(get(node2.appApiPort, "/api/v1/file?path=${enc("hello.md")}&vault=fresh").body().contains("# fresh"),
                "the write into the created vault must survive a restart")
        } finally {
            node2.shutdown()
        }
    }

    @Test
    fun `create vault with an existing id returns 409`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val node = SvodNode.start(twoVaultConfig(personal, work))
        try {
            assertEquals(409, post(node.appApiPort, "/api/v1/vaults", """{"id":"work"}""").statusCode())
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `create vault into a non-empty directory returns 409`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val occupied = Files.createTempDirectory("svod-occupied-")
        Files.writeString(occupied.resolve("existing.txt"), "x")
        val node = SvodNode.start(twoVaultConfig(personal, work))
        try {
            val r = post(node.appApiPort, "/api/v1/vaults", """{"id":"fresh","path":"$occupied"}""")
            assertEquals(409, r.statusCode(), r.body())
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `create vault with an invalid id returns 400`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val node = SvodNode.start(twoVaultConfig(personal, work))
        try {
            assertEquals(400, post(node.appApiPort, "/api/v1/vaults", """{"id":"Bad ID!"}""").statusCode())
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `delete vault unregisters it, drops it from config, survives restart, and leaves the dir on disk`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val cfgFile = Files.createTempFile("svod-config-", ".json")
        val cfg = twoVaultConfig(personal, work)
        Files.writeString(cfgFile, SvodConfig.toJson(cfg))

        val node = SvodNode.start(cfg, configPath = cfgFile)
        val port = node.appApiPort
        try {
            // a write so the work vault genuinely has content + handles to release before deletion
            assertEquals(200, put(port, "/api/v1/file?path=w.md&vault=work", """{"content":"# work"}""").statusCode())

            val deleted = delete(port, "/api/v1/vaults/work")
            assertEquals(200, deleted.statusCode(), deleted.body())
            // 200 body is DeleteVaultResult { id, path (the on-disk dir), filesDeleted=false }
            assertTrue(deleted.body().contains("\"id\":\"work\""), deleted.body())
            assertTrue(deleted.body().contains(work.toString()), "removed dir path returned: ${deleted.body()}")
            assertTrue(deleted.body().contains("\"filesDeleted\":false"), deleted.body())

            // unregistered: GET /vaults no longer lists it, and ?vault=work stops routing (404)
            assertFalse(get(port, "/api/v1/vaults").body().contains("\"id\":\"work\""))
            assertEquals(404, get(port, "/api/v1/file?path=${enc("w.md")}&vault=work").statusCode())

            // deleteFiles=false ⇒ the directory is left in place for the caller to dispose of
            assertTrue(Files.isDirectory(work), "vault dir left on disk when deleteFiles=false")
        } finally {
            node.shutdown()
        }

        // a fresh node loads the persisted config: the removed vault is gone for good
        val node2 = SvodNode.start(SvodConfig.load(cfgFile), configPath = cfgFile)
        try {
            assertFalse(get(node2.appApiPort, "/api/v1/vaults").body().contains("\"id\":\"work\""),
                "deleted vault must not reappear after a restart")
            assertTrue(get(node2.appApiPort, "/api/v1/vaults").body().contains("\"id\":\"personal\""),
                "the surviving default vault must still load")
        } finally {
            node2.shutdown()
        }
    }

    @Test
    fun `delete vault with deleteFiles=true also removes the directory from disk`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val node = SvodNode.start(twoVaultConfig(personal, work))
        try {
            assertTrue(Files.isDirectory(work))
            val r = delete(node.appApiPort, "/api/v1/vaults/work?deleteFiles=true")
            assertEquals(200, r.statusCode(), r.body())
            assertTrue(r.body().contains("\"filesDeleted\":true"), r.body())
            assertFalse(Files.exists(work), "vault dir hard-deleted when deleteFiles=true")
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `delete the default vault returns 409`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val node = SvodNode.start(twoVaultConfig(personal, work)) // default = personal
        try {
            assertEquals(409, delete(node.appApiPort, "/api/v1/vaults/personal").statusCode())
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `delete the last remaining vault returns 409`() {
        val only = Files.createTempDirectory("svod-only-")
        val node = SvodNode.start(
            SvodConfig(
                vaults = listOf(SvodConfig.VaultSettings("solo", only.toString())),
                appApiPort = 0,
                mcpPort = 0,
                embedder = SvodConfig.EmbedderSettings(provider = "none"),
                agents = listOf(SvodConfig.AgentSettings("t", "scribe", "WRITE")),
            )
        )
        try {
            assertEquals(409, delete(node.appApiPort, "/api/v1/vaults/solo").statusCode())
        } finally {
            node.shutdown()
        }
    }

    @Test
    fun `delete an unknown vault returns 404`() {
        val personal = Files.createTempDirectory("svod-personal-")
        val work = Files.createTempDirectory("svod-work-")
        val node = SvodNode.start(twoVaultConfig(personal, work))
        try {
            assertEquals(404, delete(node.appApiPort, "/api/v1/vaults/ghost").statusCode())
        } finally {
            node.shutdown()
        }
    }
}
