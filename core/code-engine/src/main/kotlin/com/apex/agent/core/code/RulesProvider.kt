package com.apex.agent.core.code

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File
import java.io.IOException

/**
 * # Rules Provider — 行为规则系统（Issue #164）
 *
 * ## 背景
 *
 * v1.0 之前项目只有「权限规则」（[com.apex.agent.permission]，
 * 管工具能不能跑），完全没有「行为规则 / 项目规则」（管 Agent **应该怎么
 * 做事**）：AGENTS.md / CLAUDE.md / .cursorrules 全仓库零读取逻辑，
 * 用户在仓库里放的编码规范、提交约定、架构约束对 Agent 不可见。
 *
 * 设计对标：
 *  - **opencode 的 AGENTS.md 分层**——每目录一份、从文件位置向上回溯合并、
 *    同目录多候选文件取首个命中即止（AGENTS.md > CLAUDE.md > .cursorrules）；
 *  - **Claude Code 的 CLAUDE.md**——项目级规则文件 + 用户级全局规则双通道，
 *    全局规则由设置层持久化（AgentSettings.globalRules），项目规则由
 *    工作区文件即时发现。
 *
 * ## 语义
 *
 *  - **目录内优先级**：AGENTS.md > CLAUDE.md > .cursorrules。同目录首个
 *    命中即用，不再读该目录其他规则文件（opencode 语义——规则文件表达
 *    「本目录的规矩」，多份并存只会互相打架）；
 *  - **嵌套回溯**：从 activeFile 所在目录逐级向上到 workspaceRoot，每级
 *    至多取一个命中文件；**子目录在前**（更具体的规则优先展示给模型，
 *    阅读顺序即优先顺序）；activeFile 为空时只读 workspaceRoot 根级；
 *  - **防线**（规则文件是用户可放任意内容的文件，不能信任其大小与编码）：
 *    单文件 > [MAX_FILE_CHARS] 截断（尾部标注）；合并总量 >
 *    [MAX_TOTAL_CHARS] 从最上层（根级）开始丢弃并标注（最具体的子目录
 *    规则最后丢）；前 [PROBE_BYTES] 字节含 NUL 视为非文本直接跳过。
 *
 * ## IO 约定（本类唯一的例外条款）
 *
 * 全部 IO 同步封装，**调用方负责切 IO 线程**（引擎在 refreshContext /
 * prepareForTask 链路调用，与既有 CodeContextProvider 同一约定）。
 * 任何 IO 异常 / 路径异常都返回 null 或跳过该级文件，**绝不向上抛**——
 * 规则是增强信息，读不到的降级形态就是「没有这段」，不应该让一次规则
 * 读取失败毁掉整轮编码任务。跳过 / 截断 / 丢弃事件走 debug 日志留痕。
 */
class RulesProvider {

    // ═══════════════════════ 公开 API ═══════════════════════

    /**
     * 从 [activeFile] 所在目录向上回溯到 [workspaceRoot]，收集各级规则
     * 文件并合并（子目录在前，每块带 `### 来自 <相对路径>` 标注）。
     *
     * @param workspaceRoot 工作区根（host 绝对路径）；null / 不存在 /
     *   非目录 → 返回 null（无工作区就没有项目规则）
     * @param activeFile 当前打开的文件（工作区相对路径或绝对路径均可；
     *   null / 空白 → 只读根级）
     * @return 合并后的多块文本；没有任何命中文件时返回 null
     */
    fun loadProjectRules(workspaceRoot: File?, activeFile: String?): String? {
        val root = workspaceRoot?.takeIf { it.isDirectory } ?: return null
        return try {
            loadProjectRulesUnchecked(root, activeFile)
        } catch (e: IOException) {
            debug("project rules unreadable: ${e.message}")
            null
        } catch (e: SecurityException) {
            debug("project rules unreadable: ${e.message}")
            null
        }
    }

    /**
     * 全局规则块（设置层持久化的自由文本，对所有工作区生效）。
     *
     * 内容原样保留（用户预期：写了什么就是什么），仅去首尾空白后包一层
     * 段落标题；超过 [MAX_FILE_CHARS] 截断并尾部标注（与项目规则同一
     * 防线，也让设置页「超 32KB 截断风险」的提示有真实语义）。
     *
     * @return `## Global Rules` 段；空白输入返回 null（省略段落）
     */
    fun formatGlobalRules(globalRules: String): String? {
        val text = globalRules.trim()
        if (text.isEmpty()) return null
        val overLimit = text.length > MAX_FILE_CHARS
        return buildString {
            append(SECTION_GLOBAL)
            appendLine()
            append(if (overLimit) text.take(MAX_FILE_CHARS) else text)
            if (overLimit) {
                appendLine()
                append(TRUNCATED_NOTE)
            }
        }
    }

