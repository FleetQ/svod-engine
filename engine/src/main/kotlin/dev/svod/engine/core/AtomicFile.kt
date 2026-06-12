package dev.svod.engine.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * The only sanctioned way to write a file in the vault.
 *
 * Sequence: write a uniquely-named temp sibling -> fsync its bytes -> atomic rename
 * over the target -> fsync the parent directory. A crash at any point leaves the
 * target either fully old or fully new, never half-written. Temp files carry a
 * recognizable suffix so crash recovery can identify and delete orphans.
 */
object AtomicFile {

    /** Suffix that marks an in-progress write. Recovery deletes any file ending with it. */
    const val TMP_SUFFIX = ".svod-tmp"

    fun isTmp(path: Path): Boolean = path.fileName?.toString()?.endsWith(TMP_SUFFIX) == true

    /**
     * @param crash optional fault injector; when armed, throws at the configured stage
     *              leaving on-disk residue exactly as a real crash would.
     */
    fun write(target: Path, bytes: ByteArray, crash: CrashInjection? = null) {
        val parent = target.parent ?: throw IOException("target has no parent: $target")
        Files.createDirectories(parent)
        val tmp = parent.resolve("${target.fileName}.${UUID.randomUUID()}$TMP_SUFFIX")

        FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { ch ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) ch.write(buf)
            crash?.maybeCrash(CrashPoint.AFTER_TMP_WRITE)
            ch.force(true) // fsync data + metadata of the temp file
        }
        crash?.maybeCrash(CrashPoint.AFTER_FSYNC)

        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        crash?.maybeCrash(CrashPoint.AFTER_RENAME)

        fsyncDir(parent) // make the rename itself durable
    }

    private fun fsyncDir(dir: Path) {
        // Directory fsync is unsupported on some filesystems/platforms; best-effort.
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // ignore — durability of the rename is then bounded by the OS, not us
        }
    }
}

/** Stages at which a write can be interrupted, used by tests to simulate power loss. */
enum class CrashPoint { AFTER_TMP_WRITE, AFTER_FSYNC, AFTER_RENAME }

class CrashSimulationException(val at: CrashPoint) : RuntimeException("simulated crash at $at")

/**
 * Test seam for crash injection. Production code holds one instance with [armed] = null,
 * so [maybeCrash] is a cheap no-op on the hot path.
 */
class CrashInjection(@Volatile var armed: CrashPoint? = null) {
    fun maybeCrash(at: CrashPoint) {
        if (armed == at) {
            armed = null // fire once
            throw CrashSimulationException(at)
        }
    }
}
