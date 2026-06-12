package dev.svod.engine.core

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * OS-level advisory exclusive lock on a vault, giving single-instance semantics.
 *
 * Held for the lifetime of the engine. A second [acquire] on the same vault — whether
 * from another process or the same JVM — fails with [VaultLockedException]. The PID is
 * recorded in the lock file purely for diagnostics.
 */
class VaultLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    val lockFile: Path,
) : AutoCloseable {

    override fun close() {
        try {
            if (lock.isValid) lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        fun acquire(root: Path): VaultLock {
            val dir = root.resolve(".svod")
            Files.createDirectories(dir)
            val lockFile = dir.resolve("lock")

            val channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )

            val lock: FileLock? = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null // already held within this JVM
            }

            if (lock == null) {
                channel.close()
                throw VaultLockedException(root)
            }

            // Record our PID for humans inspecting the lock; not load-bearing.
            channel.truncate(0)
            channel.write(ByteBuffer.wrap("${ProcessHandle.current().pid()}\n".toByteArray()))
            channel.force(true)

            return VaultLock(channel, lock, lockFile)
        }
    }
}
