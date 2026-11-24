package dev.svod.engine.lifecycle

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps dist/install.sh in step with UpdateService's platform list and the README
 * '## Download & install' table, and checks the README one-liner points at the script.
 */
class InstallScriptConsistencyTest {

    private val engineDir: Path = Paths.get(System.getProperty("svod.projectDir") ?: System.getProperty("user.dir"))
    private val repoRoot: Path = engineDir.toAbsolutePath().parent
    private val scriptPath: Path = repoRoot.resolve("dist/install.sh")
    private val readme: String = Files.readString(repoRoot.resolve("README.md"))

    private val archiveRegex = Regex("SvodEngine-[a-z0-9]+-[a-z0-9]+\\.(tar\\.gz|zip)")
    private val oneLiner = "curl -fsSL https://raw.githubusercontent.com/FleetQ/svod-engine/main/dist/install.sh | sh"
    private val binaryPaths = listOf("SvodEngine.app/Contents/MacOS/SvodEngine", "SvodEngine/bin/SvodEngine")

    private fun script(): String {
        assertTrue(Files.isRegularFile(scriptPath), "dist/install.sh does not exist")
        return Files.readString(scriptPath)
    }

    private fun downloadSection(): String {
        val lines = readme.lines()
        val start = lines.indexOfFirst { it.startsWith("## Download & install") }
        assertTrue(start >= 0, "README has no '## Download & install' section")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun tableRows(section: String): List<String> =
        section.lines().filter { it.trimStart().startsWith("|") && !it.trimStart().startsWith("|---") }

    private fun scriptArchives(text: String): Set<String> = archiveRegex.findAll(text).map { it.value }.toSet()

    @Test
    fun `install script exists and is POSIX sh`() {
        val text = script()
        assertEquals("#!/bin/sh", text.lines().first().trim(), "install.sh must start with #!/bin/sh")
        if (File("/bin/sh").exists()) {
            val process = ProcessBuilder("/bin/sh", "-n", scriptPath.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "sh -n did not finish")
            assertEquals(0, process.exitValue(), "sh -n dist/install.sh failed: $output")
        }
    }

    @Test
    fun `install script archive names match UpdateService platforms`() {
        val names = scriptArchives(script())
        val known = UpdateService.PLATFORM_LABELS.flatMap {
            listOf(UpdateService.archiveName(it), UpdateService.archiveName(it, "zip"))
        }.toSet()
        val unknown = names - known
        assertTrue(unknown.isEmpty(), "install.sh names archives UpdateService does not know: $unknown")
        listOf("macos-arm64", "linux-x64").forEach { label ->
            assertTrue(label in UpdateService.PLATFORM_LABELS, "UpdateService.PLATFORM_LABELS lacks $label")
            assertTrue(
                UpdateService.archiveName(label) in names,
                "install.sh does not name ${UpdateService.archiveName(label)}",
            )
        }
    }

    @Test
    fun `install script archive names and binary paths match the README table`() {
        val text = script()
        val rows = tableRows(downloadSection())
        assertTrue(rows.size >= 2, "README '## Download & install' has no table")
        scriptArchives(text).forEach { name ->
            assertTrue(rows.any { name in it }, "archive $name from install.sh is not in the README table")
        }
        binaryPaths.forEach { path ->
            assertTrue(path in text, "install.sh does not use binary path $path")
            assertTrue(rows.any { path in it }, "binary path $path is not in the README table")
        }
    }

    @Test
    fun `README one-liner points at the install script`() {
        val section = downloadSection()
        val oneLinerAt = section.indexOf(oneLiner)
        assertTrue(oneLinerAt >= 0, "README '## Download & install' lacks the one-liner: $oneLiner")
        val tableAt = section.indexOf("| OS |")
        assertTrue(tableAt >= 0, "README '## Download & install' has no table header")
        assertTrue(oneLinerAt < tableAt, "the one-liner must come before the download table")
        val beforeTable = section.substring(0, tableAt)
        assertTrue("SVOD_VERSION" in beforeTable, "the one-command block should mention SVOD_VERSION")
        assertTrue("SvodEngine-windows-x64.zip" in beforeTable, "the one-command block needs a Windows note")
        assertTrue(oneLiner in script(), "install.sh header should show the one-liner")
    }

    @Test
    fun `install script exits with the Releases link on unsupported platforms and never overwrites a config`() {
        val text = script()
        val unsupported = text.indexOf("no one-command install")
        assertTrue(unsupported >= 0, "install.sh has no unsupported-platform message")
        val releasesLink = "https://github.com/FleetQ/svod-engine/releases/latest"
        val tail = text.substring(unsupported)
        val linkAt = tail.indexOf(releasesLink)
        assertTrue(linkAt >= 0, "unsupported-platform branch must print $releasesLink")
        assertTrue("exit 1" in tail.substring(linkAt), "unsupported-platform branch must exit 1")
        listOf("SVOD_VERSION", "SVOD_HOME", "SVOD_VAULT_DIR").forEach {
            assertTrue(it in text, "install.sh does not read $it")
        }
        val configCheck = text.indexOf("[ -f \"\$SVOD_VAULT_DIR/config.json\" ]")
        assertTrue(configCheck >= 0, "install.sh must check for an existing \$SVOD_VAULT_DIR/config.json")
        val initAt = text.indexOf(" init \"\$SVOD_VAULT_DIR\"")
        assertTrue(initAt > configCheck, "install.sh must run init only after the config.json check")
    }
}
