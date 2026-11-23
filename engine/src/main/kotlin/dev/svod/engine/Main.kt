package dev.svod.engine

import dev.svod.engine.core.SvodEngine
import dev.svod.engine.index.VaultEval
import dev.svod.engine.lifecycle.ApiCompatibility
import dev.svod.engine.lifecycle.ConfigInit
import dev.svod.engine.lifecycle.Doctor
import dev.svod.engine.lifecycle.EngineDiscovery
import dev.svod.engine.lifecycle.SvodConfig
import dev.svod.engine.lifecycle.SvodNode
import dev.svod.engine.migrate.BasicMemoryImport
import dev.svod.engine.migrate.ClaudeMemoryImport
import dev.svod.engine.migrate.NotionImport
import dev.svod.engine.migrate.ObsidianImport
import dev.svod.engine.security.SecretScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Engine entrypoint. Reads a config file (first arg, else a default vault), starts the node,
 * and blocks until a signal. launchd manages the process lifecycle (RunAtLoad + KeepAlive +
 * kickstart); on SIGTERM the JVM runs the shutdown hook for a graceful, lossless stop.
 *
 * One-shot import mode: `svod-engine import <config.json> <obsidian-dir> [into]` migrates an
 * Obsidian vault into the configured vault and exits (run while the daemon is stopped — the
 * vault lock is exclusive; while it's running, use POST /api/v1/import instead).
 * `svod-engine import-basic-memory <config.json> <basic-memory-dir> [into]` does the same for a
 * Basic Memory project, rewriting relations to note titles/permalinks into resolvable wikilinks.
 * `svod-engine import-notion <config.json> <notion-export-dir> [into]` imports an unzipped Notion
 * export under `into` (default `notion`), removing page ids from names and turning page links into wikilinks.
 * `svod-engine import-claude-memory <config.json> <memory-dir> [into]` imports a Claude Code memory folder under
 * `into` (default `claude-memory`), tagging each memory with its type and resolving `[[name]]` links; MEMORY.md is skipped.
 *
 * First run: `svod-engine init [dir] [--embedder none|onnx-local]` writes a new `dir/config.json`
 * (default `~/svod`, one WRITE agent with a random token; never overwrites) and prints the MCP
 * client snippets and system prompt, then exits.
 *
 * Setup check: `svod-engine doctor [config.json]` checks the config, secret refs, vault folders,
 * ports and the embedder model without changing anything, prints one line per check and exits 1
 * when any check fails.
 *
 * Retrieval eval: `svod-engine eval <config.json> <golden.jsonl>` scores search (recall@k, nDCG@10, MRR) on the
 * configured vault against a golden set, with the engine stopped and without writing notes (see [VaultEval]).
 *
 * `svod-engine --help` (`-h`) prints usage and `svod-engine --version` (`-V`) prints the engine
 * and App API contract versions; both exit before any config is read.
 */
fun main(args: Array<String>) {
    // A process whose uid has no passwd entry (a container run with --user <uid>) gets user.home "?" from a native
    // image; the JVM falls back to $HOME there. "?" broke logback's rolling file path and JGit's config save, and
    // would put ~/.svod paths under "?/". Do what the JVM does, before anything reads user.home.
    if (System.getProperty("user.home").let { it.isNullOrBlank() || it == "?" }) {
        System.getenv("HOME")?.takeIf { it.isNotBlank() }?.let { System.setProperty("user.home", it) }
    }
    // Lucene's memory-segment MMapDirectory needs Arena.ofShared(), which GraalVM CE native-image
    // (JDK 23) does not support (GR-52276). In the native binary use Lucene's MappedByteBuffer mmap
    // path instead; the JVM keeps memory segments. Must be set before Lucene's MMapDirectory loads.
    if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
        System.setProperty("org.apache.lucene.store.MMapDirectory.enableMemorySegments", "false")
        // GraalVM CE 23 native-image sets jdk.lang.Process.launchMechanism=FORK at startup,
        // overwriting -D (fixed upstream later), because the JDK's posix_spawn path needs the
        // jspawnhelper executable. A full fork copies the page tables of the whole engine: measured
        // ~7 ms per `git` spawn vs <1 ms on the JVM, and every note read spawns git twice. VFORK
        // shares the address space until exec. JDK 23 accepts VFORK on Linux only (macOS throws).
        if (System.getProperty("os.name").lowercase().startsWith("linux")) {
            System.setProperty("jdk.lang.Process.launchMechanism", "VFORK")
        }
    }
    val configPath = when (val cmd = parseCommand(args)) {
        Command.Help -> return println(usageText())
        Command.Version -> return println(versionText())
        is Command.Unknown -> {
            System.err.println("svod-engine: unknown option ${cmd.flag}")
            System.err.println(usageText())
            exitProcess(2)
        }
        is Command.Import -> return runImport(cmd.args)
        is Command.ImportBasicMemory -> return runImportBasicMemory(cmd.args)
        is Command.ImportNotion -> return runImportNotion(cmd.args)
        is Command.ImportClaudeMemory -> return runImportClaudeMemory(cmd.args)
        is Command.Clone -> return runClone(cmd.args)
        is Command.Init -> return runInit(cmd.args)
        is Command.Doctor -> return runDoctor(cmd.args)
        is Command.Eval -> return runEval(cmd.args)
        is Command.Run -> cmd.configPath
    }
    val config = configPath
        ?.let { SvodConfig.loadOrThrowValidated(it) }
        ?: SvodConfig.default(Paths.get(System.getProperty("user.home"), "svod-vault"))

    val node = SvodNode.start(config, configPath = configPath)
    Runtime.getRuntime().addShutdownHook(Thread({ node.shutdown() }, "svod-shutdown"))
    // A failed write only costs the app its auto-discovery; the engine itself is fine.
    runCatching {
        val discoveryFile = System.getenv("SVOD_DISCOVERY_FILE")?.let { Paths.get(it) } ?: EngineDiscovery.DEFAULT_PATH
        EngineDiscovery.write(discoveryFile, config.host, node.appApiPort, node.mcpPort)
    }.onFailure { System.err.println("svod-engine: could not write the discovery file: ${it.message}") }

    startupBanner(config, node.appApiPort, node.mcpPort).forEach(::println)
    // Also on stderr so it lands in launchd's error log.
    noAgentsWarning(config)?.let { System.err.println(it.trim()) }

    CountDownLatch(1).await() // park until SIGTERM; the shutdown hook does the cleanup
}

