package com.apex.agent.core.tools.builtin

import java.io.File

/**
 * 共享的文件路径解析助手：将用户/Agent 提供的 [path] 解析为最终 [File]，并阻止
 * 路径穿越（`..`）和符号链接逃逸，避免工具被诱导读写工作区之外的文件。
 *
 * 行为约定：
 * - 当 [basePath] 为 null / 空白时，允许绝对路径，但仍然会 canonicalize 并拒绝
 *   通过 `..` 逃逸到当前工作目录父级之外的路径（防止相对路径越权）。
 * - 当 [basePath] 配置时，相对路径会拼接到 [basePath]，绝对路径直接使用，最后
 *   canonicalize 并校验结果必须位于 `basePath.canonicalFile` 之下；否则抛出
 *   [SecurityException]，由调用方决定如何向 Agent 报告错误。
 *
 * 仅用于"解析 + 校验"，不做存在性 / 可读性检查，保持与原 `resolveFile` /
 * `resolve` 行为一致，调用方仍按需要执行 `exists()` / `canRead()` 等。
 */
object FilePathSafety {

    /**
     * 解析 [path] 相对于 [basePath] 的最终 [File]，确保规范化后的路径不会逃逸出
     * 允许的根目录。返回的 [File] 已经过 `canonicalFile` 处理，可直接用于后续 IO。
     *
     * @throws SecurityException 当解析结果位于允许根目录之外（路径穿越 / 符号链接逃逸）。
     */
    fun safeResolve(basePath: File?, path: String): File {
        val resolved = when {
            basePath == null || basePath.absolutePath.isBlank() -> File(path)
            path.startsWith("/") -> File(path)
            else -> File(basePath, path)
        }

        // 无法 canonicalize 不存在的文件父目录时退回 absoluteFile，避免抛 NoSuchFileException
        val canonical = try {
            resolved.canonicalFile
        } catch (e: java.io.IOException) {
            resolved.absoluteFile
        }

        val allowRoot = when {
            basePath == null || basePath.absolutePath.isBlank() -> null
            else -> try {
                basePath.canonicalFile
            } catch (e: java.io.IOException) {
                basePath.absoluteFile
            }
        }

        if (allowRoot != null) {
            // 必须严格位于允许根目录下（允许根目录本身）。
            if (!isUnder(canonical, allowRoot)) {
                throw SecurityException(
                    "Path escapes workspace: '$path' resolves to '${canonical.path}' " +
                        "which is outside of '${allowRoot.path}'"
                )
            }
        } else {
            // 无 basePath 时，仅拒绝 `..` 逃逸到当前工作目录的父级之外。
            val cwdRoot = File(".").canonicalFile
            if (!isUnder(canonical, cwdRoot)) {
                throw SecurityException(
                    "Path escapes working directory: '$path' resolves to '${canonical.path}' " +
                        "which is outside of '${cwdRoot.path}'"
                )
            }
        }

        return canonical
    }

    /** 当 [child] == [root] 或位于 root 之下时返回 true。 */
    private fun isUnder(child: File, root: File): Boolean {
        val childPath = child.absolutePath.removeSuffix(java.io.File.separator)
        val rootPath = root.absolutePath.removeSuffix(java.io.File.separator)
        if (childPath == rootPath) return true
        // 严格前缀匹配 + 分隔符，避免 "/data/x" 被误判为 "/data/xy" 的子目录
        return childPath.startsWith(rootPath + java.io.File.separator)
    }
}
