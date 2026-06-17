plugins {
    // Bumped to 2.3.21 to read the MCP Kotlin SDK 0.13.0 bytecode metadata.
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
    // GraalVM native-image (AOT). Builds a self-contained `svod-engine` binary per host platform
    // via `./gradlew nativeCompile` on a GraalVM JDK. Reachability metadata for reflective libs
    // (jgit, lucene, netty, snakeyaml, …) comes from the community metadata repository + the
    // committed dev/svod metadata; see docs/adr/0015. onnx-local (DJL) is JVM-only — a native
    // binary serves BM25 (`none`) and Ollama embedders.
    id("org.graalvm.buildtools.native") version "0.10.3"
}

group = "dev.svod"
version = "1.6.4"

repositories {
    mavenCentral()
}

// Lucene 10 requires JDK 21; this machine has JDK 20, so we pin Lucene 9.x.
val luceneVersion = "9.12.0"
val ktorVersion = "3.4.3"   // matches MCP Kotlin SDK 0.13.0
val mcpVersion = "0.13.0"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050840-r")
    implementation("org.yaml:snakeyaml:2.2")

    implementation("org.apache.lucene:lucene-core:$luceneVersion")
    implementation("org.apache.lucene:lucene-analysis-common:$luceneVersion")
    implementation("org.apache.lucene:lucene-queryparser:$luceneVersion")
    implementation("org.apache.lucene:lucene-highlighter:$luceneVersion")

    // In-process embeddings: DJL + ONNX Runtime + HuggingFace tokenizers (default provider).
    implementation(platform("ai.djl:bom:0.30.0"))
    implementation("ai.djl:api")
    implementation("ai.djl.huggingface:tokenizers")
    implementation("ai.djl.onnxruntime:onnxruntime-engine")

    // MCP server (agents) over streamable HTTP. Ktor is NOT transitive from the SDK.
    implementation("io.modelcontextprotocol:kotlin-sdk-server:$mcpVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion") // MCP uses Netty so it can serve TLS
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // External-change file watcher (App API / step 4)
    implementation("io.methvin:directory-watcher:0.18.0")

    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:$mcpVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    testImplementation("io.ktor:ktor-network-tls-certificates:$ktorVersion") // self-signed cert for the TLS test
    // Validate live App API responses against contract/openapi.yaml (contract test)
    testImplementation("com.atlassian.oai:swagger-request-validator-core:2.43.0")

    // Structured logging: logback backend (config + rolling files + levels), and a Sentry
    // appender that auto-reports ERROR logs to Sentry when SENTRY_DSN is set (no-op otherwise).
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("io.sentry:sentry-logback:7.10.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin {
    // Local dev uses JDK 20; release/native CI overrides to a GraalVM-provided JDK via
    // `-Psvod.jdk=21` (GraalVM has no JDK 20 build), so one GraalVM serves compile + native-image.
    jvmToolchain((findProperty("svod.jdk") as String?)?.toInt() ?: 20)
}

application {
    mainClass.set("dev.svod.engine.MainKt")
}

