package com.apex.agent.core.tools.skill

/**
 * 为斜杠菜单提供动态数据
 * 从 SkillRegistry 实时读取已安装的 Skill/MCP/连接器/插件
 *
 * v2 修复：旧实现用 label 后缀 `"(未安装)"` 字符串编码状态，消费方
 * （SlashMenuProvider）用 `label.contains("未安装")` 反推——改文案即全线错乱，
 * 用户自建技能名字含"未安装"还会被误标。现在改为结构化字段 [SkillMenuItem.installed]。
 */
class SkillMenuProvider(
    private val skillRegistry: SkillRegistry
) {
    /**
     * 获取当前可用的 Skills 列表（仅启用的）
     */
    fun getActiveSkills(): List<SkillMenuItem> {
        return skillRegistry.getInstalled()
            .filter { it.enabled }
            .map { skill ->
                SkillMenuItem(
                    id = skill.manifest.id,
                    label = skill.manifest.name,
                    command = "/skill:${skill.manifest.id} ",
                    description = skill.manifest.description,
                    installed = true
                )
            }
    }

    /**
     * 获取内置模板（未安装但可安装的）
     */
    fun getBuiltinTemplates(): List<SkillMenuItem> {
        val installedIds = skillRegistry.getInstalled().map { it.manifest.id }.toSet()
        return BUILTIN_TEMPLATES
            .filter { it.id !in installedIds }
            .map { t ->
                SkillMenuItem(
                    id = t.id,
                    label = t.name,
                    command = "/skill:${t.id} ",
                    description = t.description,
                    installed = false
                )
            }
    }

    companion object {
        // 公开给市场页（MarketScreen）复用同一份清单，避免与斜杠菜单两处漂移
        val BUILTIN_TEMPLATES = listOf(
            BuiltinTemplate("web_scraper", "网页数据爬取", "从网页提取结构化数据", "WEB", listOf("web", "scrape", "extract")),
            BuiltinTemplate("file_organizer", "文件自动整理", "按类型/日期自动分类整理文件", "FILE", listOf("file", "organize", "automation")),
            BuiltinTemplate("code_runner", "代码运行器", "编写并运行代码，自动修复错误", "SHELL", listOf("code", "execute", "python")),
            BuiltinTemplate("data_analyzer", "数据分析", "分析 CSV/JSON 数据，生成统计报告", "UTILITY", listOf("data", "analysis", "csv")),
            BuiltinTemplate("coding_principles", "编码原则 (Karpathy)", "AI 编程协作九原则，约束 Agent 编码行为", "AGENT", listOf("prompt", "coding", "principles")),
            BuiltinTemplate("deep_research", "深度调研", "多源检索 + 抓取 + 交叉验证，产出带引用的调研报告", "WEB", listOf("research", "web", "report")),
            BuiltinTemplate("code_review", "代码评审", "按严重度分级的代码审查清单（正确性/安全/性能/可维护性）", "AGENT", listOf("review", "coding", "quality")),
            BuiltinTemplate("crash_triage", "崩溃/ANR 定位", "Android 崩溃栈与 ANR 日志的根因定位流程", "SHELL", listOf("android", "crash", "logcat")),
            BuiltinTemplate("git_workflow", "Git 提交与 PR 规范", "Conventional Commits + PR 描述模板，产出可评审的提交历史", "AGENT", listOf("git", "commit", "pr")),
            BuiltinTemplate("standup_report", "日报/周报生成", "把零散进展整理成结论先行的日报/周报", "UTILITY", listOf("report", "standup", "summary")),
            BuiltinTemplate("im_notify", "结果推送到 IM", "把执行结果推送到微信/飞书/QQ 等已配置的消息通道", "WEB", listOf("connector", "messaging", "notify"))
        )
    }

    data class BuiltinTemplate(
        val id: String,
        val name: String,
        val description: String,
        /** 市场分类（镜像 ToolCategory 枚举名）。 */
        val category: String? = null,
        val tags: List<String> = emptyList()
    )
}

data class SkillMenuItem(
    val id: String,
    val label: String,
    val command: String,
    val description: String = "",
    /** 是否已安装（消费方渲染角标/状态用，替代旧 label 字符串协议）。 */
    val installed: Boolean = true
)
