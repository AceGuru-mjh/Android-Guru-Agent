package com.apex.agent.core.engine.task

/**
 * T76 — 工具幂等性分类注册表（N-7）。
 *
 * **不动 `AgentTool` 接口**（冻结原则）：分类表独立维护 toolId →
 * [ToolIdempotencyClass] 映射，供：
 * 1. journal 落盘时快照分类（ToolOperationRecord.idempotency）；
 * 2. 崩溃恢复时对 UNKNOWN/中断操作的决策输入（[RecoveryPolicy]）。
 *
 * 覆盖 App 实际注册的 80 个工具（core/tool-registry 41 + terminal 8 +
 * browser 15 + cs-mem 3 + github 7 + ask_user 2 + 其余 app 层），逐一
 * 人工归类。**未列入表的工具默认 [ToolIdempotencyClass.UNKNOWN]**
 * （插件工具、未来新工具自动进入保守路径——恢复时升级为用户决策）。
 *
 * 归类口径（重放一次该调用的副作用）：
 * - READ_ONLY：纯读（文件列表、抓屏、日志、检索）→ 重放无副作用；
 * - IDEMPOTENT_WRITE：整覆盖写 / 幂等连接 / 纯函数变换 → 重放等价；
 * - NON_IDEMPOTENT：递增副作用（卸载、点击、输入、发送）→ 重放有害；
 * - UNKNOWN：任意行为透传（shell、HTTP、MCP、插件）→ 不可判定。
 */
class ToolExecutionPolicy {

    /** toolId → 幂等性分类（查不到 → UNKNOWN）。 */
    fun classify(toolId: String): ToolIdempotencyClass =
        REGISTRY[toolId] ?: ToolIdempotencyClass.UNKNOWN

    /**
     * 该工具是否"用户交互型"（ask_user 族）。
     *
     * 恢复时未获回答的 ask_user 调用应 RETRY（重新弹出提问），
     * 而非当作数据操作做幂等决策——单独识别。
     */
    fun isUserInteractionTool(toolId: String): Boolean =
        toolId in USER_INTERACTION_TOOLS

