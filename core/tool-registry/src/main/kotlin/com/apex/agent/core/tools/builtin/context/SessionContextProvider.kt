package com.apex.agent.core.tools.builtin.context

import com.apex.agent.core.llm.LlmMessage

/**
 * # 上下文回顾三件套（#172）— 会话上下文只读视图
 *
 * 会话内回顾工具（[ContextRecapTool] / [ContextSearchTool] /
 * [SessionStatsTool]）的数据源接口。此前 Agent 只有跨会话的 CS-Mem
 * （memory_* 工具），当前会话被压缩器裁剪或模型"迷路"时没有任何自救
 * 手段 —— 本接口把引擎持久化的当前会话消息（[ConversationMemory]，
 * app 层 SharedPrefs 单例）以只读、截断、可正则化的形式暴露给工具层。
 *
 * ## 模块边界说明
 *
 * [com.apex.agent.core.engine.ConversationMemory] 定义在 `core:agent-engine`
 * （依赖 `core:tool-registry`，反向依赖会成环），因此本模块通过
 * **lambda 读取器**解耦：app 层接线时传 `{ conversationMemory.load() }`
 * （见 ToolModule 的 `ConversationMemoryContextProvider { ... }`）。工具层
 * 与转换逻辑保持纯 JVM、零 Android 依赖，可直接单测。
 *
 * ## 记录契约
 *
 * - `role`：`"user" | "assistant" | "tool" | "system"`（system 保留但默认
 *   被回顾工具跳过——系统提示词对回顾没有信息量）；
 * - `content`：已截断至 [MAX_RECORD_CONTENT] 字符/条（回顾工具不需要
 *   全文，防长工具输出把回顾自身撑爆）；
 * - `timestamp`：毫秒时间戳；[LlmMessage] 本身不带时间，适配器填 0，
 *   统计工具对全 0 时间戳省略时长行；
 * - assistant 的工具调用以 `[tool_call] <name>` 行追加在 content 尾部
 * （[toContextRecords]），回顾工具用简单正则即可统计。
 */
interface SessionContextProvider {

    /** 当前会话消息的只读快照（index 为会话内序号，从 0 起）。 */
    fun records(): List<ContextRecord>
}

/** 单条会话记录（content 已截断至 [MAX_RECORD_CONTENT]）。 */
data class ContextRecord(
    val index: Int,
    /** "user" | "assistant" | "tool" | "system" */
    val role: String,
    val content: String,
    val timestamp: Long
)

/** 每条记录 content 的截断上限（回顾工具的防撑爆护栏）。 */
const val MAX_RECORD_CONTENT: Int = 2000

/** assistant 工具调用在 content 中的标记行前缀（[extractToolCallNames] 识别）。 */
internal const val TOOL_CALL_MARKER = "[tool_call]"

/**
 * LlmMessage 列表 → [ContextRecord] 列表。
 *
 * - System 消息保留（role=system），回顾工具按需跳过；
 * - Assistant 的 toolCalls 以 `[tool_call] <name>` 行追加到 content 末尾
 *   （引擎侧 assistant 纯工具调用轮的 content 常为空，不追加则统计丢数）；
 * - content 截断至 [MAX_RECORD_CONTENT]；
 * - timestamp 恒 0（LlmMessage 无时间信息；时长统计对全 0 自动省略）。
 */
fun toContextRecords(messages: List<LlmMessage>): List<ContextRecord> =
    messages.mapIndexed { index, message ->
        when (message) {
            is LlmMessage.System -> ContextRecord(index, "system", truncate(message.content), 0L)
            is LlmMessage.User -> ContextRecord(index, "user", truncate(message.content), 0L)
            is LlmMessage.Assistant -> {
                val callLines = message.toolCalls
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n") { "$TOOL_CALL_MARKER ${it.name}" }
                val content = if (callLines != null) {
                    if (message.content.isBlank()) callLines else message.content + "\n" + callLines
                } else {
                    message.content
                }
                ContextRecord(index, "assistant", truncate(content), 0L)
            }
            is LlmMessage.ToolResult -> ContextRecord(index, "tool", truncate(message.content), 0L)
        }
    }

private fun truncate(content: String): String =
    if (content.length <= MAX_RECORD_CONTENT) content else content.take(MAX_RECORD_CONTENT) + "…[truncated]"

/**
 * [ConversationMemory] → [SessionContextProvider] 的生产适配器。
 *
 * @param memoryReader 读取当前会话持久化消息（app 接线：
 *   `ConversationMemoryContextProvider { conversationMemory.load() }`）。
 *   每次调用现读（engine 增量 append 即时可见），失败由调用方兜底。
 */
class ConversationMemoryContextProvider(
    private val memoryReader: () -> List<LlmMessage>
) : SessionContextProvider {
    override fun records(): List<ContextRecord> =
        runCatching { toContextRecords(memoryReader()) }.getOrDefault(emptyList())
}
