package com.apex.agent.core.code.standard

import com.apex.agent.core.code.standard.StandardPermissionEngine.Companion.extractPath
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * # Standard Read Guard — read-before-edit 硬约束（业界标准 edit/write 契约）
 *
 * 业界标准 CLI 编码智能体的 edit/write 工具都有同一条铁律：
 * **编辑一个文件之前必须先读过它**——否则模型对文件真实内容的认知
 * 是幻觉，old/new 精确替换必然失准。本引擎在工具调用链路上强制：
 *
 * - [check]：code_edit / code_write（覆盖已存在文件）执行前校验——
 *   目标未读过 → 拒绝并引导先读（新文件创建免检，新建合法）；
 * - [record]：code_read / code_edit / code_write 成功后登记读状态
 *   （写过的文件内容已知，等价于读过）。
 *
 * ## 路径归一
 *
 * 同一文件可能有三种形态（raw 绝对路径 / workspace 相对路径 / guest
 * 前缀 `/workspace/...`）——[trackedPathKey] 统一归一到可解析时的
 * 规范绝对路径，防「读 A 形态、编 B 形态」绕过守卫。
 *
 * 线程契约：读状态为并发集合；[workspaceRootProvider] 由引擎的
 * volatile 状态支撑，任意线程调用安全。
 */
internal class StandardReadGuard(
    /** 工作区根提供者（引擎的 volatile workspaceRoot——切换工作区即时生效）。 */
    private val workspaceRootProvider: () -> File?
) {

    /** 本会话已读文件键集。 */
    private val readFiles = ConcurrentHashMap.newKeySet<String>()

    /**
     * 执行前校验：未读先编 → 拒绝文案；放行返回 null。
     *
     * @param registryName 注册表工具 id（code_edit / code_write）
     * @param arguments 原始参数 JSON（path 提取口径与权限引擎一致）
     */
    fun check(registryName: String, arguments: String): String? {
        if (registryName !in GUARDED_TOOLS) return null
        val rawPath = extractPath(arguments) ?: return null
        if (trackedPathKey(rawPath) in readFiles) return null
        if (registryName == "code_write") {
            // 新建免检：能解析到宿主文件且存在 → 必须先读；不存在 → 放行
            val hostFile = resolveHostFile(rawPath)
            if (hostFile == null || !hostFile.isFile) return null
        }
        return "File has not been read in this session yet: $rawPath. Read it first " +
            "with the read tool, then retry with fresh content. If you intend to " +
            "create a NEW file, use code_write instead."
    }

    /**
     * 成功后登记读状态（code_read / code_edit / code_write）。
     */
    fun record(registryName: String, arguments: String) {
        if (registryName !in RECORDING_TOOLS) return
        extractPath(arguments)?.let { readFiles.add(trackedPathKey(it)) }
    }

    /** 清空读状态（新会话）。 */
    fun reset() = readFiles.clear()

    /** 追踪键：可解析到宿主文件用规范绝对路径，否则用原文。 */
    private fun trackedPathKey(raw: String): String =
        resolveHostFile(raw)?.absolutePath ?: raw.trim()

    /** 路径解析（raw / workspace 相对 / guest 前缀三形态，与 code_* 工具口径兼容）。 */
    private fun resolveHostFile(raw: String): File? {
        if (raw.isBlank()) return null
        File(raw).let { if (it.exists()) return it }
        val root = workspaceRootProvider() ?: return null
        File(root, raw.trimStart('/')).let { if (it.exists()) return it }
        if (raw.startsWith(GUEST_PATH)) {
            File(root, raw.removePrefix(GUEST_PATH).trimStart('/')).let {
                if (it.exists()) return it
            }
        }
        return null
    }

    companion object {
        /** PRoot Ubuntu 会话中工作区统一挂载点（与引擎一致）。 */
        private const val GUEST_PATH = "/workspace"

        /** 受守卫约束的工具集（编辑 / 覆盖写）。 */
        private val GUARDED_TOOLS = setOf("code_edit", "code_write")

        /** 成功后登记读状态的工具集（读过 / 写过 = 内容已知）。 */
        private val RECORDING_TOOLS = setOf("code_read", "code_edit", "code_write")
    }
}
