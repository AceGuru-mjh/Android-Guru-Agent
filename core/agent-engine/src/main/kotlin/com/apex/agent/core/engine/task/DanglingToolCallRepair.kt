package com.apex.agent.core.engine.task

import com.apex.agent.core.llm.LlmMessage

/**
 * T76 — 悬空 toolCall 历史修补（审计 R-5：发现并修复的既有缺陷）。
 *
 * **缺陷现状**（本任务之前已存在）：
 * 进程死于工具执行中 → `ConversationMemory` 里最后一条
 * `Assistant.toolCalls=[...]` 之后没有配对的 `ToolResult` → App 重启后
 * 引擎 `load()` 恢复该历史 → 下一次 LLM 请求携带不完整的 tool_calls 序列
 * → OpenAI 兼容 API 校验失败（HTTP 400 "tool_calls must be followed by
 * tool messages"）→ 整个对话历史不可用。
 *
 * **修补规则**（恢复流程的第一步，纯函数便于单测）：
 * 1. **位置修复（2026-10-07 诊断包 crash 复盘）**：合成 ToolResult 必须插在
 *    「悬空 Assistant 消息（及其已存在的兄弟 ToolResult 块）**之后**、下一条
 *    非工具消息**之前**」——OpenAI 兼容端点（SiliconFlow 等）要求 tool 消息
 *    *紧随* tool_calls 消息。旧实现无条件追加到历史**末尾**：中断后用户
 *    发起新任务时 `execute()` 已先把新 User 消息入历史（ApexAgentEngine.kt
 *    L498 先于 buildMessages/repair），修补结果变成
 *    `[Assistant(toolCalls), User(新任务), ToolResult(合成)]` →
 *    Provider 400「insufficient tool messages following tool_calls message」
 *    → 工具降级重试两级全失败 → 会话报废（日志 15:05:07-15:05:08）。
 * 2. 对每个 Assistant.toolCalls 中「无紧邻配对 ToolResult」的悬空调用合成
 *    `ToolResult`：`"⚠ Interrupted: outcome UNKNOWN (process was killed
 *    during execution). Verify whether the action already took effect before
 *    repeating it."` —— 该文本喂给 LLM：既满足 API 的配对校验，又让模型
 *    知道结果未知、应先验证再决定是否重做（与 RecoveryPolicy 的 VERIFY
 *    决策呼应）。
 * 3. 全局去重：同一 callId（理论不该有，畸形防御）只补一条；历史上任何
 *    位置已存在该 id 的 ToolResult 视为已答，不再重复注入。
 * 4. 悬空仅看「紧邻块」：ToolResult 必须直接跟在所属 Assistant 消息之后
 *    才算配对；隔了 User/System 等消息的 ToolResult 不参与该块配对
 *    （那本身是另一种畸形，不在本修补范围）。
 *
 * 返回修补报告（修补了哪些 callId），由 TaskRuntime 决定是否 `save()` 回
 * ConversationMemory 并记录到 checkpoint。
 */
object DanglingToolCallRepair {

    /** 修补结果：无修补 / 修补列表。 */
    data class RepairReport(
        /** 本次合成补发的 ToolResult 对应的 callId 列表（有序）。 */
        val repairedCallIds: List<String>,
        /** 修补后的完整历史（仅当有修补时与输入不同）。 */
        val repairedHistory: List<LlmMessage>
    ) {
        val hasRepairs: Boolean get() = repairedCallIds.isNotEmpty()
    }

    /** 合成 ToolResult 的内容模板（LLM 可读，提示结果未知需验证）。 */
    const val UNKNOWN_RESULT_TEXT: String =
        "⚠ Interrupted: outcome UNKNOWN (process was killed during execution). " +
            "Verify whether the action already took effect before repeating it."

    /**
     * 扫描并修补历史。**纯函数**：不改入参列表，返回修补后的新列表。
     * 无悬空时返回输入的引用副本（零分配语义上等价）。
     *
     * 两遍扫描：第一遍收集全量已答 id（任意位置出现过的 ToolResult），
     * 第二遍逐块修补——合成 ToolResult 插在该 Assistant 消息的紧邻
     * ToolResult 块之后（块内已有配对结果的排在其后），保证 tool 消息
     * 紧随 tool_calls 消息，且不落在后续 User/System 消息之后。
     */
    fun repair(history: List<LlmMessage>): RepairReport {
        // 第一遍：全量已答 id（去重用；隔块出现的 ToolResult 也算已答，
        // 与旧实现的 LinkedHashSet 语义对齐——畸形重复 id 只补一条）。
        val answeredAnywhere = HashSet<String>()
        for (msg in history) {
            if (msg is LlmMessage.ToolResult && msg.toolCallId.isNotBlank()) {
                answeredAnywhere.add(msg.toolCallId)
            }
        }

        val patched = mutableListOf<LlmMessage>()
        val repairedIds = mutableListOf<String>()
        var i = 0
        while (i < history.size) {
            val msg = history[i]
            patched.add(msg)
            if (msg is LlmMessage.Assistant && msg.toolCalls.isNotEmpty()) {
                // 前进穿过紧邻的 ToolResult 块（属于本条 tool_calls 的应答）。
                val blockAnswered = mutableSetOf<String>()
                var j = i + 1
                while (j < history.size && history[j] is LlmMessage.ToolResult) {
                    val tr = history[j] as LlmMessage.ToolResult
                    if (tr.toolCallId.isNotBlank()) blockAnswered.add(tr.toolCallId)
                    patched.add(tr)
                    j++
                }
                // 块内未答且全量未答且未补发过的 → 就地合成（紧跟块尾，
                // 下一条非工具消息之前）。插入顺序与 toolCalls 声明顺序一致。
                msg.toolCalls.forEach { tc ->
                    val id = tc.id
                    if (id.isNotBlank() && id !in blockAnswered &&
                        id !in answeredAnywhere && id !in repairedIds
                    ) {
                        patched.add(LlmMessage.ToolResult(id, UNKNOWN_RESULT_TEXT))
                        repairedIds.add(id)
                    }
                }
                i = j
            } else {
                i++
            }
        }
        if (repairedIds.isEmpty()) return RepairReport(emptyList(), history.toList())
        return RepairReport(repairedIds, patched)
    }

    /**
     * 便捷入口：修补并检查"末尾 N 条内是否有悬空"（恢复横幅提示用——
     * 悬空紧邻末尾说明死在工具执行中，而非更早的历史分叉）。
     */
    fun tailHasDangling(history: List<LlmMessage>, tailWindow: Int = 4): Boolean {
        val tail = history.takeLast(tailWindow)
        return repair(tail).hasRepairs
    }
}
