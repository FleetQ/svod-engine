plugins {
    // Bumped to 2.3.21 to read the MCP Kotlin SDK 0.13.0 bytecode metadata.
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "dev.svod"
version = "0.1.0"

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
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:$mcpVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")

    // jgit logs via slf4j; provide a simple backend so warnings are not swallowed
    implementation("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin {
    jvmToolchain(20)
}

application {
    mainClass.set("dev.svod.engine.MainKt")
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
}
