package dev.svod.engine.security

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

/** Loads a TLS keystore (PKCS12 or JKS) from disk for serving MCP over HTTPS. */
object Keystores {
    fun load(path: Path, password: CharArray): KeyStore {
        for (type in listOf("PKCS12", "JKS")) {
            try {
                val ks = KeyStore.getInstance(type)
                Files.newInputStream(path).use { ks.load(it, password) }
                return ks
            } catch (_: Exception) {
                // try the next type
            }
        }
        throw IllegalStateException("could not load keystore $path as PKCS12 or JKS")
    }
}
