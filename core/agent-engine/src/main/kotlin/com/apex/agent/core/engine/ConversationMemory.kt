package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmMessage

/**
 * 跨会话持久化的对话记忆。
 *
 * 引擎在每次执行前后调用 [load] / [append] / [save]，使 conversationHistory
 * 在 App 重启后仍然保留。Plan 模式生成的中间步骤 prompt 也会被持久化，
 * 以便用户回来后能继续未完成的任务。
 *
 * 实现方需要保证线程安全（引擎在 IO 线程上调用）。纯 JVM 模块只暴露
 * 接口，具体实现（SharedPreferences / Room / 文件）放在 Android 层。
 */
interface ConversationMemory {

    /**
     * 加载所有已持久化的消息。返回空列表表示新会话。
     * 引擎构造时调用一次。
     */
    fun load(): List<LlmMessage>

    /**
     * 追加单条消息到持久化存储（增量写）。
     * 引擎在每次消息加入 conversationHistory 后立即调用。
     */
    fun append(message: LlmMessage)

    /**
     * 用新的消息列表完整覆盖存储（全量写）。
     * 用于 Plan 模式完成后清理中间步骤 prompt，或压缩历史。
     */
    fun save(messages: List<LlmMessage>)

    /**
     * 清空所有持久化消息（开新会话）。
     */
    fun clear()

    /**
     * 当前持久化的消息条数（用于 UI 显示，避免 load() 整个列表）。
     */
    fun count(): Int
}
