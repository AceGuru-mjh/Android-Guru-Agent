package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmException
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolChoiceSpec
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.catalog.ToolActivationStore
import com.apex.agent.core.tools.catalog.ToolRequestBudget
import com.apex.agent.core.tools.catalog.ToolTierPolicy

/**
 * # Tool System v4 — engine-side pure planner
 *
 * Extracted from [ApexAgentEngine] (God-file 1200-line budget): every
 * function here is **pure** (state enters as parameters, no engine fields),
 * so the engine loop stays a thin orchestrator and this file is unit-testable
 * in isolation.
 *
 * Responsibilities:
 * - per-iteration tool plan (forced / default / degraded);
 * - forced tool_choice spec (required / specific function);
 * - tools-related rejection classification (degradation trigger);
 * - provider-name ⇆ registry-id routing for execution;
 * - system-prompt tool/catalog/id-name views (same source of truth as the
 *   request tools array).
 */
internal object EngineToolPlanner {

    /** 降级等级：0=正常 / 1=纯 CORE / 2=无工具纯对话。 */
    const val DEGRADATION_CORE_ONLY = 1
    const val DEGRADATION_NO_TOOLS = 2

    val TOOLS_REJECTION_KEYWORDS = listOf(
        "tool", "function", "schema", "parameters", "parallel_tool"
    )

    val EMPTY_TOOL_PLAN = ToolRequestBudget.RequestToolPlan(
        tools = emptyList(),
        providerNameToId = emptyMap(),
        visibleRegistryIds = emptySet(),
        totalBytes = 0,
        droppedByBudget = emptyList()
    )

    /**
     * 本轮请求工具计划：强制（仅选中集）/ 默认（CORE+激活+连接服务+可选全量）/
     * 降级（≥1=纯 CORE；≥2=空）。
     *
     * @param serviceToolIds 已连接服务对应的工具 id（ConnectedServicesProvider
     *        .connectedToolIds）—— 与 CORE 同待遇进请求。降级路径不并入
     *        （coreOnly 的语义就是收窄回最小集；空集 = 零行为变化）。
     */
    fun buildToolPlan(
        config: AgentConfig,
        registry: ToolRegistry,
        activation: ToolActivationStore,
        degradationLevel: Int,
        serviceToolIds: Set<String> = emptySet()
    ): ToolRequestBudget.RequestToolPlan {
        val forced = config.forcedToolIds
        val allowed = config.allowedToolIds
        return when {
            degradationLevel >= DEGRADATION_NO_TOOLS -> EMPTY_TOOL_PLAN
            forced.isNotEmpty() && degradationLevel == 0 ->
                ToolRequestBudget.planForced(registry, forced)
            // #147 子代理：仅收窄工具集，不附带 tool_choice=required —— 子代理的
            // 最终轮需要能输出纯文本结论（forced 语义会迫使每轮调用工具）。
            allowed.isNotEmpty() && degradationLevel == 0 ->
                ToolRequestBudget.planForced(registry, allowed)
            else -> ToolRequestBudget.planDefault(
                registry = registry,
                activation = activation,
                exposeAll = config.exposeAllTools && degradationLevel == 0,
                coreOnly = degradationLevel >= DEGRADATION_CORE_ONLY,
                serviceToolIds = if (degradationLevel == 0) serviceToolIds else emptySet()
            )
        }
    }

    /** 强制 tool_choice：单选=具体函数，多选=required；降级后不再强制。 */
    fun forcedToolChoiceSpec(
        forcedToolIds: Set<String>,
        plan: ToolRequestBudget.RequestToolPlan,
        degradationLevel: Int
    ): ToolChoiceSpec? {
        if (forcedToolIds.isEmpty() || degradationLevel > 0) return null
        if (plan.tools.isEmpty()) return null
        return if (plan.tools.size == 1) {
            ToolChoiceSpec.Function(plan.tools.first().name)
        } else {
            ToolChoiceSpec.Required
        }
    }