    /**
     * 项目规则块：把 [loadProjectRules] 的合并结果包成 `## Project Rules`
     * 段（内含各层级 `### 来自 <路径>` 标注），并声明优先级高于全局规则。
     *
     * @param merged [loadProjectRules] 的返回值
     * @return 段落文本；空白输入返回 null（省略段落）
     */
    fun formatProjectRules(merged: String): String? {
        val text = merged.trim()
        if (text.isEmpty()) return null
        return buildString {
            append(SECTION_PROJECT)
            appendLine()
            appendLine(
                "以下规则来自工作区内的规则文件（AGENTS.md / CLAUDE.md / " +
                    ".cursorrules，按目录层级从具体到通用排列）。" +
                    "它们优先于 Global Rules 与一般偏好，" +
                    "但永远低于安全与权限约束。"
            )
            append(text)
        }
    }

    // ═══════════════════════ 收集与合并 ═══════════════════════

    /** 一层命中：目录 → 该目录首个命中的规则文件 + 相对路径标注。 */
    private data class RuleHit(val relativePath: String, val text: String)

    private fun loadProjectRulesUnchecked(root: File, activeFile: String?): String? {
        val rootDir = root.canonicalFile
        val chain = directoryChain(rootDir, activeFile)

        val hits = mutableListOf<RuleHit>()
        for (dir in chain) {
            val file = RULE_FILE_PRIORITY.firstNotNullOfOrNull { name ->
                dir.resolve(name).takeIf { it.isFile }
            } ?: continue
            val text = readRuleText(file) ?: continue // 非文本/读取失败 → 跳过该级
            if (text.isBlank()) continue
            hits += RuleHit(relativePathOf(rootDir, file), text)
        }
        if (hits.isEmpty()) return null

        // 总量防线：子目录在前 = 列表头部 = 最后丢；从最上层（列表尾部）开始丢弃。
        val dropped = mutableListOf<String>()
        while (mergedLength(hits) > MAX_TOTAL_CHARS && hits.size > 1) {
            dropped += hits.removeAt(hits.size - 1).relativePath
        }
        if (dropped.isNotEmpty()) {
            debug("total exceeded ${MAX_TOTAL_CHARS} chars, dropped top-level: $dropped")
        }

        return buildString {
            hits.forEachIndexed { index, hit ->
                if (index > 0) appendLine()
                appendLine("$BLOCK_PREFIX${hit.relativePath}")
                append(hit.text)
            }
            if (dropped.isNotEmpty()) {
                appendLine()
                appendLine(
                    "（合并后的规则总量超过 ${MAX_TOTAL_CHARS} 字符，" +
                        "已省略更上层的规则文件：${dropped.joinToString("、")}）"
                )
            }
        }.ifBlank { null }
    }

    /**
     * 目录链：activeFile 所在目录 → … → workspaceRoot（**子目录在前**）。
     *
     * activeFile 为空 → 只有根级；activeFile 解析后不在 root 之下（异常
     * 输入 / 越界路径）→ 退化为只有根级，不向上越出工作区读文件。
     */
    private fun directoryChain(rootDir: File, activeFile: String?): List<File> {
        if (activeFile.isNullOrBlank()) return listOf(rootDir)

        val file = File(activeFile)
        val resolved = if (file.isAbsolute) file else rootDir.resolve(file)
        // 路径字符串层面取父目录（不要求文件真实存在——活动文件可能刚被删除）
        var dir = if (resolved.isDirectory) resolved else resolved.parentFile ?: return listOf(rootDir)
        dir = dir.canonicalFile

        val chain = mutableListOf<File>()
        var current: File? = dir
        while (current != null && current.startsWith(rootDir)) {
            chain += current
            if (current == rootDir) break
            current = current.parentFile
        }
        // 回溯必须落在 rootDir 上；任何偏差（符号链接 / 越界）都退化为根级。
        if (chain.isEmpty() || chain.last() != rootDir) return listOf(rootDir)
        return chain
    }

