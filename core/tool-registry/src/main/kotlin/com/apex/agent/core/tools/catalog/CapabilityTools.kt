package com.apex.agent.core.tools.catalog

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolEnvironmentState
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.marketplace.HubSource
import com.apex.agent.core.tools.toolSchema
import com.apex.agent.core.tools.builtin.BaseTool

/**
 * # Capability Introspection — 自省元工具
 *
 * 根因（「agent 不懂自己能干什么」）：
 * - `tool_list` 只覆盖工具注册表，看不到权限/环境/技能/MCP 全貌；
 * - 市场源（HubSource 等 7 源）只服务 UI 市场，agent 侧完全不可见——
 *   「不知道自己有什么工具可以安装什么」；
 * - 没有「先自省、再扩展」的第一反动作。
 *
 * 两个新元工具补齐闭环：
 * - [CapabilityReportTool]（`capability_report`）——一键自省：权限阶梯
 *   （CAN/CANNOT/升级路径，与系统提示词同源 [PrivilegeLadder]）、实时
 *   环境快照（与执行侧环境门同源 [ToolEnvironmentState]）、工具/技能/
 *   MCP 库存 + 扩展梯度；
 * - [MarketSearchTool]（`market_search`）——检索官方市场（技能 hub +
 *   MCP hub 双目录），返回条目与安装指令（skill_install / mcp_connect）。
 *
 * 依赖注入契约：计数/等级经构造 lambda 注入（纯 JVM 可测，无 Android
 * 依赖）；未知计数传默认 -1，输出时整行省略（fail-open，不教模型假事实）。
 */
class CapabilityReportTool(
    private val registry: ToolRegistry,
    private val environmentState: ToolEnvironmentState,
    /** 当前权限等级（"ROOT"/"SHIZUKU"/"NORMAL_SHELL"；未知折叠见 [PrivilegeLadder.normalize]）。 */
    private val privilegeLevel: () -> String = { PrivilegeLadder.LEVEL_NORMAL_SHELL },
    /** 已安装技能数（-1 = 未知，整行省略）。 */
    private val installedSkillCount: () -> Int = { -1 },
    /** 已配置 MCP 服务器数（-1 = 未知，整行省略）。 */
    private val configuredMcpCount: () -> Int = { -1 },
    /** 已连接 MCP 服务器数（-1 = 未知，仅省略连接数部分）。 */
    private val connectedMcpCount: () -> Int = { -1 },
    /**
     * GitHub 连接快照（v3 S3）：`"login|repo"`（repo 为 `-` = 未设置默认
     * 仓库）/ null = 未连接。null 默认值 = GitHub 行整段省略（既有构造点
     * 零改动，向后兼容）；core 经 lambda 拿快照，不依赖 app 层
     * GithubTokenManager，保持纯 JVM 可测。
     */
    private val githubStateProvider: (() -> String?)? = null
) : BaseTool(
    id = "capability_report",
    name = "Capability Report",
    description = """
        One-call self-check of everything you can do right now: privilege
        level (with CAN/CANNOT lists and the upgrade path), live environment
        flags, tool inventory (loaded vs catalog), installed skills, and MCP
        server status. Ends with the expansion ladder for acquiring missing
        capabilities. Call this FIRST when unsure whether something is
        possible, when a capability question comes up, or right after a
        permission error — instead of guessing or giving up.
    """.trimIndent(),
    declaredSchema = toolSchema { }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("introspection", "capability", "privilege")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val info = PrivilegeLadder.infoFor(privilegeLevel())

        val allTools = registry.getAllTools()
            .filterNot { ToolTierPolicy.isLegacyAlias(it.id) }
        val coreCount = allTools.count { ToolTierPolicy.isCore(it.id) }
        val catalogCount = allTools.size - coreCount

        val skills = installedSkillCount()
        val mcpConfigured = configuredMcpCount()
        val mcpConnected = connectedMcpCount()

        return ToolResult.ok(
            buildString {
                appendLine("== Capability Report ==")
                appendLine("Privilege: ${info.summary}")
                appendLine("- CAN: ${info.can.joinToString("; ")}.")
                if (info.cannot.isNotEmpty()) {
                    appendLine("- CANNOT: ${info.cannot.joinToString("; ")}.")
                }
                appendLine("- Upgrade: ${info.upgradeHint}")
                appendLine()
                appendLine("Environment: ${environmentState.summary()}")
                appendLine(
                    "Tools: ${allTools.size} registered — $coreCount loaded by default, " +
                        "$catalogCount more via tool_search(query) → tool_open(tool_name)."
                )
                if (skills >= 0) {
                    appendLine(
                        "Skills: $skills installed — skill_list() to browse; " +
                            "market_search()/skill_search() to find and skill_install to add more."
                    )
                }
                if (mcpConfigured >= 0) {
                    val connectedPart = if (mcpConnected >= 0) ", $mcpConnected connected" else ""
                    appendLine(
                        "MCP servers: $mcpConfigured configured$connectedPart — mcp_list() to " +
                            "browse; market_search(kind=\"mcp\")/mcp_connect() to add more."
                    )
                }
                // v3 S3：GitHub 连接行（宿主未注入 provider 时整行省略——
                // 纯 JVM 测试/既有构造点零变化）。快照解析防御式：lambda 抛
                // 异常或格式异常折叠为 not connected（不教模型假事实）。
                if (githubStateProvider != null) {
                    val snapshot = runCatching { githubStateProvider() }.getOrNull()
                    val parts = snapshot?.split('|', limit = 2)
                    val login = parts?.getOrNull(0)?.takeIf { it.isNotBlank() }
                    if (login != null) {
                        val repo = parts.getOrNull(1)
                            ?.takeUnless { it.isNullOrBlank() || it == "-" }
                        val repoPart = if (repo != null) " (default repo: $repo)" else ""
                        appendLine("GitHub: connected as $login$repoPart — github_* tools ready.")
                    } else {
                        appendLine("GitHub: not connected — github_* tools need a PAT (ask the user).")
                    }
                }
                // v3 双工位作用域（事实性描述）：本工具不感知设置开关
                // （AgentSettings.mcpScopeIsolation），只陈述引擎计划层的
                // 隔离机制本身；开关语义见 docs/scope-isolation.md。
                appendLine(
                    "Workspace isolation: scope enforcement at engine plan level " +
                        "(per workspace: agent/coding/all)."
                )
                appendLine()
                appendLine("Expansion ladder (when a capability is missing):")
                appendLine("1. tool_search(query) → tool_open(tool_name) — any registered tool")
                appendLine("2. market_search(query) → skill_install → skill_activate — skills from the official hub")
                appendLine("3. market_search(kind=\"mcp\") → mcp_connect — external MCP servers")
                appendLine("4. terminal.backends → terminal.ubuntu.ensure → apt/pip/npm — full Linux toolchain")
                appendLine("5. Privilege wall → ask the user to raise the level (see upgrade hint above)")
                appendLine("Never claim a task is impossible before trying these routes.")
            }.trim()
        )
    }
}

