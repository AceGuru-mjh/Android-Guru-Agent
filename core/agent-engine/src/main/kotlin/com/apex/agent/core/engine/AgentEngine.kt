package com.apex.agent.core.engine

import kotlinx.coroutines.flow.Flow

/**
 * Agent引擎：核心循环 Plan → Act → Observe → Reflect
 * 纯Kotlin，零Android依赖
 */
interface AgentEngine {

    /**
     * 兼容旧接口：纯文本输入。内部委托给 [execute]([UserInput])。
     */
    fun execute(input: String): Flow<AgentEvent>

    /**
     * 多模态输入入口。
     *
     * - [UserInput.text] 进入 `LlmMessage.User.content`；
     * - [UserInput.images] 进入 `LlmMessage.User.images`，由 Vision-capable LLM 识别；
     * - [UserInput.files] 作为文件路径上下文拼入用户文本，Agent 可用工具读取。
     *
     * 图片的 base64 仅保留在内存 `conversationHistory`（当前会话上下文），
     * 不写入持久化 [ConversationMemory]（避免存储爆炸）。
     */
    fun execute(input: UserInput): Flow<AgentEvent>

    /**
     * 停止当前执行
     */
    suspend fun abort()

    /**
     * 响应用户对 [AgentEvent.UserInputRequired] 事件的提交，恢复挂起的执行。
     * 无 pending 请求时视为 no-op。
     */
    fun submitUserInput(answer: String)

    /**
     * 用户取消对 [AgentEvent.UserInputRequired] 事件的等待，终止挂起。
     */
    fun cancelUserInput()
}
