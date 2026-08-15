package com.apex.agent.core.tools.skill

import com.apex.agent.core.tools.builtin.SkillInstallTool

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
     * 从 SkillInstallTool.BUILTIN_TEMPLATES_BY_ID 统一注册表派生，避免三处漂移。
     */
    fun getBuiltinTemplates(): List<SkillMenuItem> {
        val installedIds = skillRegistry.getInstalled().map { it.manifest.id }.toSet()
        return SkillInstallTool.BUILTIN_TEMPLATES_BY_ID
            .filterKeys { it !in installedIds }
            .map { (id, entry) ->
                SkillMenuItem(
                    id = id,
                    label = "${entry.name} (未安装)",
                    command = "/skill:$id ",
                    description = entry.description
                )
            }
    }
}

data class SkillMenuItem(
    val id: String,
    val label: String,
    val command: String,
    val description: String = ""
)
