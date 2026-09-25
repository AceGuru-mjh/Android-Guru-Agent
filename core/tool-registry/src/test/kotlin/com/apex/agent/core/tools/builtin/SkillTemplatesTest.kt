package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.skill.SkillManifest
import com.apex.agent.core.tools.skill.SkillMenuProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 Skill 模板的静态校验。
 *
 * 模板是**手写 JSON 字符串**（不是编译期对象），拼错一个转义或漏一个引号只在
 * 用户点安装时才炸；而 SkillMenuProvider 的清单与模板常量两处手工同步，任一侧
 * 漏改都会让「市场里看得见、点了装不上」。这里在单测里一次性兜住两类漂移：
 * 1. 每个模板都能反序列化成 [SkillManifest]，且 id 与键一致；
 * 2. 市场清单 [SkillMenuProvider.BUILTIN_TEMPLATES] 与模板常量集合完全对齐。
 */
class SkillTemplatesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val templates: Map<String, String> = mapOf(
        "web_scraper" to SkillInstallTool.WEB_SCRAPER_TEMPLATE,
        "file_organizer" to SkillInstallTool.FILE_ORGANIZER_TEMPLATE,
        "code_runner" to SkillInstallTool.CODE_RUNNER_TEMPLATE,
        "data_analyzer" to SkillInstallTool.DATA_ANALYZER_TEMPLATE,
        "coding_principles" to SkillInstallTool.CODING_PRINCIPLES_TEMPLATE,
        "deep_research" to SkillInstallTool.DEEP_RESEARCH_TEMPLATE,
        "code_review" to SkillInstallTool.CODE_REVIEW_TEMPLATE,
        "crash_triage" to SkillInstallTool.CRASH_TRIAGE_TEMPLATE,
        "git_workflow" to SkillInstallTool.GIT_WORKFLOW_TEMPLATE,
        "standup_report" to SkillInstallTool.STANDUP_REPORT_TEMPLATE,
        "im_notify" to SkillInstallTool.IM_NOTIFY_TEMPLATE
    )

    @Test
    fun `every template parses into a valid manifest`() {
        templates.forEach { (key, raw) ->
            val manifest = json.decodeFromString<SkillManifest>(raw)
            assertEquals("模板 $key 的 id 与键不一致", key, manifest.id)
            assertTrue("模板 $key 缺少 name", manifest.name.isNotBlank())
            assertTrue("模板 $key 缺少 description", manifest.description.isNotBlank())
            assertEquals("apex-skill-v1", manifest.schema)
        }
    }

    @Test
    fun `market list and template constants stay in sync`() {
        val listed = SkillMenuProvider.BUILTIN_TEMPLATES.map { it.id }.toSet()
        assertEquals("市场清单与模板常量集合不一致（漏改一侧会导致可见但装不上）", templates.keys, listed)
    }

    @Test
    fun `composite templates declare their required tools`() {
        templates.forEach { (key, raw) ->
            val manifest = json.decodeFromString<SkillManifest>(raw)
            manifest.tools.forEach { tool ->
                assertTrue(
                    "模板 $key 的工具 ${tool.id} 缺少 description",
                    tool.description.isNotBlank()
                )
                if (tool.implementation.type == "composite") {
                    assertTrue(
                        "模板 $key 的 composite 工具 ${tool.id} 没有步骤",
                        tool.implementation.steps.isNotEmpty()
                    )
                    tool.implementation.steps.forEach { step ->
                        assertTrue(
                            "模板 $key 的步骤缺少 tool id",
                            step.tool.isNotBlank()
                        )
                        assertTrue(
                            "模板 $key 引用了未声明的工具 ${step.tool}",
                            step.tool in manifest.requirements.toolsRequired
                        )
                    }
                }
            }
        }
    }
}
