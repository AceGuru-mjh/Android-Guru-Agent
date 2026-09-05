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
 * 1. 收集历史中所有 `Assistant.toolCalls` 的 id（按出现顺序）；
 * 2. 收集所有 `ToolResult.toolCallId`；
 * 3. 对每个"有 callId 但无 ToolResult"的悬空调用，在其后（不破坏
 *    消息顺序语义的前提下、追加到历史末尾之前最近的合法位置——即直接
 *    追加到历史尾部）合成一条 `ToolResult`：
 *    `"⚠ Interrupted: outcome UNKNOWN (process was killed during execution).
 *    Verify whether the action already took effect before repeating it."`
 *    —— 该文本喂给 LLM：既满足 API 的配对校验，又让模型知道结果未知、
 *    应先验证再决定是否重做（与 RecoveryPolicy 的 VERIFY 决策呼应）。
 * 4. 重复 id（理论不该有）只补一条，防重复注入。
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
     */
    fun repair(history: List<LlmMessage>): RepairReport {
        // 1+2. 收集：按顺序记录待配对的 callId；ToolResult 到达即移除。
        // 用 LinkedHashSet 保序 + 去重；末尾仍留在集合中的即悬空。
        val pending = LinkedHashSet<String>()
        for (msg in history) {
            when (msg) {
                is LlmMessage.Assistant -> msg.toolCalls.forEach { tc ->
                    if (tc.id.isNotBlank()) pending.add(tc.id)
                }
                is LlmMessage.ToolResult -> pending.remove(msg.toolCallId)
                else -> Unit
            }
        }
        if (pending.isEmpty()) return RepairReport(emptyList(), history.toList())

        // 3. 合成 ToolResult 追加到历史末尾。
        val patched = history.toMutableList()
        val repairedIds = mutableListOf<String>()
        for (callId in pending) {
            patched.add(LlmMessage.ToolResult(callId, UNKNOWN_RESULT_TEXT))
            repairedIds.add(callId)
        }
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
