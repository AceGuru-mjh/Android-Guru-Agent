package com.apex.agent.core.engine.templates

import com.apex.agent.core.engine.promptvars.PromptVariableContext
import com.apex.agent.core.engine.promptvars.PromptVariableExpander
import com.apex.agent.core.engine.promptvars.PromptVariableRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/**
 * 4-b — 提示词模板引擎测试。
 *
 * 覆盖：变量值三层合并优先级（overrides > 模板默认值 > 注册表解析）、
 * 必填缺失报告、usageCount 自增、renderRaw 嵌套解析、未声明变量经
 * 内置变量直通、注册表自定义变量满足必填。
 */
class PromptTemplateEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private var now = 1000L

    private lateinit var templateRegistry: PromptTemplateRegistry
    private lateinit var promptVariables: PromptVariableRegistry
    private lateinit var expander: PromptVariableExpander
    private lateinit var engine: PromptTemplateEngine

    private val ctx: PromptVariableContext = PromptVariableContext.DEFAULT.copy(
        nowMs = { 1735689600000L }
    )

    @Before
    fun setup() {
        dir = tmp.newFolder("templates")
        templateRegistry = PromptTemplateRegistry(dir, clock = { now })
        promptVariables = PromptVariableRegistry(Locale.US)
        expander = PromptVariableExpander(promptVariables)
        engine = PromptTemplateEngine(templateRegistry, expander)
    }

    private suspend fun saveReviewTemplate(id: String = "tpl_review"): PromptTemplate {
        val template = PromptTemplate(
            id = id,
            name = "评审模板",
            category = TemplateCategory.CODING,
            content = "语言：{{language}}\n代码：{{code}}\n模型：{{model_id}}",
            variables = listOf(
                TemplateVariable("language", "代码语言", required = false, defaultValue = "Python"),
                TemplateVariable("code", "待评审代码", required = true)
            ),
            tags = listOf("测试")
        )
        templateRegistry.save(template)
        return template
    }

    // ═══ 三层合并优先级 ═══

    @Test
    fun `render merges overrides defaults and builtins in order`() = runTest {
        saveReviewTemplate()
        val result = engine.render("tpl_review", overrides = mapOf("code" to "print(1)"), context = ctx)
        assertTrue(result.ok)
        assertEquals(emptyList<String>(), result.missingRequired)
        assertNotNull(result.template)
        // 默认值层（language 无 override → 模板默认 Python）
        assertTrue(result.text.contains("语言：Python"))
        // override 层
        assertTrue(result.text.contains("代码：print(1)"))
        // 内置变量层（model_id 未声明但直通解析）
        assertTrue(result.text.contains("模型：gemini-2.0-flash"))
    }

    @Test
    fun `override beats template default value`() = runTest {
        saveReviewTemplate()
        val result = engine.render(
            "tpl_review",
            overrides = mapOf("language" to "Kotlin", "code" to "val x = 1"),
            context = ctx
        )
        assertTrue(result.ok)
        assertTrue(result.text.contains("语言：Kotlin"))
        assertFalse(result.text.contains("Python"))
    }

    @Test
    fun `template default beats context custom for same declared variable`() = runTest {
        saveReviewTemplate()
        val result = engine.render(
            "tpl_review",
            overrides = mapOf("code" to "c"),
            context = ctx.copy(custom = mapOf("language" to "Rust"))
        )
        // 合并层级（规范）：overrides > 模板默认值 > 注册表链（含 context.custom）
        assertTrue(result.text.contains("语言：Python"))
        assertFalse(result.text.contains("Rust"))
    }

    // ═══ 必填缺失 ═══

    @Test
    fun `missing required variable is reported and render fails`() = runTest {
        saveReviewTemplate()
        val result = engine.render("tpl_review", context = ctx)
        assertFalse(result.ok)
        assertEquals(listOf("code"), result.missingRequired)
        // text 为尽力展开的部分结果：缺失位按 KEEP 策略保留
        assertTrue(result.text.contains("语言：Python"))
        assertTrue(result.text.contains("{{code}}"))
        assertTrue(result.text.contains("模型：gemini-2.0-flash"))
    }

    @Test
    fun `multiple missing required variables listed in declaration order`() = runTest {
        templateRegistry.save(
            PromptTemplate(
                id = "tpl_multi",
                name = "多变量",
                content = "{{a}} {{b}} {{c}}",
                variables = listOf(
                    TemplateVariable("b", "B", required = true),
                    TemplateVariable("a", "A", required = true),
                    TemplateVariable("c", "C", required = false)
                )
            )
        )
        val result = engine.render("tpl_multi", context = ctx)
        assertFalse(result.ok)
        assertEquals(listOf("b", "a"), result.missingRequired)
    }

    @Test
    fun `optional variable without default stays ok`() = runTest {
        templateRegistry.save(
            PromptTemplate(
                id = "tpl_opt",
                name = "可选变量",
                content = "头部 {{optional_tail}}",
                variables = listOf(TemplateVariable("optional_tail", "可选", required = false))
            )
        )
        val result = engine.render("tpl_opt", context = ctx)
        assertTrue(result.ok)
        assertTrue(result.text.contains("{{optional_tail}}"))
    }

    @Test
    fun `required variable satisfied by registry custom variable`() = runTest {
        promptVariables.registerCustom("project", "Apex Agent 仓库")
        templateRegistry.save(
            PromptTemplate(
                id = "tpl_project",
                name = "项目模板",
                content = "项目：{{project}}",
                variables = listOf(TemplateVariable("project", "项目名", required = true))
            )
        )
        val result = engine.render("tpl_project", context = ctx)
        assertTrue(result.ok)
        assertTrue(result.text.contains("项目：Apex Agent 仓库"))
    }

    @Test
    fun `required variable satisfied by context custom`() = runTest {
        templateRegistry.save(
            PromptTemplate(
                id = "tpl_env",
                name = "环境模板",
                content = "环境：{{env}}",
                variables = listOf(TemplateVariable("env", "部署环境", required = true))
            )
        )
        val result = engine.render("tpl_env", context = ctx.copy(custom = mapOf("env" to "生产")))
        assertTrue(result.ok)
        assertTrue(result.text.contains("环境：生产"))
    }

    // ═══ 未知模板 ═══

    @Test
    fun `render unknown template id fails cleanly`() = runTest {
        val result = engine.render("tpl_not_exist", context = ctx)
        assertFalse(result.ok)
        assertNull(result.template)
        assertEquals("", result.text)
        assertEquals(emptyList<String>(), result.missingRequired)
    }

    // ═══ usageCount ═══

    @Test
    fun `render bumps usageCount without touching updatedAt`() = runTest {
        saveReviewTemplate()
        val before = templateRegistry.get("tpl_review")!!
        engine.render("tpl_review", overrides = mapOf("code" to "x"), context = ctx)
        engine.render("tpl_review", context = ctx) // 失败渲染同样计数（使用即计数）
        val after = templateRegistry.get("tpl_review")!!
        assertEquals(before.usageCount + 2, after.usageCount)
        assertEquals(before.updatedAt, after.updatedAt)
        // 落盘确认
        assertEquals(2L, PromptTemplateRegistry(dir).get("tpl_review")!!.usageCount)
    }

    @Test
    fun `usageCount survives registry reload`() = runTest {
        saveReviewTemplate()
        engine.render("tpl_review", overrides = mapOf("code" to "x"), context = ctx)
        // 新引擎实例读同一磁盘库 → 计数延续
        val reloaded = PromptTemplateRegistry(dir, clock = { now })
        val engine2 = PromptTemplateEngine(reloaded, expander)
        engine2.render("tpl_review", overrides = mapOf("code" to "y"), context = ctx)
        assertEquals(2L, reloaded.get("tpl_review")!!.usageCount)
    }

    // ═══ renderRaw ═══

    @Test
    fun `renderRaw expands with overrides and nested built-in resolution`() = runTest {
        val result = engine.renderRaw(
            "{{greeting}}",
            overrides = mapOf("greeting" to "你好 {{user_name}}，当前 {{platform}}"),
            context = ctx
        )
        assertTrue(result.ok)
        assertEquals("你好 Boss，当前 Android", result.text)
        assertNull(result.template)
    }

    @Test
    fun `renderRaw without variables returns text unchanged`() = runTest {
        val result = engine.renderRaw("纯文本无变量", context = ctx)
        assertTrue(result.ok)
        assertEquals("纯文本无变量", result.text)
    }

    @Test
    fun `renderRaw empty text is empty`() = runTest {
        assertEquals("", engine.renderRaw("", context = ctx).text)
    }

    @Test
    fun `renderRaw time variables are deterministic with fake clock`() = runTest {
        val result = engine.renderRaw("现在是 {{datetime}}（{{weekday}}）", context = ctx)
        assertEquals("现在是 2025-01-01 00:00（Wed）", result.text)
    }

    // ═══ 未声明变量直通内置 ═══

    @Test
    fun `undeclared variables resolve through built-in chain`() = runTest {
        templateRegistry.save(
            PromptTemplate(
                id = "tpl_env_header",
                name = "环境头",
                content = "平台 {{platform}} / 设备 {{device}} / {{code}}",
                variables = listOf(TemplateVariable("code", "代码", required = true))
            )
        )
        val result = engine.render("tpl_env_header", overrides = mapOf("code" to "main()"), context = ctx)
        assertTrue(result.ok)
        assertTrue(result.text.contains("平台 Android"))
        assertTrue(result.text.contains("设备 Pixel 9 Pro"))
        assertTrue(result.text.contains("main()"))
    }

    // ═══ 内置模板集成 ═══

    @Test
    fun `built-in template renders with defaults and overrides`() = runTest {
        templateRegistry.reseedBuiltIns()
        val result = engine.render(
            "builtin_regex_explain",
            overrides = mapOf("pattern" to "[a-z]+\\d"),
            context = ctx
        )
        assertTrue(result.ok)
        assertEquals("正则解释", result.template!!.name)
        // 声明默认值生效（sample → 无）
        assertTrue(result.text.contains("示例文本：无"))
        assertTrue(result.text.contains("[a-z]+\\d"))
        // usageCount 从 0 → 1
        assertEquals(1L, templateRegistry.get("builtin_regex_explain")!!.usageCount)
    }

    @Test
    fun `built-in template missing required variables reports them`() = runTest {
        templateRegistry.reseedBuiltIns()
        val result = engine.render("builtin_translate", context = ctx)
        assertFalse(result.ok)
        // text 与 target_language 必填缺失
        assertEquals(listOf("text", "target_language"), result.missingRequired)
    }

    @Test
    fun `render result template reflects stored snapshot`() = runTest {
        saveReviewTemplate()
        val result = engine.render("tpl_review", overrides = mapOf("code" to "x"), context = ctx)
        assertEquals("tpl_review", result.template!!.id)
        assertEquals(listOf("code", "language"), result.template!!.variables.map { it.name }.sorted())
    }
}
