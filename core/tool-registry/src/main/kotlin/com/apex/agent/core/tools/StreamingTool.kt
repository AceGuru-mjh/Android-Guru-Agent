package com.apex.agent.core.tools

import kotlinx.coroutines.flow.Flow

/**
 * 工具流式输出事件。
 *
 * 一个流式工具在 [StreamingAgentTool.executeStream] 中发射零或多个
 * [Output] / [Progress] 事件，最后以一个 [Complete]（成功）或 [Error]
 * （失败）收尾。终端事件是可选的：Flow 自然结束也是合法的终止信号
 * （engine 把已累积的 [Output] 作为最终结果）。
 *
 * 该 sealed interface 镜像 engine 层的 `AgentEvent.ToolOutputChunk` /
 * `ToolProgress` / `ToolCallComplete`，但留在纯 JVM 的 tool-registry 模块，
 * 使工具可独立单测、无需 agent engine 或 Android 运行时。
 */
sealed interface ToolStreamEvent {

    /**
     * 普通输出片段。多个片段由 engine 拼接，并逐段转成
     * `AgentEvent.ToolOutputChunk` 推送到 UI，让用户在工具执行期间就能看到
     * 实时输出（而非等工具完成后一次性显示）。
     */
    data class Output(val chunk: String) : ToolStreamEvent

    /**
     * 进度事件。用于长时间运行、能估算完成度的工具（如下载、大文件处理）。
     * [percent] 为 0..1；[message] 为可选的人类可读说明。
     * engine 把它转成 `AgentEvent.ToolProgress`，UI 显示进度条。
     */
    data class Progress(
        val percent: Float? = null,
        val message: String? = null
    ) : ToolStreamEvent

    /**
     * 工具正常完成。[output] 携带完整最终输出；当已发过 [Output] 片段时，
     * engine 忽略 [output]（以累积值为准），仅把它当作“成功”的终端信号。
     * 仅当工具完全没发 [Output]（非典型）时，engine 才把 [output] 补发一次。
     */
    data class Complete(val output: String) : ToolStreamEvent

    /**
     * 工具失败。[message] 被追加到累积输出，engine 据此把工具调用标记为失败
     * （success=false）。
     */
    data class Error(val message: String) : ToolStreamEvent
}

/**
 * 支持流式输出的工具接口（可选能力）。
 *
 * 能增量产出输出的工具（如 `shell_execute` 逐行读取进程 stdout）在实现
 * [AgentTool] 之外再实现本接口。[ToolExecutor] 在运行时通过 `tool is
 * StreamingAgentTool` 检测：命中则走 [executeStream]，未命中则把阻塞的
 * [AgentTool.execute] 结果透明包装成 “单个 Output + Complete” 事件序列。
 *
 * 因此实现本接口是工具获得实时 UI 输出的**唯一**改动点 —— 无需改 engine
 * 或 ViewModel。实现 [executeStream] 时应：
 * - 产出输出时发 [ToolStreamEvent.Output]；
 * - 以 [ToolStreamEvent.Complete]（或 [ToolStreamEvent.Error]）收尾；
 * - 响应协程取消，使 `abort()` 能停止底层工作（如 `Process.destroy()`）。
 *
 * 返回的 [Flow] 由 executor 在 IO 调度器上收集。
 */
interface StreamingAgentTool : AgentTool {

    /**
     * 流式执行工具。
     */
    fun executeStream(arguments: String): Flow<ToolStreamEvent>
}
