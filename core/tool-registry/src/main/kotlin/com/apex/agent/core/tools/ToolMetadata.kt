package com.apex.agent.core.tools

/**
 * Functional category of a tool. Order defines the display order in prompts
 * and menus (most-relevant agent surface first).
 */
enum class ToolCategory(
    /** Human-readable label shown in prompts and menus (Chinese UI). */
    val label: String,
    /** Stable sort key — lower sorts earlier. */
    val order: Int
) {
    /** Local shell command execution (`shell_execute`). */
    SHELL("Shell 执行", 10),

    /** Sandboxed file tree operations (read/write/edit/glob/search). */
    FILE("文件操作", 20),

    /** ATR 2.0 terminal runtime (`terminal.*`). */
    TERMINAL("终端运行时", 30),

    /** Plain HTTP networking (fetch/search/request/download). */
    WEB("网络访问", 40),

    /** Built-in browser automation (DOM-level page control). */
    BROWSER("浏览器自动化", 50),

    /** Long-term memory recall (CS-Mem). */
    MEMORY("记忆", 60),

    /** In-session context review tools (`context_*` / `session_stats`) — #172. */
    CONTEXT("上下文回顾", 62),

    /** Installed app management (list/launch/install/uninstall). */
    APP("应用管理", 70),

    /** Device/system state control (settings/clipboard/logcat/time). */
    SYSTEM("系统控制", 80),

    /** Encrypted clipboard vault (`vault_*`) — secret storage & blind paste. */
    SECURITY("安全金库", 85),

    /** Accessibility/UI-tree interaction (tap/swipe/dump/input). */
    UI("界面操作", 90),

    /** Device sensors and environment (location/notifications). */
    SENSOR("传感器", 100),

    /** Agent-initiated user interaction (ask_user / ask_user_choice). */
    AGENT("用户交互", 110),

    /** Skill marketplace & installed skill tools (`skill_*` + composites). */
    SKILL("技能", 120),

    /** MCP server tools (`mcp_*`). */
    MCP("MCP", 130),

    /** GitHub API connector tools (`github_*`). */
    GITHUB("GitHub", 140),

    /** Hosted plugin tools (`plugin_*`). */
    PLUGIN("插件", 150),

    /** Pure-JVM data/text/time utilities (deterministic, no side effects). */
    UTILITY("实用工具", 160);

    companion object {
        /** Categories in display order (prompt/menu rendering). */
        fun inDisplayOrder(): List<ToolCategory> = entries.sortedBy { it.order }
    }
}

/**
 * #206 工具域 —— 18 个细分类之上的 7 域分组（选择器三级结构：域 → 类 → 工具）。
 *
 * **为什么**：Coding 屏的函数调用选择器直接平铺 18 个类，一屏放不下且认知
 * 负担大；域层把同类能力聚合（执行与系统 / 代码与文件 / 网络与信息 / 记忆
 * 与上下文 / 交互与界面 / 扩展生态 / 安全与工具），先选域再选类再选工具。
 *
 * **单一事实源**：类 → 域的归属只存在于 [domainOf] 的穷举 when 里，
 * [categories] 是它的反向派生（不会出现两边清单漂移）；新增 ToolCategory
 * 时编译器强制补映射（when 穷举）。
 */
