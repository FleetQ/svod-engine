package dev.svod.engine.security

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes a secret to disk owner-read/write only. The file is CREATED with 0600 (not created then
 * chmod'ed), so it is never briefly world-readable; the directory gets 0700 the same way. On a
 * filesystem without POSIX permissions (Windows) the file is written plainly.
 */
object SecretFiles {

    private val OWNER_RW = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    private val OWNER_RWX = OWNER_RW + PosixFilePermission.OWNER_EXECUTE

    /** Append to an owner-only file, creating it 0600 (and its directory 0700) when absent. [write] truncates; this does not. */
    fun append(path: Path, value: String) {
        if (!Files.exists(path)) write(path, "")
        Files.writeString(path, value, StandardOpenOption.APPEND)
    }

    /** Re-apply owner-only mode to an existing file (after an atomic rename that lost it). No-op on non-POSIX stores. */
    fun restrict(path: Path) {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path, OWNER_RW)
    }

    fun write(path: Path, value: String) {
        val dir = path.parent
        if (dir != null && !Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_RWX))
            } catch (_: UnsupportedOperationException) {
                Files.createDirectories(dir)
            }
        }
        if (!Files.exists(path)) {
            try {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_RW))
            } catch (_: UnsupportedOperationException) {
                Files.createFile(path)
            }
        } else {
            runCatching { Files.setPosixFilePermissions(path, OWNER_RW) }
        }
        Files.writeString(path, value, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    /** True when [path] is a regular file inside [dir] (after normalisation) — the guard before a delete. */
    fun isInside(path: Path, dir: Path): Boolean =
        path.toAbsolutePath().normalize().startsWith(dir.toAbsolutePath().normalize()) && Files.isRegularFile(path)
}
