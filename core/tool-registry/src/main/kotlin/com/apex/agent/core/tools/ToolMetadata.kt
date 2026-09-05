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

    /** Installed app management (list/launch/install/uninstall). */
    APP("应用管理", 70),

    /** Device/system state control (settings/clipboard/logcat/time). */
    SYSTEM("系统控制", 80),

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
 */
data class ToolMetadata(
    val id: String,
    val category: ToolCategory,
    val risk: ToolRisk,
    val tags: List<String> = emptyList()
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
    }

    /** Builder for tools that declare metadata explicitly. */
    class Builder(
        private val id: String
    ) {
        private var category: ToolCategory? = null
        private var risk: ToolRisk? = null
        private val tags = mutableListOf<String>()

        /** Set the category; inferred from the id if never called. */
        fun category(category: ToolCategory) = apply { this.category = category }

        /** Set the risk; inferred from the id if never called. */
        fun risk(risk: ToolRisk) = apply { this.risk = risk }

        /** Append a search tag (lowercased, deduped). */
        fun tag(vararg tag: String) = apply {
            tag.forEach { t ->
                val normalized = t.lowercase().trim()
                if (normalized.isNotEmpty() && normalized !in tags) tags += normalized
            }
        }

        fun build(): ToolMetadata = ToolMetadata(
            id = id,
            category = category ?: inferCategory(id),
            risk = risk ?: inferRisk(id, category ?: inferCategory(id)),
            tags = tags
        )
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

            id.startsWith("app_") -> ToolCategory.APP

            id.startsWith("device_") || id.startsWith("settings_") ||
                id.startsWith("media_") || id.startsWith("clipboard_") ||
                id.startsWith("get_time") || id.startsWith("logcat") ||
                id.startsWith("screenshot") -> ToolCategory.SYSTEM

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
            id == "shell_execute" ||
                id.startsWith("app_uninstall") || id.startsWith("app_install") ||
                id.startsWith("app_force") ||
                id.startsWith("settings_put") || id.startsWith("settings_") && id.endsWith("_put") ||
                id.startsWith("delete_file") || id.startsWith("file_delete") ||
                id.startsWith("uninstall") ||
                id.startsWith("move_") -> ToolRisk.HIGH

            // Mutating but recoverable / sandbox-scoped operations → MEDIUM.
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
    }
}