internal sealed interface Command {
    data object Help : Command
    data object Version : Command
    data class Run(val configPath: Path?) : Command
    data class Import(val args: List<String>) : Command
    data class ImportBasicMemory(val args: List<String>) : Command
    data class ImportNotion(val args: List<String>) : Command
    data class ImportClaudeMemory(val args: List<String>) : Command
    data class Clone(val args: List<String>) : Command
    data class Init(val args: List<String>) : Command
    data class Doctor(val args: List<String>) : Command
    data class Eval(val args: List<String>) : Command
    data class Unknown(val flag: String) : Command
}

private val HELP_FLAGS = setOf("--help", "-h", "help")
private val VERSION_FLAGS = setOf("--version", "-V", "version")

internal fun parseCommand(args: Array<String>): Command {
    val first = args.firstOrNull() ?: return Command.Run(null)
    return when {
        first in HELP_FLAGS -> Command.Help
        first in VERSION_FLAGS -> Command.Version
        first == "import" -> Command.Import(args.drop(1))
        first == "import-basic-memory" -> Command.ImportBasicMemory(args.drop(1))
        first == "import-notion" -> Command.ImportNotion(args.drop(1))
        first == "import-claude-memory" -> Command.ImportClaudeMemory(args.drop(1))
        first == "clone" -> Command.Clone(args.drop(1))
        first == "init" -> Command.Init(args.drop(1))
        first == "doctor" -> Command.Doctor(args.drop(1))
        first == "eval" -> Command.Eval(args.drop(1))
        first.startsWith("-") -> Command.Unknown(first)
        else -> Command.Run(Paths.get(first))
    }
}

