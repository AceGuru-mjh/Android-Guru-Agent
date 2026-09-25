package com.apex.agent.platform.mcphost

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.catalog.ToolTierPolicy
import kotlinx.coroutines.CancellationException

/**
 * # 工具暴露桥 —— 白名单过滤 + 经注册表执行
 *
 * 外部调用与内部 Agent 调用走**同一条 v3 执行管线**（[ToolExecutor]），
 * 即外部 AI 同样受环境门 / 权限门 / 风险门 / schema 校验 / 熔断 / 限流
 * 的完整约束 —— MCP Host 不旁路任何工具门控。
 *
 * 白名单三重过滤（tools/list 与 tools/call 双向一致，防"列表不含但可调"漏洞）：
 * 1. 分类在 [McpHostConfig.allowedCategories] 内；
 * 2. id 不在 [McpHostConfig.blockedToolIds] 黑名单；
 * 3. 非 legacy 别名（[ToolTierPolicy.isLegacyAlias]）。
 *
 * 额外硬规则（安全红线，**写死**、不受配置影响）：`vault_*` 家族一律禁止
 * 暴露与调用 —— 金库密钥绝不经外部 AI 通道出入，即使把 SECURITY 加入
 * 白名单、从黑名单移除 vault_* 也拦截。
 */
class McpHostBridge(
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val configProvider: () -> McpHostConfig
) {

    /** tools/list 单项：工具名 / 描述 / 输入 schema（JSON 字符串）。 */
    data class HostToolDef(
        val name: String,
        val description: String,
        val inputSchemaJson: String
    )

    /** tools/call 结构化结果（未知/被拦工具在 Server 层转 -32602）。 */
    sealed interface HostToolResult {
        /** 成功：工具输出原样作为 text content。 */
        data class Ok(val text: String) : HostToolResult

        /** 业务失败：isError=true，携带模型可读的错误文本。 */
        data class Err(val text: String) : HostToolResult

        /** 工具不存在 / 未向 MCP Host 暴露（含黑名单、legacy、vault 硬拦截）。 */
        data class UnknownTool(val toolName: String, val reason: String) : HostToolResult
    }

    /** vault_* 硬拦截判定（写死，配置不可放开）。 */
    fun isVaultTool(toolId: String): Boolean = toolId.startsWith("vault_")

    /**
     * 单工具是否允许经 MCP Host 暴露（列表与调用共用同一判定，保证一致）。
     * null = 不允许（返回原因供错误消息使用）。
     */
    fun exposureBlockReason(toolId: String): String? {
        val config = configProvider()
        val tool = registry.getTool(toolId) ?: return "not registered"
        if (isVaultTool(tool.id)) {
            return "vault tools are never exposed via MCP Host (security vault is local-only)"
        }
        if (ToolTierPolicy.isLegacyAlias(tool.id)) {
            return "legacy alias tools are not exposed"
        }
        if (config.isToolBlocked(tool.id)) {
            return "blocked by MCP Host tool blacklist"
        }
        if (!config.isCategoryAllowed(tool.metadata.category.name)) {
            return "tool category '${tool.metadata.category.name}' is not in the allowed list"
        }
        return null
    }

    /** 白名单内工具清单（按 id 稳定排序；inputSchema 为渲染 JSON 字符串）。 */
    fun toolDefinitions(): List<HostToolDef> {
        val config = configProvider()
        return registry.getAllTools()
            .asSequence()
            .filter { !isVaultTool(it.id) }
            .filter { !ToolTierPolicy.isLegacyAlias(it.id) }
            .filter { it.id !in config.blockedToolIds }
            .filter { config.isCategoryAllowed(it.metadata.category.name) }
            .sortedBy { it.id }
            .map { it.toHostDef() }
            .toList()
    }

    private fun AgentTool.toHostDef(): HostToolDef = HostToolDef(
        name = id,
        description = description,
        inputSchemaJson = parametersSchema
    )

    /**
     * 经 v3 执行管线调用工具（含权限门）。
     *
     * - 白名单外的工具（含 vault_* 硬拦截）→ [HostToolResult.UnknownTool]；
     * - 工具结果以 "Error" 开头 → [HostToolResult.Err]（isError=true）；
     *   权限门拒绝（Ask/Deny —— 外部无人答复弹窗）时替换为面向外部客户端的
     *   明确指引文本；
     * - 其余原样作为 [HostToolResult.Ok]。
     */
    suspend fun callTool(name: String, arguments: String): HostToolResult {
        exposureBlockReason(name)?.let { return HostToolResult.UnknownTool(name, it) }

        val result = try {
            executor.execute(name, arguments.ifBlank { "{}" })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return HostToolResult.Err("Error: tool execution failed: ${e.message ?: e::class.simpleName}")
        }

        return if (result.startsWith("Error")) {
            HostToolResult.Err(humanizeFailure(result))
        } else {
            HostToolResult.Ok(result)
        }
    }

    /**
     * 失败文本人性化：权限门 / 命令门拒绝在 MCP Host 场景下统一替换为
     * 「此工具需要交互式授权，MCP Host 模式下不可用」语义的英文消息
     * （错误消息面向外部客户端，按项目约定用英文）。
     */
    private fun humanizeFailure(raw: String): String {
        val lower = raw.lowercase()
        val isPermissionRejection = lower.startsWith("error: permission denied") ||
            raw.startsWith("Error: 权限不足") ||
            raw.startsWith("Error: 用户拒绝")
        return if (isPermissionRejection) {
            "Error: this tool requires interactive authorization on the device, " +
                "which is unavailable through MCP Host. Do not retry; " +
                "perform the action directly on the phone or choose a different approach."
        } else {
            raw
        }
    }
}
