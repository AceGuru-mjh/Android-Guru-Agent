package com.apex.agent.core.tools.catalog

/**
 * # Tool System v4 — Tool Tiers (default-on core set)
 *
 * Root cause this fixes: the registry holds ~110 tools; sending every schema
 * on every request produced a 70–100 KB payload that small-context models and
 * strict OpenAI-compatible gateways reject outright ("directly sending a
 * message errors, you must pick functions first").
 *
 * Borrowed and unified from the two best references:
 * - operit's progressive activation (`use_package` materialises extra tool
 *   docs only when needed);
 * - rikkahub-agent's ToolCatalog (search/open meta-tools once the surface
 *   grows past ~30 tools).
 *
 * v4 policy:
 * - **CORE** — a curated, always-exposed set (~45 ids below). These go into
 *   every request with full schemas: the agent is *capable by default*.
 * - **CATALOG** — everything else. Not in the request; discoverable through
 *   the `tool_search` / `tool_open` / `tool_list` meta-tools. `tool_open`
 *   activates a tool for the rest of the session (see [ToolActivationStore]),
 *   and the engine includes activated tools on the next iteration.
 * - **LEGACY ALIASES** — deprecated duplicate ids kept for compatibility.
 *   They never enter the request; the modern tool wins the name slot.
 */
object ToolTierPolicy {

    /** The deprecated alias ids (old flat names kept only for compat). */
    val LEGACY_ALIAS_IDS: Set<String> = setOf(
        "terminal_exec", "terminal_send", "terminal_read",
        "terminal_list", "terminal_signal", "terminal_close",
        // #171 四族合并：旧工具 id 降为 legacy alias（类保留注册，向后兼容
        // 既有会话/技能；不再随请求下发，模型一律用合并后的新入口）。
        "get_time", "datetime", "cron_next", "duration_convert",
        "uuid_generate", "random_generate",
        "regex_extract", "regex_replace",
        "json_path", "json_transform"
    )

    /**
     * Curated CORE set — always in the request, full schema.
     *
     * Selection criteria:
     * 1. Covers the agent's headline capabilities (ask / files / web /
     *    terminal / memory / apps / device / MCP / skills / connectors);
     * 2. Each is individually useful without prior setup;
     * 3. Total schema payload stays ~20 KB — safe for every provider.
     */
    val CORE_TOOL_IDS: Set<String> = setOf(
        // ── interaction ──
        "ask_user", "ask_user_choice",

        // ── files ──
        "read_file", "write_file", "edit_file", "list_files",
        "glob_files", "search_files", "copy_move_file", "delete_file",

        // ── coding (Code 模式；Agent 模式同样可见 —— 工具规则互用) ──
        // v0.2：code_task（#147 子代理委派）与 code_check（#148 即时诊断）入 CORE。
        "code_read", "code_edit", "code_write", "code_grep", "code_glob", "code_todo",
        "code_task", "code_check",

        // ── git (v1.0 #153；经 PRoot Ubuntu 沙箱执行，两模式互用) ──
        "code_git_status", "code_git_diff", "code_git_log", "code_git_commit", "code_git_branch",

        // ── web ──
        "web_search", "web_fetch", "http_request", "download_file",

        // ── terminal (agent-native PTY; dotted ids are provider-sanitised) ──
        // terminal.exec = one-shot structured execution（无需先建会话，
        // “agent 默认能跑命令”的最短路径）。
        "terminal.exec", "terminal.create", "terminal.run", "terminal.observe",
        "terminal.wait", "terminal.snapshot", "terminal.backends",

        // ── memory (CS-Mem) ──
        "memory_recent_episodes", "memory_search_nodes", "memory_recall_macro",

        // ── apps & device ──
        "app_list", "app_info", "app_launch",
        // #171：get_time → time（四族合并后唯一时间入口）；
        // #172：context_recap 入 CORE —— 长会话自救的关键工具（迷路时一次
        // 调用重建全景，无需 tool_search 两跳）。
        "get_device_info", "time", "context_recap", "clipboard", "screenshot", "calculate",

        // ── MCP (first-class tools register as mcp__server__tool) ──
        "mcp_list", "mcp_connect", "mcp_call",

        // ── skills & connectors ──
        "skill_list", "skill_search", "connector_list",

        // ── catalog meta-tools (v4) ──
        "tool_search", "tool_open", "tool_list"
    )

    /** Ids of the v4 catalog meta-tools themselves. */
    val CATALOG_TOOL_IDS: Set<String> = setOf("tool_search", "tool_open", "tool_list")

    /** @return true when [toolId] belongs to the always-exposed CORE set. */
    fun isCore(toolId: String): Boolean = toolId in CORE_TOOL_IDS

    /** @return true when [toolId] is a deprecated legacy alias. */
    fun isLegacyAlias(toolId: String): Boolean = toolId in LEGACY_ALIAS_IDS
}