internal fun usageText(): String = """
    |usage:
    |  svod-engine [config.json]
    |      start the engine (default vault ~/svod-vault when no config is given)
    |  svod-engine init [dir] [--embedder none|onnx-local]
    |      write a new config.json in dir (default ~/svod) and print how to connect an agent
    |  svod-engine doctor [config.json]
    |      check the setup (config, secrets, vault, ports, model) without changing anything; exits 1 on any failure
    |  svod-engine eval <config.json> <golden.jsonl>
    |      score search quality (recall@k, nDCG@10, MRR) on your vault against a golden set, then exit (engine stopped; never writes notes)
    |  svod-engine import <config.json> <obsidian-dir> [into]
    |      import an Obsidian vault into the configured vault, then exit (engine stopped)
    |  svod-engine import-basic-memory <config.json> <basic-memory-dir> [into]
    |      import a Basic Memory project (relations to titles/permalinks resolved), then exit (engine stopped)
    |  svod-engine import-notion <config.json> <notion-export-dir> [into]
    |      import an unzipped Notion Markdown & CSV export (default into: notion; CSVs skipped), then exit (engine stopped)
    |  svod-engine import-claude-memory <config.json> <memory-dir> [into]
    |      import a Claude Code memory folder (type → tag, [[name]] links resolved, MEMORY.md skipped), then exit (engine stopped)
    |  svod-engine clone <remote> <dest-dir> <vaultId> [local-branch] [--branch <sync-branch>]
    |      clone an existing synced vault onto this machine, then exit
    |  svod-engine --help | -h
    |      print this help
    |  svod-engine --version | -V
    |      print the engine and App API contract versions
""".trimMargin()

internal fun versionText(): String =
    "svod-engine ${SvodNode.ENGINE_VERSION} (App API contract ${ApiCompatibility.CURRENT_CONTRACT_VERSION})"

/**
 * With no agents configured every MCP login is rejected (MCP auth only goes through the agent
 * registry), so say so — but keep running: agents can be added at runtime and the registry reloads.
 */
internal fun noAgentsWarning(config: SvodConfig): String? =
    if (config.agents.isNotEmpty()) null
    else "  warning: no agents configured — every MCP client will be rejected until you add one to " +
        "agents[] in the config or from the app (POST /api/v1/agents)"

/** The lines printed once the node is up. */
internal fun startupBanner(config: SvodConfig, appApiPort: Int, mcpPort: Int): List<String> = buildList {
    add("svod-engine ${SvodNode.ENGINE_VERSION} ready on java ${System.getProperty("java.version")}")
    add("  vault:   ${config.vaultPath}")
    add("  app api: http://${config.host}:$appApiPort  (loopback)")
    add("  mcp:     http://${config.host}:$mcpPort")
    noAgentsWarning(config)?.let(::add)
}

/**
 * Bootstrap a new machine into an existing synced vault:
 *   svod-engine clone <remote> <dest-dir> <vaultId> [branch] [--branch <sync-branch>]
 * Clones the canonical `refs/svod/sync/<vaultId>` from the remote into <dest-dir>; then point the
 * engine config at <dest-dir> with sync enabled + the same remote. Run while the daemon is stopped.
 */
private fun runClone(argv: List<String>) {
    // `--branch <name>`: the vault syncs through the plain remote branch refs/heads/<name> (syncBranch).
    val flag = argv.indexOf("--branch")
    require(flag < 0 || flag + 1 < argv.size) { "--branch needs a branch name" }
    val syncBranch = if (flag >= 0) argv[flag + 1] else null
    val args = if (flag >= 0) argv.filterIndexed { i, _ -> i != flag && i != flag + 1 } else argv
    require(args.size >= 3) { "usage: svod-engine clone <remote> <dest-dir> <vaultId> [local-branch] [--branch <sync-branch>]" }
    val (remote, dest, vaultId) = args
    dev.svod.engine.sync.SyncBootstrap.clone(
        remote, Paths.get(dest), vaultId, args.getOrNull(3) ?: "master", syncBranch = syncBranch,
    )
    println("clone: $vaultId ← $remote into $dest (sync ref ${syncBranch?.let { "refs/heads/$it" } ?: "refs/svod/sync/$vaultId"})")
    println("  next: add this dir to the engine config with sync enabled + the same remote, then start the engine")
}