enum class ToolDomain(
    /** 域的中文标签（选择器一级菜单）。 */
    val label: String,
    /** 域的展示顺序。 */
    val order: Int
) {
    /** 命令执行、终端、系统与应用控制、传感器。 */
    EXECUTION("执行与系统", 10),

    /** 文件树操作与代码托管连接。 */
    CODE_AND_FILES("代码与文件", 20),

    /** HTTP 网络与浏览器自动化。 */
    WEB_AND_INFO("网络与信息", 30),

    /** 长期记忆与会话上下文回顾。 */
    MEMORY_AND_CONTEXT("记忆与上下文", 40),

    /** 面向用户的交互与界面自动化。 */
    INTERACTION("交互与界面", 50),

    /** 技能 / MCP / 插件生态。 */
    ECOSYSTEM("扩展生态", 60),

    /** 安全金库与确定性实用工具。 */
    SAFETY_AND_UTILITIES("安全与工具", 70);

    /** 该域包含的工具类（domainOf 的反向派生，按 ToolCategory 自身顺序）。 */
    val categories: List<ToolCategory>
        get() = ToolCategory.inDisplayOrder().filter { domainOf(it) == this }

    companion object {
        /** 域按展示顺序。 */
        fun inDisplayOrder(): List<ToolDomain> = entries.sortedBy { it.order }

        /** 类 → 域的归属（穷举映射：新增 ToolCategory 必须在这里补归属）。 */
        fun domainOf(category: ToolCategory): ToolDomain = when (category) {
            ToolCategory.SHELL, ToolCategory.TERMINAL, ToolCategory.SYSTEM,
            ToolCategory.APP, ToolCategory.SENSOR -> EXECUTION
            ToolCategory.FILE, ToolCategory.GITHUB -> CODE_AND_FILES
            ToolCategory.WEB, ToolCategory.BROWSER -> WEB_AND_INFO
            ToolCategory.MEMORY, ToolCategory.CONTEXT -> MEMORY_AND_CONTEXT
            ToolCategory.AGENT, ToolCategory.UI -> INTERACTION
            ToolCategory.SKILL, ToolCategory.MCP, ToolCategory.PLUGIN -> ECOSYSTEM
            ToolCategory.SECURITY, ToolCategory.UTILITY -> SAFETY_AND_UTILITIES
        }
    }
}

/**
 * Risk class of invoking a tool. Drives the v2 execution gate: HIGH-risk
 * tools prompt the user for a session-scoped approval on first use; MEDIUM
 * and LOW tools execute directly (the engine's existing command-level gate
 * still applies to shell commands inside those tools).
 */
enum class ToolRisk(val label: String) {
    /** Read-only or pure computation — no approval ever needed. */
    LOW("低"),

    /** Reads/writes within the sandbox, or executes non-destructive commands. */
    MEDIUM("中"),

    /**
     * Destructive, irreversible, or system-wide effects. The gate asks the
     * user once per session per tool unless the tool is marked `selfGated`
     * (it already runs its own, finer-grained confirmation flow — e.g.
     * `shell_execute` routes through [CommandPermissionGate]).
     */
    HIGH("高");
}

/**
 * Metadata for a single tool.
 *
 * @param id tool id (kept in the data class so a [ToolMetadata] alone fully
 *   identifies its tool — snapshots and reports stay self-describing).
 * @param category functional category.
 * @param risk invocation risk.
 * @param tags free-form lowercase tags for search (e.g. "json", "parse").
 * @param annotations v3 MCP-aligned behavioural hints (readOnly /
 *   destructive / idempotent / openWorld / sensitive). Defaults to
 *   [ToolAnnotations.infer] over id + risk so every existing tool gets
 *   hints without code changes; explicit declaration wins.
 * @param scope v3 workspace scope ("agent" | "coding" | "all", default
 *   "all"): which screen this tool is visible on. Sourced from the SAME
 *   field the market tiers use (McpServerConfig.scope / skill manifest
 *   scope) at registration time; consumed by EngineToolPlanner to enforce
 *   mode-level isolation. "all" = both workspaces (every built-in tool
 *   default — zero behavior change for the existing 111 tools).
 */
