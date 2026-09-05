package com.apex.agent.core.codetools.fs

import java.io.File

/**
 * Workspace 路径安全：将用户/Agent 提供的 [path] 解析为最终 [File]，阻止 `..`
 * 穿越 / 符号链接逃逸。与 `FilePathSafety` 行为一致，独立实现以保持
 * :core:code-tools 零 Android 依赖、可纯 JVM 单测。
 */
object WorkspacePathSafety {

    fun safeResolve(basePath: File, path: String): File {
        val resolved = when {
            path.isBlank() -> basePath
            path.startsWith("/") -> File(path)
            else -> File(basePath, path)
        }
        val canonical = try { resolved.canonicalFile } catch (_: java.io.IOException) { resolved.absoluteFile }
        val allowRoot = try { basePath.canonicalFile } catch (_: java.io.IOException) { basePath.absoluteFile }
        if (!isUnder(canonical, allowRoot)) {
            throw SecurityException(
                "Path escapes workspace: '$path' → '${canonical.path}' (outside '${allowRoot.path}')"
            )
        }
        return canonical
    }

    private fun isUnder(child: File, root: File): Boolean {
        val cp = child.absolutePath.removeSuffix(File.separator)
        val rp = root.absolutePath.removeSuffix(File.separator)
        if (cp == rp) return true
        return cp.startsWith(rp + File.separator)
    }
}