    /** 规则文件相对工作区根的路径（根级文件就是文件名本身）。 */
    private fun relativePathOf(rootDir: File, file: File): String = try {
        file.relativeTo(rootDir).path
    } catch (e: IllegalArgumentException) {
        file.name // 不在 root 之下（理论不可达，directoryChain 已保证）——退化为文件名
    }

    private fun mergedLength(hits: List<RuleHit>): Int =
        hits.sumOf { BLOCK_PREFIX.length + it.relativePath.length + 1 + it.text.length }

    // ═══════════════════════ 单文件读取防线 ═══════════════════════

    /**
     * 读取单个规则文件（有界内存 + 三重防线）：
     *
     * 1. **非文本探测**：前 [PROBE_BYTES] 字节含 NUL → 二进制，跳过；
     * 2. **有界读取**：至多读入 [MAX_FILE_CHARS] 判定所需的字节数
     *    （UTF-8 每字符 ≤ 4 字节，读 (N+1)*4 字节仍解码出 ≤ N 字符
     *    即可断定「文件已读完」），超大文件不会整只进内存；
     * 3. **截断**：解码后 > [MAX_FILE_CHARS] 截到上限并尾部标注。
     *
     * 任何 IO 异常 / 编码异常 → null（KDoc 例外条款：不抛）。
     */
    private fun readRuleText(file: File): String? = try {
        file.inputStream().use { input ->
            val probe = ByteArray(PROBE_BYTES)
            // 注：用三参 readNBytes 而非 readNBytes(ByteArray)——K2 新类型推断会把
            // 返回值经后续用法（copyOf(Int)/算术）约束成 Int，错误地选中
            // readNBytes(Int) 重载导致实参不匹配；三参形式无重载歧义。
            val probed = input.readNBytes(probe, 0, probe.size)
            if (probe.copyOf(probed).contains(0.toByte())) {
                debug("skip non-text rule file: ${file.name}")
                return@use null
            }
            val rest = ByteArray(READ_LIMIT_BYTES - probed)
            val restRead = input.readNBytes(rest, 0, rest.size)
            val text = String(probe.copyOf(probed) + rest.copyOf(restRead), Charsets.UTF_8)
            if (text.length > MAX_FILE_CHARS) {
                debug("rule file truncated at $MAX_FILE_CHARS chars: ${file.name}")
                text.take(MAX_FILE_CHARS) + "\n" + TRUNCATED_NOTE
            } else {
                text
            }
        }
    } catch (e: IOException) {
        debug("rule file unreadable (${file.name}): ${e.message}")
        null
    } catch (e: SecurityException) {
        debug("rule file unreadable (${file.name}): ${e.message}")
        null
    }

    private fun debug(message: String) {
        AppLogger.instance.debug(LogCategory.SYSTEM, TAG, message)
    }

    private companion object {
        const val TAG = "RulesProvider"

        /** 目录内优先级：首个命中即止，不再读该目录其他候选（opencode 语义）。 */
        val RULE_FILE_PRIORITY = listOf("AGENTS.md", "CLAUDE.md", ".cursorrules")

        /** 段落标题（与 [com.apex.agent.core.engine.EnginePrompts] 的 "## " 段风格对齐）。 */
        const val SECTION_GLOBAL = "## Global Rules"
        const val SECTION_PROJECT = "## Project Rules"
        const val BLOCK_PREFIX = "### 来自 "

        /** 非文本探测窗口：前 8KB 含 NUL 视为二进制。 */
        const val PROBE_BYTES = 8 * 1024

        /** 单文件上限（字符数）：超出截断。32KB 足够容纳任何认真的规则文件。 */
        const val MAX_FILE_CHARS = 32 * 1024

        /** 合并总量上限（字符数）：超出从最上层（根级）开始丢弃。 */
        const val MAX_TOTAL_CHARS = 64 * 1024

        /**
         * 有界读取的字节预算：probe + 后续字节。UTF-8 一个字符至多 4 字节，
         * (N+1)*4 字节预算保证「解码后 ≤ N 字符 ⇒ 文件已读尽」——即截断
         * 判定不会漏判（文件还有剩余却以为读完了）。
         */
        const val READ_LIMIT_BYTES = PROBE_BYTES + (MAX_FILE_CHARS + 1) * 4

        /** 截断尾注（对齐项目内「输出截断要可见」的一贯纪律）。 */
        const val TRUNCATED_NOTE =
            "（规则文件超过 32768 字符，已截断——完整内容请拆分为多个层级的规则文件）"
    }
}