graalvmNative {
    // Use the GraalVM JDK on PATH (CI's setup-graalvm provides it); don't auto-download a toolchain.
    toolchainDetection.set(false)
    // Pull community reachability metadata for common libs (netty, etc.) automatically.
    metadataRepository { enabled.set(true) }
    binaries {
        named("main") {
            imageName.set("svod-engine")
            mainClass.set("dev.svod.engine.MainKt")
            // --no-fallback: fail the build rather than silently embedding a JVM fallback image.
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            // logback + the bundled web viewer assets must be embedded as resources.
            buildArgs.add("-H:IncludeResources=logback.xml")
            // Defer static init of libraries that touch native libs / the filesystem to run time,
            // so the closed-world analysis doesn't try to run them at build time. onnx-local (DJL)
            // is not exercised by a native binary (BM25 / Ollama only).
            // DJL/ONNX are loaded reflectively by Embedders and are NOT reachable in a native image
            // (onnx-local is JVM-only), so they're intentionally absent here.
            buildArgs.add("--initialize-at-run-time=org.eclipse.jgit,org.apache.lucene,io.netty")
            // Marking io.netty for run-time init transitively drags kotlin.DeprecationLevel (a plain
            // enum, used by @Deprecated annotations on Kotlin stdlib code netty's config references)
            // into the run-time-init set, but the closed-world analysis initialises it at build time
            // anyway → "Classes that should be initialized at run time got initialized during image
            // building". The enum is safe at build time; pin it there to resolve the conflict.
            buildArgs.add("--initialize-at-build-time=kotlin.DeprecationLevel")
            // ktor-server-netty 3.4.3 ships a bundled native-image.properties that injects
            // `-H:+SharedArenaSupport` — a JDK 22+ FFM option that GraalVM CE rejects outright
            // ("Could not find option 'SharedArenaSupport'"), aborting the build in seconds. Strip it
            // via the raw --exclude-config buildArg (the excludeConfig DSL does not match this jar and
            // regressed all three OSes). GraalVM reports the config resource with forward slashes on
            // every OS (the error URI is `...jar!/META-INF/native-image/...` even on Windows), so the
            // resource regex uses plain `/`. Both regexes avoid backslash-escaped metacharacters
            // (`[.]` not `\.`) because the native-image.cmd argfile on Windows mangles `\.`, which is
            // why earlier escaped variants matched on macOS/Linux but silently failed on Windows.
            buildArgs.add("--exclude-config")
            buildArgs.add(".*ktor-server-netty.*[.]jar")
            buildArgs.add("META-INF/native-image/io[.]ktor/ktor-server-netty/native-image[.]properties")
            // Defer Netty's native (kqueue/epoll) transport to run-time init so the closed-world
            // analysis doesn't initialize its JNI loaders at build time (Netty runs on NIO here).
            buildArgs.add(
                "--initialize-at-run-time=" + listOf(
                    "io.netty.channel.kqueue.KQueue", "io.netty.channel.kqueue.KQueueEventLoop",
                    "io.netty.channel.kqueue.KQueueEventArray", "io.netty.channel.kqueue.KQueueServerSocketChannel",
                    "io.netty.channel.kqueue.KQueueSocketChannel", "io.netty.channel.kqueue.KQueueServerDomainSocketChannel",
                    "io.netty.channel.kqueue.KQueueDomainSocketChannel", "io.netty.channel.kqueue.KQueueDatagramChannel",
                    "io.netty.channel.kqueue.Native", "io.netty.channel.epoll.Epoll", "io.netty.channel.epoll.Native",
                    "io.netty.handler.ssl.BouncyCastleAlpnSslUtils",
                    "io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler",
                ).joinToString(",")
            )
            resources.autodetect()
        }
    }
}

// Keep DJL + ONNX Runtime off the NATIVE image classpath only: onnx-local is JVM-only, and ONNX's
// JNI native-library loader (`findEntry0`) is unsupported by native-image. They remain on the JVM
// runtime classpath, so the app-image still serves in-process semantic search.
configurations.named("nativeImageClasspath") {
    exclude(group = "ai.djl")
    exclude(group = "ai.djl.huggingface")
    exclude(group = "ai.djl.onnxruntime")
    exclude(group = "com.microsoft.onnxruntime")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
    systemProperty("file.encoding", "UTF-8")
    // Opt-in flag for the test that hits a live Ollama; off by default for hermetic runs.
    systemProperty("svod.ollama.it", System.getProperty("svod.ollama.it", "false"))
    // Opt-in flags for the large-vault perf/soak test (forwarded to the forked test JVM).
    for (p in listOf("svod.perf", "svod.notes", "svod.writers"))
        System.getProperty(p)?.let { systemProperty(p, it) }
}
