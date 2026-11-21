// Maven Central first: every plugin this build uses (Kotlin JVM, Kotlin serialization, GraalVM native build tools)
// is published there with its plugin marker. CI failed twice on 2026-11-21 when the Gradle Plugin Portal did not
// resolve org.graalvm.buildtools.native 0.10.3; the portal stays as the second source.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "svod-engine"
