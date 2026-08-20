package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.FilesystemCapabilities
import com.apex.agent.platform.terminal.linux.LinuxFilesystem
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import java.io.File

/**
 * PR #68: Real PRoot Filesystem.
 *
 * Resolves WorkspacePath ("workspace:/foo/bar") to host-side java.io.File
 * inside the rootfs directory. Operations touch the REAL filesystem of the
 * rootfs — not a fake in-memory map.
 *
 * Spec: PR #68 — Real Linux Runtime.
 */
class PRootFilesystem(
    private val rootfsPath: AbsolutePath
) : LinuxFilesystem {

    override val capabilities: FilesystemCapabilities = FilesystemCapabilities(
        read = true,
        write = true,
        create = true,
        delete = true,
        execute = true,
        symbolicLinks = true,
        hardLinks = false,
        permissions = false,
        stat = true
    )

    private fun resolveToHost(path: WorkspacePath): File {
        // "workspace:/foo/bar" → rootfs/foo/bar
        val rel = path.value.removePrefix("workspace:").removePrefix("/")
        return File(rootfsPath.value, rel)
    }

    override suspend fun exists(path: WorkspacePath): Boolean =
        resolveToHost(path).exists()

    override suspend fun isDirectory(path: WorkspacePath): Boolean =
        resolveToHost(path).isDirectory

    override suspend fun createDirectories(path: WorkspacePath): Result<Unit> = runCatching {
        resolveToHost(path).mkdirs()
    }

    override suspend fun delete(path: WorkspacePath): Result<Unit> = runCatching {
        resolveToHost(path).deleteRecursively()
    }

    override suspend fun size(path: WorkspacePath): Long? {
        val f = resolveToHost(path)
        return if (f.exists()) f.length() else null
    }

    override suspend fun resolve(path: WorkspacePath): AbsolutePath =
        AbsolutePath(resolveToHost(path).absolutePath)
}
