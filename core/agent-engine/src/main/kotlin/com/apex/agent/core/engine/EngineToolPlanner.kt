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
     * 本轮请求工具计划：强制（仅选中集）/ 默认（CORE+激活+可选全量）/
     * 降级（≥1=纯 CORE；≥2=空）。
     */
    fun buildToolPlan(
        config: AgentConfig,
        registry: ToolRegistry,
        activation: ToolActivationStore,
        degradationLevel: Int
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
                coreOnly = degradationLevel >= DEGRADATION_CORE_ONLY
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
     * tool_choice/parallel）；②400/413/422 请求级拒绝——请求体里只有
     * tools 是 v4 新变量，用它当降级信号；若非工具问题，降级后仍会在
     * 同一轮报错并上抛，不吞错。401/403（鉴权）明确排除。
     */
    fun isToolsRelatedRejection(e: Throwable): Boolean {
        if (e is LlmException.Http && (e.code == 400 || e.code == 413 || e.code == 422)) {
            return true
        }
        val msg = (e.message ?: "").lowercase()
        return TOOLS_REJECTION_KEYWORDS.any { msg.contains(it) }
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
                appendLine("- ${f.name} (${f.mimeType}, ${f.sizeBytes} bytes) path=${f.localPath}")
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
