package dev.svod.engine.lifecycle

import dev.svod.engine.index.ModelManager
import dev.svod.engine.security.Secrets
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `svod-engine doctor [config.json]`: a read-only setup check. Every check is one line (ok / warn /
 * fail, a warn or fail with a one-line fix); any FAIL makes the exit code 1. Doctor never writes to
 * the vault, the config, the discovery file or the model cache, and never prints a secret value —
 * only the ref and who owns it.
 */
object Doctor {

    enum class Status { OK, WARN, FAIL }

    data class Check(val status: Status, val name: String, val message: String, val fix: String? = null)

    /** Everything Doctor reads from the machine, injectable so tests touch no real env, keychain or home folder. */
    data class Env(
        val env: Map<String, String> = System.getenv(),
        val resolveSecret: (String) -> String = { ref ->
            if (ref.startsWith("env:")) {
                val name = ref.removePrefix("env:")
                env[name] ?: throw IllegalStateException("environment secret not set: $name")
            } else Secrets.resolve(ref)
        },
        val portFree: (host: String, port: Int) -> Boolean = { host, port ->
            runCatching {
                ServerSocket().use { s ->
                    s.reuseAddress = false
                    s.bind(InetSocketAddress(InetAddress.getByName(host), port))
                }
            }.isSuccess
        },
        val discoveryFile: Path = env["SVOD_DISCOVERY_FILE"]?.let { Paths.get(it) } ?: EngineDiscovery.DEFAULT_PATH,
        val modelCacheDir: Path = ModelManager.sharedCacheDir(),
        val pidAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).isPresent },
        val osName: String = System.getProperty("os.name") ?: "",
    ) {
        // The generated toString would dump [env] — the whole process environment, secrets included.
        override fun toString() = "Doctor.Env(discoveryFile=$discoveryFile, modelCacheDir=$modelCacheDir, osName=$osName)"
    }

    /** Loads [path]; a missing or malformed file is one FAIL and no config (so no other check runs). */
    fun checkConfigFile(path: Path): Pair<SvodConfig?, List<Check>> {
        if (!Files.isRegularFile(path)) {
            return null to listOf(Check(Status.FAIL, "config", "config file not found: $path", "run svod-engine init, or pass the path of your config.json"))
        }
        if (!Files.isReadable(path)) {
            return null to listOf(Check(Status.FAIL, "config", "config file is not readable: $path", "make $path readable by this user"))
        }
        return try {
            SvodConfig.load(path) to listOf(Check(Status.OK, "config", "$path loaded"))
        } catch (e: SerializationException) {
            null to listOf(Check(Status.FAIL, "config", "config is not valid JSON for Svod: ${oneLine(e.message)}", "fix the JSON at $path"))
        } catch (e: IllegalArgumentException) {
            null to listOf(Check(Status.FAIL, "config", "config cannot be read: ${oneLine(e.message)}", "fix the JSON at $path"))
        } catch (e: java.io.IOException) {
            null to listOf(Check(Status.FAIL, "config", "config cannot be read: ${oneLine(e.message)}", "make $path readable by this user"))
        }
    }

    fun run(config: SvodConfig, env: Env = Env()): List<Check> {
        val engine = runningEngine(env)
        return validation(config) + agents(config) + secrets(config, env) + vaults(config) +
            listOfNotNull(engine?.let { engineCheck(it) }) + ports(config, env, engine) + embedder(config, env)
    }

    fun format(checks: List<Check>): List<String> = checks.map { c ->
        val tag = when (c.status) { Status.OK -> "ok  "; Status.WARN -> "warn"; Status.FAIL -> "FAIL" }
        "$tag ${c.name}: ${c.message}" + (c.fix?.let { " — fix: $it" } ?: "")
    } + (
        "${checks.count { it.status == Status.OK }} ok, ${checks.count { it.status == Status.WARN }} warn, " +
            "${checks.count { it.status == Status.FAIL }} fail"
        )

    fun exitCode(checks: List<Check>): Int = if (checks.any { it.status == Status.FAIL }) 1 else 0

    // (a) every validate() error, with stale grants only a warning on loopback (as startupErrors()).
    internal fun validation(config: SvodConfig): List<Check> {
        val errors = config.validate()
        val stale = if (config.host in SvodConfig.LOOPBACK) config.staleGrants().toSet() else emptySet()
        if (errors.isEmpty()) return listOf(Check(Status.OK, "config", "valid"))
        return errors.map { e ->
            if (e in stale) Check(Status.WARN, "config", "$e (ignored on loopback)", "remove the grant from the config")
            else Check(Status.FAIL, "config", e, "fix this in the config")
        }
    }

    // (b) same wording as the startup banner.
    internal fun agents(config: SvodConfig): List<Check> =
        if (config.agents.isNotEmpty()) listOf(Check(Status.OK, "agents", "${config.agents.size} configured"))
        else listOf(
            Check(
                Status.WARN, "agents", "no agents configured — every MCP client will be rejected until you add one",
                "add one to agents[] in the config or from the app (POST /api/v1/agents)",
            ),
        )

    // (c) secret refs: only env:/file:/keychain: values are resolved; literal values are never printed.
    internal fun secrets(config: SvodConfig, env: Env): List<Check> = buildList {
        for (a in config.agents) {
            if (isRef(a.token)) add(secretRef("agent '${a.agentId}' token", a.token, env))
            else add(Check(Status.OK, "secrets", "agent '${a.agentId}' token is set inline"))
        }
        for (u in config.users) if (isRef(u.keyRef)) add(secretRef("user '${u.userId}' keyRef", u.keyRef, env))
        // The engine resolves embedder.apiKeyRef only for the OpenAI provider (Embedders.create);
        // a leftover ref under another provider is never read, so it must not fail the check.
        if (config.embedder.provider.lowercase() in setOf("openai", "remote-openai")) {
            config.embedder.apiKeyRef?.takeIf(::isRef)?.let { add(secretRef("embedder.apiKeyRef", it, env)) }
        }
        config.reranker.apiKeyRef?.takeIf(::isRef)?.let { add(secretRef("reranker.apiKeyRef", it, env)) }
        for ((field, tls) in listOf("mcpTls" to config.mcpTls, "appApiTls" to config.appApiTls)) {
            if (tls == null) continue
            if (Files.isRegularFile(Paths.get(tls.keystorePath))) add(Check(Status.OK, "tls", "$field.keystorePath ${tls.keystorePath} exists"))
            else add(Check(Status.FAIL, "tls", "$field.keystorePath ${tls.keystorePath} does not exist", "create the keystore or fix $field.keystorePath"))
            if (isRef(tls.keystorePassword)) add(secretRef("$field.keystorePassword", tls.keystorePassword, env))
            if (isRef(tls.keyPassword)) add(secretRef("$field.keyPassword", tls.keyPassword, env))
        }
    }

    private fun isRef(value: String) = SvodConfig.SECRET_REF_PREFIXES.any { value.startsWith(it) }

    private fun secretRef(owner: String, ref: String, env: Env): Check {
        if (ref.startsWith("keychain:") && !env.osName.lowercase().contains("mac")) {
            return Check(Status.FAIL, "secrets", "$owner '$ref': keychain: refs only work on macOS", "use env: or file: on this platform")
        }
        val resolved = runCatching { env.resolveSecret(ref) }
        if (resolved.isSuccess) return Check(Status.OK, "secrets", "$owner '$ref' resolves")
        val fix = when {
            ref.startsWith("env:") -> "set the environment variable ${ref.removePrefix("env:")} for the engine"
            ref.startsWith("file:") -> "create ${ref.removePrefix("file:")} (readable by the engine user) or fix the ref"
            else -> "add the keychain item or fix the ref"
        }
        return Check(Status.FAIL, "secrets", "$owner '$ref' does not resolve", fix)
    }

    // (d) vault folders: writable, or creatable, and a git repo (or one that will be initialised).
    internal fun vaults(config: SvodConfig): List<Check> = config.resolvedVaults().filter { it.path.isNotBlank() }.flatMap { v ->
        val name = "vault '${v.id}'"
        val path = Paths.get(v.path).toAbsolutePath()
        when {
            Files.isDirectory(path) -> buildList {
                if (Files.isWritable(path)) add(Check(Status.OK, name, "$path writable"))
                else add(Check(Status.FAIL, name, "$path is not writable", "give the engine user write access to $path"))
                if (!Files.exists(path.resolve(".git"))) {
                    add(Check(Status.WARN, name, "$path is not a git repo yet; it will be initialised as a git repo on first start"))
                }
            }
            Files.exists(path) -> listOf(Check(Status.FAIL, name, "$path is not a folder", "point the vault path at a folder"))
            else -> {
                var parent: Path? = path.parent
                while (parent != null && !Files.exists(parent)) parent = parent.parent
                if (parent != null && Files.isDirectory(parent) && Files.isWritable(parent)) {
                    listOf(Check(Status.WARN, name, "$path does not exist; it will be created on first start"))
                } else {
                    listOf(Check(Status.FAIL, name, "$path does not exist and ${parent ?: "its parent"} is not writable", "create $path or change the vault path"))
                }
            }
        }
    }

    internal data class RunningEngine(val pid: Long, val appApiPort: Int?, val mcpPort: Int?)

    // (e) the discovery file, read only; missing or unparsable is ignored.
    internal fun runningEngine(env: Env): RunningEngine? = runCatching {
        if (!Files.isRegularFile(env.discoveryFile)) return null
        val obj = Json.parseToJsonElement(Files.readString(env.discoveryFile)) as? JsonObject ?: return null
        val pid = (obj["pid"] as? JsonPrimitive)?.longOrNull ?: return null
        if (!env.pidAlive(pid)) return null
        RunningEngine(pid, (obj["appApiPort"] as? JsonPrimitive)?.intOrNull, (obj["mcpPort"] as? JsonPrimitive)?.intOrNull)
    }.getOrNull()

    private fun engineCheck(e: RunningEngine) = Check(
        Status.WARN, "engine",
        "an engine is already running (pid ${e.pid} on ports ${e.appApiPort ?: "?"}/${e.mcpPort ?: "?"}); stop it before starting another",
    )

    // (f) the configured ports must be free; port 0 (any free port) is skipped.
    internal fun ports(config: SvodConfig, env: Env, engine: RunningEngine?): List<Check> {
        // An unresolvable host fails every bind; say so rather than calling each port "in use".
        if (runCatching { InetAddress.getByName(config.host) }.isFailure) {
            return listOf(Check(Status.FAIL, "ports", "host '${config.host}' does not resolve", "fix host in the config"))
        }
        return listOf("appApiPort" to config.appApiPort, "mcpPort" to config.mcpPort).filter { it.second in 1..65535 }.map { (field, port) ->
            when {
                env.portFree(config.host, port) -> Check(Status.OK, "ports", "$field $port free")
                engine != null && port in setOf(engine.appApiPort, engine.mcpPort) ->
                    Check(Status.WARN, "ports", "$field $port in use by the running engine (pid ${engine.pid})", "stop that engine before starting another")
                else -> Check(Status.FAIL, "ports", "$field $port in use", "stop the process using it or change appApiPort/mcpPort")
            }
        }
    }

    // (g) the onnx-local model: files only, never a download.
    internal fun embedder(config: SvodConfig, env: Env): List<Check> {
        if (config.embedder.provider.lowercase() !in setOf("onnx-local", "local-onnx")) return emptyList()
        fun hasModel(dir: Path) = Files.isRegularFile(dir.resolve(ModelManager.MODEL_FILE)) &&
            Files.isRegularFile(dir.resolve(ModelManager.TOKENIZER_FILE))
        config.embedder.onnxLocalPath?.let { local ->
            val dir = Paths.get(local)
            return if (hasModel(dir)) listOf(Check(Status.OK, "embedder", "onnx-local model at $dir"))
            else listOf(Check(Status.FAIL, "embedder", "onnxLocalPath $dir must contain ${ModelManager.MODEL_FILE} and ${ModelManager.TOKENIZER_FILE}", "fix embedder.onnxLocalPath or remove it to use the bundled model"))
        }
        val id = config.embedder.onnxModelId
        // Every vault loads the model (ModelManager.resolve): its own legacy .svod/models copy, else the
        // shared cache. So it is ready only if the shared cache has it or every vault has its own copy.
        val shared = env.modelCacheDir.resolve(id)
        val legacy = config.resolvedVaults().filter { it.path.isNotBlank() }
            .mapNotNull { v -> runCatching { Paths.get(v.path).resolve(".svod").resolve("models").resolve(id) }.getOrNull() }
        val found = when {
            hasModel(shared) -> shared
            legacy.isNotEmpty() && legacy.all(::hasModel) -> legacy.first()
            else -> null
        }
        if (found != null) return listOf(Check(Status.OK, "embedder", "onnx-local model '$id' at $found"))
        // Only bundled models have a pinned download; any other id makes the first start fail.
        if (id !in ModelManager.BUNDLED) {
            return listOf(
                Check(
                    Status.FAIL, "embedder", "model '$id' is not downloaded and has no pinned download",
                    "set embedder.onnxLocalPath to a folder with ${ModelManager.MODEL_FILE} and ${ModelManager.TOKENIZER_FILE}, or use a bundled model (${ModelManager.BUNDLED.keys.joinToString()})",
                ),
            )
        }
        return listOf(
            Check(
                Status.WARN, "embedder", "model not downloaded; it will be fetched on first start (~120 MB)",
                "let the first start download it, or set embedder.provider to none",
            ),
        )
    }

    private fun oneLine(s: String?) = (s ?: "unknown error").lineSequence().first().take(200)
}