    /**
     * 判定异常是否与工具负载相关（降级重试的前置条件）。
     *
     * 覆盖：①模型/网关拒绝带 tools 请求的典型报错文案（函数名/schema/
     * tool_choice/parallel）；②400/413/422 请求级拒绝且报错文案命中工具关键词。
     *
     * P1 修复（降级误判）：旧实现把**任何** HTTP 400/413/422 一律判为“工具被拒”
     * —— 但请求体里还有 temperature / reasoning_effort / parallel_tool_calls /
     * 图片载荷等大量可 400/413 的字段。误判一次 → 降级 1（纯 CORE），再误判
     * → 降级 2 = 本任务余下轮次完全无工具（模型“突然只用嘴回答”）。
     * 现在状态码命中后**还要求报错文案命中工具关键词**才降级；非工具问题
     * 的 4xx 原样上抛（诚实暴露真实错误，而不是静默阉割工具集）。
     * 401/403（鉴权）始终排除。
     */
    fun isToolsRelatedRejection(e: Throwable): Boolean {
        val msg = (e.message ?: "").lowercase()
        val msgHitsToolKeyword = TOOLS_REJECTION_KEYWORDS.any { msg.contains(it) }
        if (e is LlmException.Http) {
            // 413（载荷超限）：报错体常为空 —— 保留「降级探测」语义（去掉最大
            // 载荷成分重试一次；若非工具问题，降级后同样报错并上抛，不吞错）。
            if (e.code == 413) return true
            // 400/422（校验失败）：请求体里 temperature / reasoning_effort /
            // parallel_tool_calls / 图片等同样可触发 —— 必须文案命中工具关键词
            // 才降级，否则原样上抛（诚实暴露真实错误，不静默阉割工具集）。
            if (e.code == 400 || e.code == 422) return msgHitsToolKeyword
        }
        // 非 HTTP 异常：文案关键词判定（历史行为）。
        return msgHitsToolKeyword
    }

    /** 模型回显的工具名 → 注册表 id（无映射时原样返回——registry id 直查）。 */
    fun registryIdOf(
        plan: ToolRequestBudget.RequestToolPlan?,
        providerName: String
    ): String = plan?.providerNameToId?.get(providerName) ?: providerName

    /** 系统提示词工具清单视图：与请求 tools 数组同源（计划内工具）。 */
    fun visibleToolsFor(
        plan: ToolRequestBudget.RequestToolPlan?,
        registry: ToolRegistry
    ): List<AgentTool> {
        if (plan == null) return registry.getAllTools()
        return registry.getAllTools().filter { it.id in plan.visibleRegistryIds }
    }

    /** 目录总览视图：全部非 legacy 工具（仅在请求携带工具时展示目录段）。 */
    fun catalogToolsFor(
        plan: ToolRequestBudget.RequestToolPlan?,
        registry: ToolRegistry
    ): List<AgentTool> {
        return plan?.tools?.takeIf { it.isNotEmpty() }?.let {
            registry.getAllTools().filterNot { tool ->
                ToolTierPolicy.isLegacyAlias(tool.id)
            }
        } ?: emptyList()
    }

    /** id → provider 名映射（提示词工具清单与请求 tools 数组同名）。 */
    fun idToProviderName(plan: ToolRequestBudget.RequestToolPlan?): Map<String, String> =
        plan?.providerNameToId?.entries?.associate { (name, id) -> id to name } ?: emptyMap()
}

// ── 纯输入/消息辅助（自引擎迁出，同包直调）────────────────────────────

/**
 * 多模态用户输入 → 文本上下文（图片/文件以路径清单形式告知 Agent，
 * Agent 可用 read_file 等工具读取）。纯函数，无引擎状态。
 */
internal fun buildUserText(input: UserInput): String {
    if (input.images.isEmpty() && input.files.isEmpty()) return input.text
    return buildString {
        if (input.images.isNotEmpty()) {
            appendLine("[用户附加了 ${input.images.size} 张图片]")
        }
        if (input.files.isNotEmpty()) {
            appendLine("[用户附加文件]")
            input.files.forEach { f ->
                // 文档类附件标注「已支持文本提取」——引导模型放心用 read_file 读正文，
                // 而不是看到 .pdf 就当作二进制放弃（旧链路确实读不了，现在能读）。
                val docHint = when {
                    f.mimeType.contains("pdf", ignoreCase = true) || f.name.endsWith(".pdf", true) -> "，read_file 可提取文本"
                    f.mimeType.contains("wordprocessingml", ignoreCase = true) || f.name.endsWith(".docx", true) -> "，read_file 可提取文本"
                    else -> ""
                }
                appendLine("- ${f.name} (${f.mimeType}, ${f.sizeBytes} bytes$docHint) path=${f.localPath}")
            }
        }
        appendLine()
        append("用户消息: ")
        append(input.text)
    }
}

/** 判定当前消息列表是否含图片（用户附件 / 历史 Vision 上下文）。纯函数。 */
internal fun messagesContainImages(messages: List<LlmMessage>): Boolean =
    messages.any { it is LlmMessage.User && it.images.isNotEmpty() }

/**
 * 提取最近一条用户消息文本（#168 AUTO 档复杂度分类的输入信号）。
 *
 * 自消息列表尾部向前找第一条 [LlmMessage.User]——ReAct 循环中工具结果/
 * 助手消息会不断追加，用户原始诉求始终是最后一条 User 消息。
 * 无用户消息（如 TaskRuntime 注入的 system-only 恢复路径）返回 null。
 */
internal fun lastUserPromptText(messages: List<LlmMessage>): String? =
    (messages.lastOrNull { it is LlmMessage.User } as? LlmMessage.User)?.content
