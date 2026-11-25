package dev.svod.engine.lifecycle

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps dist/install.sh (the one-command install) in step with UpdateService's platform list and the
 * README '## Download & install' table, so a renamed archive or binary path breaks a test instead of
 * the install.
 */
class InstallScriptConsistencyTest {
    private val repoRoot: File =
        File(System.getProperty("svod.projectDir") ?: System.getProperty("user.dir")).absoluteFile.parentFile
    private val scriptFile = repoRoot.resolve("dist/install.sh")
    private val script: String by lazy {
        assertTrue(scriptFile.isFile, "dist/install.sh is missing at $scriptFile")
        scriptFile.readText()
    }
    private val downloadSection: String by lazy {
        val lines = repoRoot.resolve("README.md").readLines()
        val start = lines.indexOfFirst { it.trim() == "## Download & install" }
        assertTrue(start >= 0, "README.md has no '## Download & install' section")
        val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
            .let { if (it < 0) lines.size else start + 1 + it }
        lines.subList(start, end).joinToString("\n")
    }
    private val tableRows: List<String> by lazy {
        downloadSection.lines().filter { it.trimStart().startsWith("|") }
    }
    private val archiveRegex = Regex("SvodEngine-[a-z0-9]+-[a-z0-9]+\\.(tar\\.gz|zip)")
    private val oneLiner = "curl -fsSL https://raw.githubusercontent.com/FleetQ/svod-engine/main/dist/install.sh | sh"

    private fun archivesInScript() = archiveRegex.findAll(script).map { it.value }.toSet()

    @Test
    fun `install script exists and is POSIX sh`() {
        assertEquals("#!/bin/sh", script.lineSequence().first().trim(), "install.sh must start with #!/bin/sh")
        assertTrue(Regex("(?m)^set -eu\\s*$").containsMatchIn(script), "install.sh must use `set -eu`")
        if (File("/bin/sh").exists()) {
            val p = ProcessBuilder("/bin/sh", "-n", scriptFile.absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS), "sh -n did not finish")
            assertEquals(0, p.exitValue(), "sh -n dist/install.sh failed: $out")
        }
    }

    @Test
    fun `install script archive names match UpdateService platforms`() {
        val names = archivesInScript()
        val known = UpdateService.PLATFORM_LABELS.flatMap {
            listOf(UpdateService.archiveName(it), UpdateService.archiveName(it, "zip"))
        }.toSet()
        for (n in names) assertTrue(n in known, "install.sh names $n, which is not an UpdateService archive ($known)")
        for (label in listOf("macos-arm64", "linux-x64")) {
            assertTrue(label in UpdateService.PLATFORM_LABELS, "UpdateService has no platform $label")
            assertTrue(UpdateService.archiveName(label) in names, "install.sh does not install ${UpdateService.archiveName(label)}")
        }
    }

    @Test
    fun `install script archive names and binary paths match the README table`() {
        val names = archivesInScript()
        assertTrue(names.isNotEmpty(), "install.sh names no archive")
        for (n in names) {
            assertTrue(tableRows.any { n in it }, "$n from install.sh is not in the README Download & install table")
        }
        val binaries = mapOf(
            "SvodEngine-macos-arm64.tar.gz" to "SvodEngine.app/Contents/MacOS/SvodEngine",
            "SvodEngine-linux-x64.tar.gz" to "SvodEngine/bin/SvodEngine",
        )
        for ((archive, binary) in binaries) {
            assertTrue(binary in script, "install.sh does not use the binary path $binary")
            val row = tableRows.firstOrNull { archive in it }
            assertTrue(row != null && binary in row, "README row for $archive does not run $binary: $row")
        }
    }

    @Test
    fun `README one-liner points at the install script`() {
        val oneLinerAt = downloadSection.indexOf(oneLiner)
        assertTrue(oneLinerAt >= 0, "README Download & install has no `$oneLiner`")
        val tableAt = downloadSection.indexOf("| OS |")
        assertTrue(tableAt >= 0, "README Download & install has no table")
        assertTrue(oneLinerAt < tableAt, "the one-liner must come before the download table")
        assertTrue("SVOD_VERSION" in downloadSection, "README should say how to pin a version with SVOD_VERSION")
        assertTrue(scriptFile.isFile, "the one-liner points at dist/install.sh, which is missing")
    }

    @Test
    fun `install script exits with the Releases link on unsupported platforms and never overwrites a config`() {
        val unsupported = Regex("(?s)\\*\\)(.*?);;").findAll(script).map { it.groupValues[1] }
            .firstOrNull { "https://github.com/FleetQ/svod-engine/releases/latest" in it }
        assertTrue(unsupported != null, "the unsupported-platform branch must print the Releases link")
        assertTrue("exit 1" in unsupported || "die " in unsupported, "the unsupported-platform branch must exit 1")
        assertTrue(Regex("SVOD_VERSION").containsMatchIn(script), "install.sh must read SVOD_VERSION")
        assertTrue(Regex("-f \"\\\$SVOD_VAULT_DIR/config\\.json\"").containsMatchIn(script),
            "install.sh must check for an existing \$SVOD_VAULT_DIR/config.json before init")
        assertTrue(" init \"\$SVOD_VAULT_DIR\"" in script, "install.sh must run init on \$SVOD_VAULT_DIR")
        for (line in script.lines().filterNot { it.trimStart().startsWith("#") }) {
            assertTrue(!Regex(">\\s*\"?[^\\s]*config\\.json").containsMatchIn(line), "install.sh must never write a config.json: $line")
            assertTrue(!Regex("\\b(rm|mv|cp)\\b[^\\n]*config\\.json").containsMatchIn(line), "install.sh must never touch a config.json: $line")
        }
    }
}
