plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "dev.svod"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050840-r")
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
    // Integrity tests spin up real coroutines + git; give them room and surface output.
    maxHeapSize = "2g"
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
    systemProperty("file.encoding", "UTF-8")
}
