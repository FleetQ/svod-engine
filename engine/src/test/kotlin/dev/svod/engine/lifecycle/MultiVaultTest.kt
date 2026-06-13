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
}