    companion object {
        /** 全部已注册工具的显式分类表（人工逐条归类，可审计）。 */
        private val REGISTRY: Map<String, ToolIdempotencyClass> = buildMap {
            // ═══ 文件 / 文件系统（core/tool-registry FileTools）═══
            put("read_file", ToolIdempotencyClass.READ_ONLY)
            put("list_files", ToolIdempotencyClass.READ_ONLY)
            put("glob_files", ToolIdempotencyClass.READ_ONLY)
            put("search_files", ToolIdempotencyClass.READ_ONLY)
            put("write_file", ToolIdempotencyClass.IDEMPOTENT_WRITE) // 整文件覆盖写
            put("edit_file", ToolIdempotencyClass.NON_IDEMPOTENT)    // 增量编辑变换，重放可能二次修改
            put("append", ToolIdempotencyClass.NON_IDEMPOTENT)       // append 语义天然非幂等
            put("delete_file", ToolIdempotencyClass.NON_IDEMPOTENT)  // 删除不可逆
            put("copy_move_file", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("create_directory", ToolIdempotencyClass.IDEMPOTENT_WRITE) // mkdirs 幂等
            put("file_organizer", ToolIdempotencyClass.NON_IDEMPOTENT)    // 批量移动
            put("download_file", ToolIdempotencyClass.IDEMPOTENT_WRITE)   // 下载覆盖写
            put("text_transform", ToolIdempotencyClass.READ_ONLY)     // 纯函数变换（大小写/反转）
            put("json_format", ToolIdempotencyClass.READ_ONLY)
            put("char_count", ToolIdempotencyClass.READ_ONLY)
            put("data_analyzer", ToolIdempotencyClass.READ_ONLY)
            put("code", ToolIdempotencyClass.READ_ONLY)
            put("code_runner", ToolIdempotencyClass.UNKNOWN)          // 任意代码执行
            put("structure", ToolIdempotencyClass.READ_ONLY)
            put("template", ToolIdempotencyClass.READ_ONLY)
            put("connector", ToolIdempotencyClass.READ_ONLY)

            // ═══ Shell / 系统（高危透传）═══
            put("shell_execute", ToolIdempotencyClass.UNKNOWN)        // 任意命令：行为不可判定
            put("get_device_info", ToolIdempotencyClass.READ_ONLY)
            put("get_time", ToolIdempotencyClass.READ_ONLY)
            put("get_set_settings", ToolIdempotencyClass.IDEMPOTENT_WRITE) // put 值幂等
            put("logcat", ToolIdempotencyClass.READ_ONLY)
            put("screenshot", ToolIdempotencyClass.READ_ONLY)
            put("clipboard", ToolIdempotencyClass.NON_IDEMPOTENT)    // 读写混合：写侧污染剪贴板
            put("calculate", ToolIdempotencyClass.READ_ONLY)
            put("notification_read", ToolIdempotencyClass.READ_ONLY)

            // ═══ App 管理 ═══
            put("app_list", ToolIdempotencyClass.READ_ONLY)
            put("app_info", ToolIdempotencyClass.READ_ONLY)
            put("app_install", ToolIdempotencyClass.IDEMPOTENT_WRITE)  // 安装覆盖（reinstall 语义）
            put("app_uninstall", ToolIdempotencyClass.NON_IDEMPOTENT)  // 卸载不可逆
            put("app_launch", ToolIdempotencyClass.IDEMPOTENT_WRITE)   // 单实例启动
            put("app_force_stop", ToolIdempotencyClass.NON_IDEMPOTENT) // 停止有状态应用造成状态丢失

            // ═══ UI 自动化（输入注入类全部 NON_IDEMPOTENT）═══
            put("ui_dump", ToolIdempotencyClass.READ_ONLY)
            put("ui_tap", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("ui_swipe", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("input_text", ToolIdempotencyClass.NON_IDEMPOTENT)

            // ═══ 设备状态 ═══
            put("brightness", ToolIdempotencyClass.IDEMPOTENT_WRITE)  // 设定值
            put("alarm", ToolIdempotencyClass.NON_IDEMPOTENT)         // 每次新建闹钟
            put("control_media", ToolIdempotencyClass.NON_IDEMPOTENT) // 播放/暂停切换类
            put("get_location", ToolIdempotencyClass.READ_ONLY)
            put("battery", ToolIdempotencyClass.READ_ONLY)
            put("network", ToolIdempotencyClass.READ_ONLY)
            put("storage", ToolIdempotencyClass.READ_ONLY)
            put("display", ToolIdempotencyClass.READ_ONLY)
            put("ring", ToolIdempotencyClass.NON_IDEMPOTENT)          // 响铃
            put("music", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("radio", ToolIdempotencyClass.READ_ONLY)
            put("password", ToolIdempotencyClass.READ_ONLY)

            // ═══ Web（读/写分明）═══
            put("web_fetch", ToolIdempotencyClass.READ_ONLY)
            put("web_search", ToolIdempotencyClass.READ_ONLY)
            put("http_request", ToolIdempotencyClass.UNKNOWN)         // GET 幂等 POST 非幂等——方法级才可判定

            // ═══ 记忆 / 技能 / MCP / 插件 ═══
            put("memory_recent_episodes", ToolIdempotencyClass.READ_ONLY)
            put("memory_search_nodes", ToolIdempotencyClass.READ_ONLY)
            put("memory_recall_macro", ToolIdempotencyClass.READ_ONLY)
            put("skill_list", ToolIdempotencyClass.READ_ONLY)
            put("skill_search", ToolIdempotencyClass.READ_ONLY)
            put("skill_create", ToolIdempotencyClass.IDEMPOTENT_WRITE)
            put("skill_install", ToolIdempotencyClass.IDEMPOTENT_WRITE)
            put("skill_uninstall", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("mcp_list", ToolIdempotencyClass.READ_ONLY)
            put("mcp_connect", ToolIdempotencyClass.IDEMPOTENT_WRITE) // 连接重试幂等
            put("mcp_call", ToolIdempotencyClass.UNKNOWN)             // MCP 服务端任意行为

            // ═══ 用户交互（特殊：恢复时重新提问而非幂等决策）═══
            put("ask_user", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("ask_user_choice", ToolIdempotencyClass.NON_IDEMPOTENT)

            // ═══ Terminal v2（审计 §12 禁区内的工具只做分类，不改其实现）═══
            put("terminal_list", ToolIdempotencyClass.READ_ONLY)
            put("terminal_read", ToolIdempotencyClass.READ_ONLY)
            put("terminal_exec", ToolIdempotencyClass.UNKNOWN)        // 任意命令
            put("terminal_send", ToolIdempotencyClass.NON_IDEMPOTENT) // 发送即执行新命令
            put("terminal_signal", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("terminal_close", ToolIdempotencyClass.NON_IDEMPOTENT)

            // ═══ Browser 自动化 ═══
            put("browser_navigate", ToolIdempotencyClass.IDEMPOTENT_WRITE) // 重复导航同一 URL 等价
            put("browser_snapshot", ToolIdempotencyClass.READ_ONLY)
            put("browser_show", ToolIdempotencyClass.READ_ONLY)
            put("browser_screenshot", ToolIdempotencyClass.READ_ONLY)
            put("browser_debug_dump", ToolIdempotencyClass.READ_ONLY)
            put("browser_network_log", ToolIdempotencyClass.READ_ONLY)
            put("browser_download_list", ToolIdempotencyClass.READ_ONLY)
            put("browser_context_summary", ToolIdempotencyClass.READ_ONLY)
            put("browser_click", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("browser_input", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("browser_date_input", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("browser_select", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("browser_scroll", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("browser_toggle", ToolIdempotencyClass.NON_IDEMPOTENT)
            put("browser_file_upload", ToolIdempotencyClass.NON_IDEMPOTENT)

            // ═══ GitHub（读/写分明）═══
            put("github_get_user", ToolIdempotencyClass.READ_ONLY)
            put("github_list_repos", ToolIdempotencyClass.READ_ONLY)
            put("github_list_issues", ToolIdempotencyClass.READ_ONLY)
            put("github_read_file", ToolIdempotencyClass.READ_ONLY)
            put("github_search_code", ToolIdempotencyClass.READ_ONLY)
            put("github_write_file", ToolIdempotencyClass.IDEMPOTENT_WRITE) // 分支写覆盖
            put("github_create_issue", ToolIdempotencyClass.NON_IDEMPOTENT) // 重发会建重复 issue
        }

        private val USER_INTERACTION_TOOLS = setOf("ask_user", "ask_user_choice")
    }
}