data class ToolMetadata(
    val id: String,
    val category: ToolCategory,
    val risk: ToolRisk,
    val tags: List<String> = emptyList(),
    val annotations: ToolAnnotations = ToolAnnotations.infer(id, risk),
    val scope: String = "all"
) {
    /** True when this tool's risk level requires gated approval. */
    val isHighRisk: Boolean get() = risk == ToolRisk.HIGH

    /**
     * Compact one-line summary used in logs and the usage report:
     * `json_path [UTILITY/低] tags: json,query`
     */
    fun summary(): String = buildString {
        append(id)
        append(" [").append(category.name).append('/').append(risk.label).append(']')
        if (tags.isNotEmpty()) append(" tags: ").append(tags.joinToString(","))
        append(" · ").append(annotations.summary())
    }

    /** Builder for tools that declare metadata explicitly. */
    class Builder(
        private val id: String
    ) {
        private var category: ToolCategory? = null
        private var risk: ToolRisk? = null
        private var annotations: ToolAnnotations? = null
        private var scope: String = "all"
        private val tags = mutableListOf<String>()

        /** Set the workspace scope ("agent" | "coding" | "all"). */
        fun scope(scope: String) = apply { this.scope = scope }

        /** Set the category; inferred from the id if never called. */
        fun category(category: ToolCategory) = apply { this.category = category }

        /** Set the risk; inferred from the id if never called. */
        fun risk(risk: ToolRisk) = apply { this.risk = risk }

        /**
         * Declare v3 behavioural annotations explicitly, overriding the
         * id-based inference (e.g. `clipboard` is a mutating write even
         * though its id alone doesn't say so).
         */
        fun annotations(annotations: ToolAnnotations) =
            apply { this.annotations = annotations }

        /** Fluent annotation shortcut: `annotations { readOnly() }`-style. */
        fun annotations(block: ToolAnnotations.Companion.() -> ToolAnnotations) =
            apply { this.annotations = ToolAnnotations.block() }

        /** Append a search tag (lowercased, deduped). */
        fun tag(vararg tag: String) = apply {
            tag.forEach { t ->
                val normalized = t.lowercase().trim()
                if (normalized.isNotEmpty() && normalized !in tags) tags += normalized
            }
        }

        fun build(): ToolMetadata {
            val resolvedCategory = category ?: inferCategory(id)
            val resolvedRisk = risk ?: inferRisk(id, resolvedCategory)
            return ToolMetadata(
                id = id,
                category = resolvedCategory,
                risk = resolvedRisk,
                tags = tags,
                annotations = annotations ?: ToolAnnotations.infer(id, resolvedRisk),
                scope = scope
            )
        }
    }

    companion object {
        /**
         * Infer metadata for a tool id. Prefix rules are ordered by
         * specificity — the first match wins, and unknown ids fall back to
         * UTILITY/LOW (a safe default: worst case a tool gets a harmless
         * label, never a wrong HIGH-risk prompt).
         */
        @JvmStatic
        fun infer(id: String): ToolMetadata = ToolMetadata(
            id = id,
            category = inferCategory(id),
            risk = inferRisk(id, inferCategory(id))
        )

        /** Category inference from the id's prefix family. */
        @JvmStatic
        fun inferCategory(id: String): ToolCategory = when {
            id == "shell_execute" -> ToolCategory.SHELL

            id.startsWith("terminal.") || id.startsWith("exec_in_terminal") ||
                id == "send_to_terminal" || id == "read_terminal" ||
                id == "list_terminals" -> ToolCategory.TERMINAL

            id.startsWith("file_") || id.startsWith("read_file") ||
                id.startsWith("write_file") || id.startsWith("edit_file") ||
                id.startsWith("list_files") || id.startsWith("delete_file") ||
                id.startsWith("copy_file") || id.startsWith("move_file") ||
                id.startsWith("glob_") || id.startsWith("search_files") -> ToolCategory.FILE

            id.startsWith("web_") || id == "http_request" ||
                id == "download_file" -> ToolCategory.WEB

            id.startsWith("browser_") || id.startsWith("page_") ||
                id.startsWith("dom_") -> ToolCategory.BROWSER

            id.startsWith("memory_") || id.startsWith("recall") ||
                id.startsWith("memorize") || id.startsWith("forget") ||
                id.startsWith("episode") -> ToolCategory.MEMORY

            // #172：会话内上下文回顾（区别于跨会话的 MEMORY）。
            id.startsWith("context_") || id.startsWith("session_stats") -> ToolCategory.CONTEXT

            id.startsWith("app_") || id == "deep_link" -> ToolCategory.APP

            // #172：高级设备工具（tts/手电筒/振动/电池/网络/分享）归系统控制。
            id.startsWith("tts_") || id.startsWith("torch") ||
                id.startsWith("vibrate") || id.startsWith("battery_") ||
                id.startsWith("network_") || id.startsWith("share_") ||
                id.startsWith("device_") || id.startsWith("settings_") ||
                id.startsWith("media_") || id.startsWith("clipboard_") ||
                id.startsWith("get_time") || id.startsWith("logcat") ||
                id.startsWith("screenshot") -> ToolCategory.SYSTEM

            // #167 金库工具族：密钥存取与盲投递。
            id.startsWith("vault_") -> ToolCategory.SECURITY

            id.startsWith("ui_") || id.startsWith("input_text") ||
                id.startsWith("tap_") || id.startsWith("swipe_") ||
                id.startsWith("dump_") -> ToolCategory.UI

            id.startsWith("get_location") || id.startsWith("notification") ||
                id.startsWith("sensor_") -> ToolCategory.SENSOR

            id.startsWith("ask_user") -> ToolCategory.AGENT

            id.startsWith("skill_") || id.contains("skill") -> ToolCategory.SKILL

            id.startsWith("mcp_") -> ToolCategory.MCP

            id.startsWith("github_") -> ToolCategory.GITHUB

            id.startsWith("plugin") -> ToolCategory.PLUGIN

            else -> ToolCategory.UTILITY
        }

        /**
         * Risk inference. Destructive/system-wide tool families default to
         * HIGH; mutation-capable families to MEDIUM; everything else LOW.
         */
        @JvmStatic
        fun inferRisk(id: String, category: ToolCategory): ToolRisk = when {
            // Destructive or irreversible operations → HIGH.
            // #167：金库删除 = 不可逆销毁密钥。
            id.startsWith("vault_delete") ||
                id == "shell_execute" ||
                id.startsWith("app_uninstall") || id.startsWith("app_install") ||
                id.startsWith("app_force") ||
                id.startsWith("settings_put") || id.startsWith("settings_") && id.endsWith("_put") ||
                id.startsWith("delete_file") || id.startsWith("file_delete") ||
                id.startsWith("uninstall") ||
                id.startsWith("move_") -> ToolRisk.HIGH

            // Mutating but recoverable / sandbox-scoped operations → MEDIUM.
            // #167：金库写入（覆写式）与盲投递（剪贴板/终端/HTTP 副作用）。
            id.startsWith("vault_save") || id.startsWith("vault_paste") ||
                id.startsWith("write_file") || id.startsWith("edit_file") ||
                id.startsWith("file_write") || id.startsWith("file_edit") ||
                id.startsWith("terminal.") || id == "download_file" ||
                id.startsWith("clipboard_") || id.startsWith("ui_") ||
                id.startsWith("input_") || id.startsWith("media_") ||
                id.startsWith("mcp_") || id.startsWith("skill_") ||
                id.startsWith("github_") && (id.contains("write") || id.contains("create")) ||
                id.startsWith("plugin") -> ToolRisk.MEDIUM

            else -> when (category) {
                ToolCategory.SHELL -> ToolRisk.HIGH
                ToolCategory.UI -> ToolRisk.MEDIUM
                ToolCategory.TERMINAL -> ToolRisk.MEDIUM
                ToolCategory.BROWSER -> ToolRisk.MEDIUM
                else -> ToolRisk.LOW
            }
        }

        /** Fluent metadata construction: `meta("json_path") { tag("json") }`. */
        @JvmStatic
        fun meta(id: String, block: Builder.() -> Unit = {}): ToolMetadata =
            Builder(id).apply(block).build()

        /**
         * v3 作用域规范化：把市场 tier / 手改配置里的原始 scope 折叠为
         * "agent" / "coding" / "all" 三值之一。null、空白与未知值（笔改
         * mcp_servers.json 或 manifest 的笔误）一律按 "all" 处理——
         * fail-open：一个拼写错误不应把工具从两个工位同时藏死。注册侧
         * （McpToolRegistrar / SkillHotReloader）打标前统一过本函数，
         * 保证 [scope] 恒为合法三值；市场目录侧的可见性仍按原始值精确
         * 匹配，两侧语义互不越界。
         */
        @JvmStatic
        fun normalizeScope(raw: String?): String {
            val value = raw?.trim()?.lowercase() ?: return SCOPE_ALL
            return if (value == "agent" || value == "coding") value else SCOPE_ALL
        }

        /** v3 作用域常量：与市场 tier / AgentConfig.skillScope 同源。 */
        const val SCOPE_ALL = "all"
    }
}
