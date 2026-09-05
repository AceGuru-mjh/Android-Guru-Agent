package com.apex.agent.core.code

/**
 * Code Agent 的 JIT 上下文提供器接口（Spec §41/§42）。
 *
 * 不要把整个 repository 塞进 LLM context。每次 [provide] 根据当前任务 +
 * 当前活跃文件 + 选区 + diagnostics + git diff + build 状态，组装一段
 * concise 的 "## Code Context" 注入 Code engine 的 [AgentConfig.additionalSystemContext]。
 *
 * 实现见 [com.apex.agent.platform.code.intel.CodeContextProviderImpl]
 * （依赖 LSP / git / build runner / problems aggregator，故在 Android 层）。
 */
interface CodeContextProvider {

    /**
     * 组装当前 Code 上下文快照。
     * @param workspaceId 当前 workspace
     * @param activeFile 当前打开的文件（相对 workspace root），可空
     * @param selection 选区行范围，可空
     * @param task 用户本轮任务文本
     * @return 注入 system prompt 的 "## Code Context" 段落；空串表示无额外上下文
     */
    suspend fun provide(
        workspaceId: String,
        activeFile: String? = null,
        selection: IntRange? = null,
        task: String = ""
    ): String

    /** 被编辑触发的上下文失效（diagnostics 重发、git diff 变化）。 */
    fun invalidate(workspaceId: String)
}

/**
 * 默认空实现：不注入任何 Code 上下文。用于 Code engine 在 LSP/git 未就绪时的 fallback，
 * 以及纯 JVM 单测。
 */
class NoOpCodeContextProvider : CodeContextProvider {
    override suspend fun provide(workspaceId: String, activeFile: String?, selection: IntRange?, task: String): String = ""
    override fun invalidate(workspaceId: String) {}
}