/** `svod-engine init [dir] [--embedder none|onnx-local]`: write a new config, print the client setup. */
private fun runInit(argv: List<String>) {
    val flag = argv.indexOf("--embedder")
    require(flag < 0 || flag + 1 < argv.size) { "--embedder needs a value (none or onnx-local)" }
    val embedder = if (flag >= 0) argv[flag + 1] else "none"
    val args = if (flag >= 0) argv.filterIndexed { i, _ -> i != flag && i != flag + 1 } else argv
    // A leftover option (`--embedder=onnx-local`, `--help`) must not become a directory name.
    require(args.size <= 1 && args.none { it.startsWith("-") }) { "usage: svod-engine init [dir] [--embedder none|onnx-local]" }
    val dir = args.firstOrNull()?.let { Paths.get(it) } ?: Paths.get(System.getProperty("user.home"), "svod")
    val result = ConfigInit.write(dir, embedder)
    val configPath = result.configPath.toAbsolutePath().toString()
    println("init: wrote $configPath")
    println("  start: svod-engine ${if (configPath.any { it.isWhitespace() }) "\"$configPath\"" else configPath}")
    println("  the agent token below is a secret; it is kept in that file (owner-only where supported)")
    println()
    println(ConfigInit.clientSetup(result.config, result.token))
}

/** `svod-engine doctor [config.json]`: read-only setup check; exits 1 when any check fails. */
private fun runDoctor(args: List<String>) {
    if (args.size > 1 || args.any { it.startsWith("-") }) {
        System.err.println("usage: svod-engine doctor [config.json]")
        exitProcess(2)
    }
    val checks = if (args.isEmpty()) {
        Doctor.run(SvodConfig.default(Paths.get(System.getProperty("user.home"), "svod-vault")))
    } else {
        val (config, fileChecks) = Doctor.checkConfigFile(Paths.get(args[0]))
        if (config == null) fileChecks else fileChecks + Doctor.run(config)
    }
    Doctor.format(checks).forEach(::println)
    exitProcess(Doctor.exitCode(checks))
}

/** `svod-engine eval <config.json> <golden.jsonl>`: score search on the vault; engine stopped, no notes written. */
private fun runEval(args: List<String>) {
    if (args.size != 2 || args.any { it.startsWith("-") }) {
        System.err.println("usage: svod-engine eval <config.json> <golden.jsonl>")
        exitProcess(2)
    }
    try {
        val config = SvodConfig.loadOrThrowValidated(Paths.get(args[0]))
        VaultEval.run(config, Paths.get(args[1]))
    } catch (e: IllegalArgumentException) {
        System.err.println("svod-engine eval: ${e.message}")
        exitProcess(2)
    } catch (e: IllegalStateException) {
        System.err.println("svod-engine eval: ${e.message}")
        exitProcess(2)
    }
}

private fun runImport(args: List<String>) {
    require(args.size >= 2) { "usage: svod-engine import <config.json> <obsidian-dir> [into]" }
    runImportWith(args) { source, engine, into -> ObsidianImport.import(source, engine, into = into) }
}

private fun runImportBasicMemory(args: List<String>) {
    require(args.size >= 2) { "usage: svod-engine import-basic-memory <config.json> <basic-memory-dir> [into]" }
    runImportWith(args) { source, engine, into -> BasicMemoryImport.import(source, engine, into = into) }
}

private fun runImportNotion(args: List<String>) {
    require(args.size >= 2) { "usage: svod-engine import-notion <config.json> <notion-export-dir> [into]" }
    runImportWith(args) { source, engine, into -> NotionImport.import(source, engine, into = into.ifEmpty { "notion" }) }
}

private fun runImportClaudeMemory(args: List<String>) {
    require(args.size >= 2) { "usage: svod-engine import-claude-memory <config.json> <memory-dir> [into]" }
    runImportWith(args) { source, engine, into -> ClaudeMemoryImport.import(source, engine, into = into.ifEmpty { "claude-memory" }) }
}

private fun runImportWith(args: List<String>, run: suspend (Path, SvodEngine, String) -> ObsidianImport.Result) {
    val config = SvodConfig.loadOrThrowValidated(Paths.get(args[0]))
    val source = Paths.get(args[1])
    // Same guard as POST /api/v1/import: a mistyped path must fail loudly, not report "1 skipped".
    require(Files.isDirectory(source)) { "source is not a directory: ${args[1]}" }
    val into = args.getOrNull(2) ?: ""
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    SvodEngine.open(config.vault(), scope, SecretScanner(config.secretScanning)).use { engine ->
        val r = runBlocking { run(source, engine, into) }
        println("import: ${r.imported.size} imported, ${r.unchanged.size} unchanged, ${r.skipped.size} skipped")
        if (r.skipped.isNotEmpty()) println("  skipped (present-but-differs / blocked): ${r.skipped.joinToString(", ")}")
    }
}
