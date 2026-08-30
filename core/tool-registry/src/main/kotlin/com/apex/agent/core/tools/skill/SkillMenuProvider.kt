package com.apex.agent.core.tools.skill

/**
 * 为斜杠菜单提供动态数据
 * 从 SkillRegistry 实时读取已安装的 Skill/MCP/连接器/插件
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
                    description = skill.manifest.description
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
                    label = "${t.name} (未安装)",
                    command = "/skill:${t.id} ",
                    description = t.description
                )
            }
    }

    companion object {
        // 公开给市场页（MarketScreen）复用同一份清单，避免与斜杠菜单两处漂移
        val BUILTIN_TEMPLATES = listOf(
            BuiltinTemplate("web_scraper", "网页数据爬取", "从网页提取结构化数据"),
            BuiltinTemplate("file_organizer", "文件自动整理", "按类型/日期自动分类整理文件"),
            BuiltinTemplate("code_runner", "代码运行器", "编写并运行代码，自动修复错误"),
            BuiltinTemplate("data_analyzer", "数据分析", "分析 CSV/JSON 数据，生成统计报告"),
            BuiltinTemplate("coding_principles", "编码原则 (Karpathy)", "AI 编程协作九原则，约束 Agent 编码行为")
        )
    }

    data class BuiltinTemplate(
        val id: String,
        val name: String,
        val description: String
    )
}

data class SkillMenuItem(
    val id: String,
    val label: String,
    val command: String,
    val description: String = ""
)
