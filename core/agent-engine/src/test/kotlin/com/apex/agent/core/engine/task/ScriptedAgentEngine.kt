package com.apex.agent.core.engine.task

import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.UserInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * T76 测试专用 — 脚本化 AgentEngine（直接发射预定义 AgentEvent 序列）。
 *
 * 与 FakeLlmClient（脚本化 LLM 响应、走完整引擎 ReAct 链）互补：本 Fake
 * 用于**事件层**精确控制——验证 TaskRuntime 对特定事件序列的 checkpoint
 * 推导（如压缩兼容、边界落盘），不依赖真实引擎行为。
 *
 * 只存在于 test 源集，生产代码禁止引用。
 */
class ScriptedAgentEngine(
    private val events: List<AgentEvent>,
    /** 事件发完后的挂起时长（模拟"仍在执行中"——崩溃测试防流自然结束）。 */
    private val tailDelayMs: Long = 0L
) : AgentEngine {

    /** 记录注入的任务状态消息（N-9 重注入验证）。 */
    val injectedContexts = mutableListOf<String>()

    /** 记录收到的 execute 输入（恢复提示验证）。 */
    val receivedInputs = mutableListOf<String>()

    /** abort 调用计数。 */
    var abortCount = 0
        private set

    override fun execute(input: String): Flow<AgentEvent> = execute(UserInput.text(input))

    override fun execute(input: UserInput): Flow<AgentEvent> = flow {
        receivedInputs.add(input.text)
        events.forEach { emit(it) }
        if (tailDelayMs > 0) kotlinx.coroutines.delay(tailDelayMs)
    }

    override suspend fun abort() {
        abortCount++
    }

    override fun submitUserInput(answer: String) {}

    override fun cancelUserInput() {}
}
