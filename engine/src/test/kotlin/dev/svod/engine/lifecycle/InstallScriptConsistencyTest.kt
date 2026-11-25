package dev.svod.engine.lifecycle

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps dist/install.sh (the one-command install) in step with the release archive names in
 * [UpdateService] and the README '## Download & install' table.
 */
class InstallScriptConsistencyTest {

    private val repoRoot: File =
        File(System.getProperty("svod.projectDir") ?: System.getProperty("user.dir")).absoluteFile.parentFile
    private val scriptFile = File(repoRoot, "dist/install.sh")
    private val script: String by lazy {
        assertTrue(scriptFile.isFile, "dist/install.sh does not exist")
        scriptFile.readText()
    }
    private val readme: String = File(repoRoot, "README.md").readText()

    private val archiveRegex = Regex("SvodEngine-[a-z0-9]+-[a-z0-9]+\\.(tar\\.gz|zip)")
    private val oneLiner = "curl -fsSL https://raw.githubusercontent.com/FleetQ/svod-engine/main/dist/install.sh | sh"
    private val releasesLink = "https://github.com/FleetQ/svod-engine/releases/latest"
    private val macBinary = "SvodEngine.app/Contents/MacOS/SvodEngine"
    private val linuxBinary = "SvodEngine/bin/SvodEngine"

    private fun downloadSection(): String {
        val lines = readme.lines()
        val start = lines.indexOfFirst { it.trim() == "## Download & install" }
        assertTrue(start >= 0, "README has no '## Download & install' section")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun tableRows(section: String): List<String> =
        section.lines().filter { it.trimStart().startsWith("|") }

    private fun scriptArchives(): Set<String> = archiveRegex.findAll(script).map { it.value }.toSet()

    @Test
    fun `install script exists and is POSIX sh`() {
        assertEquals("#!/bin/sh", script.lines().first().trim(), "install.sh must start with #!/bin/sh")
        if (File("/bin/sh").exists()) {
            val process = ProcessBuilder("/bin/sh", "-n", scriptFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "sh -n did not finish")
            assertEquals(0, process.exitValue(), "sh -n dist/install.sh failed: $output")
        }
    }

    @Test
    fun `install script archive names match UpdateService platforms`() {
        val allowed = UpdateService.PLATFORM_LABELS.flatMap {
            listOf(UpdateService.archiveName(it), UpdateService.archiveName(it, "zip"))
        }.toSet()
        val names = scriptArchives()
        val unknown = names - allowed
        assertTrue(unknown.isEmpty(), "install.sh names archives UpdateService does not know: $unknown")
        for (label in listOf("macos-arm64", "linux-x64")) {
            assertTrue(label in UpdateService.PLATFORM_LABELS, "UpdateService.PLATFORM_LABELS lacks $label")
            assertTrue(UpdateService.archiveName(label) in names, "install.sh does not name ${UpdateService.archiveName(label)}")
        }
    }

    @Test
    fun `install script archive names and binary paths match the README table`() {
        val rows = tableRows(downloadSection())
        for (name in scriptArchives()) {
            assertTrue(rows.any { name in it }, "README Download & install table has no row for $name")
        }
        val macRow = rows.firstOrNull { "SvodEngine-macos-arm64.tar.gz" in it }
        val linuxRow = rows.firstOrNull { "SvodEngine-linux-x64.tar.gz" in it }
        assertTrue(macRow != null && macBinary in macRow, "README macOS row does not run $macBinary")
        assertTrue(linuxRow != null && linuxBinary in linuxRow, "README Linux row does not run $linuxBinary")
        assertTrue(macBinary in script, "install.sh does not use $macBinary")
        assertTrue(linuxBinary in script, "install.sh does not use $linuxBinary")
    }

    @Test
    fun `README one-liner points at the install script`() {
        val section = downloadSection()
        val lineAt = section.indexOf(oneLiner)
        assertTrue(lineAt >= 0, "README Download & install section lacks the one-liner: $oneLiner")
        val tableAt = section.indexOf("| OS |")
        assertTrue(tableAt >= 0, "README Download & install section has no table")
        assertTrue(lineAt < tableAt, "the one-liner must come before the table")
        assertTrue("SVOD_VERSION" in section, "README should mention SVOD_VERSION next to the one-liner")
        assertTrue(oneLiner in script, "install.sh header should show the one-liner")
    }

    @Test
    fun `install script exits with the Releases link on unsupported platforms and never overwrites a config`() {
        assertTrue(releasesLink in script, "install.sh must point unsupported platforms at $releasesLink")
        val unsupported = script.lines().indexOfFirst { "no one-command install" in it }
        assertTrue(unsupported >= 0, "install.sh has no unsupported-platform message")
        val branch = script.lines().drop(unsupported).take(6).joinToString("\n")
        assertTrue(releasesLink in branch, "the unsupported-platform branch must print $releasesLink")
        assertTrue("exit 1" in branch, "the unsupported-platform branch must exit 1")
        assertTrue("config.json\" ]" in script, "install.sh must check whether config.json exists before init")
        assertTrue(Regex("""-f\s+"\${'$'}SVOD_VAULT_DIR/config\.json"""").containsMatchIn(script),
            "install.sh must test -f \"\$SVOD_VAULT_DIR/config.json\"")
        assertTrue(script.lines().none { Regex(""">\s*"?\S*config\.json""").containsMatchIn(it.substringBefore("#")) },
            "install.sh must never write into a config.json")
    }
}