/**
 * 官方市场检索（技能 hub + MCP hub 双目录，数据源 [HubSource]）。
 *
 * 构造注入 fetch lambda（而非 HubSource 实例）：市场目录拉取是网络调用，
 * 纯 JVM 单测以固定目录数据注入（仓库纪律：测试不联网）。
 */
class MarketSearchTool(
    private val fetchSkills: suspend () -> Result<List<HubSource.HubSkillEntry>>,
    private val fetchMcpServers: suspend () -> Result<List<HubSource.HubMcpEntry>>
) : BaseTool(
    id = "market_search",
    name = "Market Search",
    description = """
        Search the official marketplace for capabilities you don't have yet.
        kind="skill" — Apex Skill Hub (domain methodologies & tool bundles);
        install hits with skill_install({"source":"url", ...}).
        kind="mcp" — MCP Hub (external tool servers); wire hits with
        mcp_connect (remote url, or npx command in the Ubuntu sandbox).
        kind="all" (default) searches both. Use this whenever installed
        tools/skills cannot do the job — the missing capability may be one
        install away.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "query",
            required = true,
            description = "Keywords to match against entry name/description/tags " +
                "(e.g. 'youtube download', 'image generation', 'filesystem')"
        )
        string(
            "kind",
            description = "'skill' | 'mcp' | 'all' (default all)"
        )
        integer(
            "limit",
            description = "Max results per kind (1..10, default 5)",
            minimum = 1.0,
            maximum = 10.0
        )
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("marketplace", "discovery", "install")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val query = args.requireString("query").trim()
        if (query.isEmpty()) {
            return ToolResult.invalid(
                field = "query",
                message = "query must not be empty",
                suggestion = "give 1-3 keywords, e.g. 'youtube download'"
            )
        }
        val kind = (args.optionalString("kind") ?: "all").trim().lowercase()
        if (kind !in setOf("skill", "mcp", "all")) {
            return ToolResult.invalid(
                field = "kind",
                message = "unknown kind '$kind'",
                suggestion = "use 'skill', 'mcp' or 'all'"
            )
        }
        val limit = (args.optionalInt("limit") ?: 5).coerceIn(1, 10)
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

        val sb = StringBuilder()
        var anySourceFailed = false
        var total = 0

        if (kind == "all" || kind == "skill") {
            val entries = fetchSkills().getOrNull()
            if (entries != null) {
                val hits = entries.scored(terms).take(limit)
                total += hits.size
                renderSkills(sb, hits)
            } else {
                anySourceFailed = true
            }
        }
        if (kind == "all" || kind == "mcp") {
            val entries = fetchMcpServers().getOrNull()
            if (entries != null) {
                val hits = entries.scored(terms).take(limit)
                total += hits.size
                renderMcp(sb, hits)
            } else {
                anySourceFailed = true
            }
        }

        if (total == 0) {
            return if (anySourceFailed) {
                ToolResult.fail(
                    ToolErrorCode.EXECUTION_FAILED,
                    "Marketplace unreachable (network error) — check connectivity, retry " +
                        "later, or find skills/MCP servers with web_search and install via " +
                        "skill_install(url) / mcp_connect."
                )
            } else {
                ToolResult.ok(
                    "No marketplace entries matched '$query'. Try broader keywords, or " +
                        "create your own with skill_create / terminal toolchains."
                )
            }
        }
        if (anySourceFailed) {
            sb.appendLine()
            sb.appendLine("(One marketplace source was unreachable; results may be partial.)")
        }
        return ToolResult.ok(sb.toString().trim())
    }

    // ── 渲染 ──

    private fun renderSkills(sb: StringBuilder, hits: List<HubSource.HubSkillEntry>) {
        sb.appendLine("== Skills (official Apex Skill Hub) ==")
        hits.forEach { entry ->
            val meta = listOfNotNull(
                entry.category?.takeIf { it.isNotBlank() },
                "v${entry.version}"
            ).joinToString(", ").ifEmpty { "v${entry.version}" }
            sb.appendLine("- ${entry.name} ($meta): ${entry.description.ifBlank { "(no description)" }}")
            sb.appendLine(
                "  Install: skill_install({\"source\":\"url\",\"url\":\"" +
                    "${HubSource.skillManifestUrl(entry)}\"})"
            )
        }
        sb.appendLine("After install, skill_activate(skill_id) loads the methodology.")
        sb.appendLine()
    }

    private fun renderMcp(sb: StringBuilder, hits: List<HubSource.HubMcpEntry>) {
        sb.appendLine("== MCP servers (official Apex MCP Hub) ==")
        hits.forEach { entry ->
            val transport = entry.transport.uppercase().ifBlank { "HTTP" }
            sb.appendLine("- ${entry.name} [$transport]: ${entry.description.ifBlank { "(no description)" }}")
            when (transport) {
                "STDIO" -> {
                    val cmd = entry.command ?: "npx"
                    val argsPart = entry.args.joinToString("", prefix = " ") { "\"$it\"" }
                    val sandbox = if (entry.runInSandbox) ", \"run_in_sandbox\": true" else ""
                    sb.appendLine(
                        "  Wire: mcp_connect({\"name\":\"${entry.name}\"," +
                            "\"command\":\"$cmd\",\"args\":[$argsPart]$sandbox})"
                    )
                }
                else -> {
                    sb.appendLine(
                        "  Wire: mcp_connect({\"name\":\"${entry.name}\",\"url\":\"${entry.url}\"})"
                    )
                }
            }
        }
        sb.appendLine("After connecting, mcp_list() shows the tools the server provides.")
        sb.appendLine()
    }

    // ── 打分（与 tool_search 同款朴素评分：多词 OR 命中，全等 > 前缀 > 包含）──

    private fun <T> List<T>.scored(
        terms: List<String>
    ): List<T> where T : Any = this
        .map { entry ->
            val haystack = haystackOf(entry).lowercase()
            var score = 0
            for (term in terms) {
                when {
                    haystack == term -> score += 10
                    haystack.startsWith(term) -> score += 6
                    haystack.contains(term) -> score += 4
                }
            }
            entry to score
        }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { nameOf(it.first) })
        .map { it.first }

    private fun haystackOf(entry: Any): String = when (entry) {
        is HubSource.HubSkillEntry ->
            "${entry.id} ${entry.name} ${entry.description} ${entry.tags.joinToString(" ")}"
        is HubSource.HubMcpEntry ->
            "${entry.name} ${entry.description} ${entry.tags.joinToString(" ")} ${entry.vendor}"
        else -> entry.toString()
    }

    private fun nameOf(entry: Any): String = when (entry) {
        is HubSource.HubSkillEntry -> entry.name
        is HubSource.HubMcpEntry -> entry.name
        else -> entry.toString()
    }
}
