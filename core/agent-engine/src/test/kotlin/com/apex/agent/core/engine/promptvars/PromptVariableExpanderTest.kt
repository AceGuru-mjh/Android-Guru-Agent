package com.apex.agent.core.engine.promptvars

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 4-b — 提示词变量展开器测试。
 *
 * 语法矩阵：基本/空白容忍/大小写不敏感/默认值/转义/未知策略
 * (KEEP/EMPTY/THROW)/递归深度/环安全/findVariables/畸形容错。
 */
class PromptVariableExpanderTest {

    private val registry = PromptVariableRegistry(Locale.US)
    private val ctx: PromptVariableContext = PromptVariableContext.DEFAULT.copy(
        nowMs = { 1735689600000L } // 2025-01-01T00:00:00Z（周三，UTC）
    )
    private val expander = PromptVariableExpander(registry)

    // ═══ 基本展开 ═══

    @Test
    fun `basic expansion of single and multiple variables`() {
        assertEquals("Gemini 2.0 Flash", expander.expand("{{model_name}}", ctx))
        assertEquals(
            "模型 gemini-2.0-flash 由 Android 运行",
            expander.expand("模型 {{model_id}} 由 {{platform}} 运行", ctx)
        )
    }

    @Test
    fun `plain text without braces is unchanged`() {
        assertEquals("没有任何变量的普通文本", expander.expand("没有任何变量的普通文本", ctx))
        assertEquals("single brace { } ok", expander.expand("single brace { } ok", ctx))
    }

    @Test
    fun `empty string is a no-op`() {
        assertEquals("", expander.expand("", ctx))
    }

    @Test
    fun `names are case-insensitive`() {
        assertEquals("Gemini 2.0 Flash", expander.expand("{{MODEL_NAME}}", ctx))
        assertEquals("Android", expander.expand("{{Platform}}", ctx))
    }

    @Test
    fun `inner whitespace is tolerated`() {
        assertEquals("Gemini 2.0 Flash", expander.expand("{{ model_name }}", ctx))
        assertEquals("Gemini 2.0 Flash", expander.expand("{{\tmodel_name\n}}", ctx))
        assertEquals("Gemini 2.0 Flash", expander.expand("{{   model_name   }}", ctx))
    }

    // ═══ 默认值语法 ═══

    @Test
    fun `default value applies when variable is missing`() {
        assertEquals("全面审查", expander.expand("{{missing_focus|全面审查}}", ctx))
        assertEquals("hello world", expander.expand("{{missing|hello world}}", ctx))
        // 显式空默认值 → 空串
        assertEquals("前缀[]后缀", expander.expand("前缀[{{missing|}}]后缀", ctx))
    }

    @Test
    fun `default value is ignored when variable resolves`() {
        assertEquals("Android", expander.expand("{{platform|默认平台}}", ctx))
        assertEquals("00:00", expander.expand("{{ time |14:30 }}", ctx))
    }

    @Test
    fun `default value wins over unknown policy`() {
        val thrower = PromptVariableExpander(registry, onUnknown = UnknownPolicy.THROW)
        assertEquals("回退文案", thrower.expand("{{unknown_var|回退文案}}", ctx))
    }

    @Test
    fun `default value is trimmed`() {
        assertEquals("x", expander.expand("{{missing|  x  }}", ctx))
    }

    // ═══ 转义 ═══

    @Test
    fun `escaped braces produce literal text`() {
        assertEquals("{{model_id}}", expander.expand("\\{{model_id}}", ctx))
        assertEquals("字面 {{x}} 保留", expander.expand("字面 \\{{x}} 保留", ctx))
        // 转义后的名称不再参与解析
        assertEquals("值 \\{{model_id}}", expander.expand("值 \\\\{{model_id}}", ctx))
    }

    // ═══ 未知变量策略 ═══

    @Test
    fun `unknown variable kept as-is by default`() {
        assertEquals("结果 {{unknown_var}} 保留", expander.expand("结果 {{unknown_var}} 保留", ctx))
        // 保留的是原始 token（含空白形态）
        assertEquals("[{{ spaced_unknown }}]", expander.expand("[{{ spaced_unknown }}]", ctx))
    }

    @Test
    fun `unknown variable emptied with EMPTY policy`() {
        val emptier = PromptVariableExpander(registry, onUnknown = UnknownPolicy.EMPTY)
        assertEquals("结果  保留", emptier.expand("结果 {{unknown_var}} 保留", ctx))
        assertEquals("", emptier.expand("{{unknown_var}}", ctx))
    }

    @Test
    fun `unknown variable throws with THROW policy`() {
        val thrower = PromptVariableExpander(registry, onUnknown = UnknownPolicy.THROW)
        val ex = assertThrows(UnknownPromptVariableException::class.java) {
            thrower.expand("前缀 {{nope}} 后缀", ctx)
        }
        assertEquals("nope", ex.variableName)
        assertEquals("{{nope}}", ex.token)
    }

    @Test
    fun `expandOrNull folds expansion errors to null`() {
        val thrower = PromptVariableExpander(registry, onUnknown = UnknownPolicy.THROW)
        assertNull(thrower.expandOrNull("前缀 {{nope}} 后缀", ctx))
        assertEquals("Android", thrower.expandOrNull("{{platform}}", ctx))
        // KEEP 策略下畸形输入不是错误 → 返回原文
        assertEquals("{{}}", expander.expandOrNull("{{}}", ctx))
    }

    // ═══ 递归展开与深度限制 ═══

    @Test
    fun `resolved values are expanded recursively by default depth`() {
        registry.registerCustom("level1", "L1 {{level2}}")
        registry.registerCustom("level2", "L2 {{level3}}")
        registry.registerCustom("level3", "L3")
        assertEquals("L1 L2 L3", expander.expand("{{level1}}", ctx))
        assertEquals("头 L1 L2 L3 尾", expander.expand("头 {{level1}} 尾", ctx))
    }

    @Test
    fun `maxDepth limits recursion and deeper placeholders stay literal`() {
        registry.registerCustom("l1", "L1 {{l2}}")
        registry.registerCustom("l2", "L2 {{l3}}")
        registry.registerCustom("l3", "L3")
        val depth1 = PromptVariableExpander(registry, maxDepth = 1)
        assertEquals("L1 {{l2}}", depth1.expand("{{l1}}", ctx))
        val depth2 = PromptVariableExpander(registry, maxDepth = 2)
        assertEquals("L1 L2 {{l3}}", depth2.expand("{{l1}}", ctx))
        val depth3 = PromptVariableExpander(registry, maxDepth = 3)
        assertEquals("L1 L2 L3", depth3.expand("{{l1}}", ctx))
        val depth0 = PromptVariableExpander(registry, maxDepth = 0)
        assertEquals("{{l1}}", depth0.expand("{{l1}}", ctx))
    }

    @Test
    fun `negative maxDepth is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PromptVariableExpander(registry, maxDepth = -1)
        }
    }

    // ═══ 环安全 ═══

    @Test
    fun `mutual cycle terminates and leaves placeholder as-is`() {
        registry.registerCustom("a", "{{b}}")
        registry.registerCustom("b", "{{a}}")
        assertEquals("{{a}}", expander.expand("{{a}}", ctx))
        assertEquals("前缀 {{a}} 后缀", expander.expand("前缀 {{a}} 后缀", ctx))
    }

    @Test
    fun `self cycle terminates`() {
        registry.registerCustom("self_loop", "前 {{self_loop}} 后")
        // 外层解析后进入内层，内层回到链上变量 → 原样保留并停止
        assertEquals("前 {{self_loop}} 后", expander.expand("{{self_loop}}", ctx))
    }

    @Test
    fun `partial cycle keeps resolved prefix text`() {
        registry.registerCustom("x", "X {{y}}")
        registry.registerCustom("y", "Y {{x}}")
        assertEquals("X Y {{x}}", expander.expand("{{x}}", ctx))
    }

    @Test
    fun `diamond reference is not a cycle`() {
        // 菱形：root → a 与 b，两者都引用 leaf —— 分支独立，不误伤
        registry.registerCustom("root", "{{a}}+{{b}}")
        registry.registerCustom("a", "A{{leaf}}")
        registry.registerCustom("b", "B{{leaf}}")
        registry.registerCustom("leaf", "叶")
        assertEquals("A叶+B叶", expander.expand("{{root}}", ctx))
    }

    // ═══ 嵌套解析（值引用内置变量）═══

    @Test
    fun `nested value referencing built-in resolves`() {
        registry.registerCustom("greeting", "你好 {{user_name}}")
        assertEquals("你好 Boss", expander.expand("{{greeting}}", ctx))
    }

    @Test
    fun `context custom participates in expansion`() {
        val withCustom = ctx.copy(custom = mapOf("city" to "杭州"))
        assertEquals("城市：杭州", expander.expand("城市：{{city}}", withCustom))
        // context.custom 优先于注册表自定义
        registry.registerCustom("city", "北京")
        assertEquals("城市：杭州", expander.expand("城市：{{city}}", withCustom))
    }

    // ═══ findVariables ═══

    @Test
    fun `findVariables returns distinct names in first-seen order`() {
        val names = expander.findVariables("{{a}} {{b}} {{a}} {{ c }}")
        assertEquals(listOf("a", "b", "c"), names)
    }

    @Test
    fun `findVariables lowercases and includes defaulted references`() {
        val names = expander.findVariables("{{CODE}} and {{lang|默认}}")
        assertEquals(listOf("code", "lang"), names)
    }

    @Test
    fun `findVariables skips escaped placeholders and malformed braces`() {
        assertEquals(emptyList<String>(), expander.findVariables("\\{{escaped}}"))
        assertEquals(emptyList<String>(), expander.findVariables("{{}} {{1abc}} {{name"))
        assertEquals(listOf("x"), expander.findVariables("\\{{y}} {{x}}"))
    }

    @Test
    fun `findVariables on plain text is empty`() {
        assertTrue(expander.findVariables("没有变量").isEmpty())
        assertTrue(expander.findVariables("").isEmpty())
    }

    // ═══ 畸形花括号容错 ═══

    @Test
    fun `malformed braces are left as-is`() {
        assertEquals("{{}}", expander.expand("{{}}", ctx))
        assertEquals("{{ }}", expander.expand("{{ }}", ctx))
        assertEquals("{{1abc}}", expander.expand("{{1abc}}", ctx))
        assertEquals("{{has-dash}}", expander.expand("{{has-dash}}", ctx))
        assertEquals("{{name", expander.expand("{{name", ctx))
        assertEquals("hello {{ world", expander.expand("hello {{ world", ctx))
        assertEquals("}}", expander.expand("}}", ctx))
        assertEquals("}}}}{{", expander.expand("}}}}{{", ctx))
    }

    @Test
    fun `malformed default value with braces is left as-is`() {
        // 默认值内不允许花括号 → 整体不匹配 → 原样保留
        assertEquals("{{name|a{{b}}}}", expander.expand("{{name|a{{b}}}}", ctx))
    }

    @Test
    fun `trailing braces after valid token survive as text`() {
        assertEquals("00:00}", expander.expand("{{time}}}", ctx))
        assertEquals("Android{{}}", expander.expand("{{platform}}{{}}", ctx))
    }

    @Test
    fun `text before and after matches is preserved`() {
        val input = "开始 {{model_id}} 中段 纯文本 结束 {{platform}}"
        assertEquals("开始 gemini-2.0-flash 中段 纯文本 结束 Android", expander.expand(input, ctx))
    }

    @Test
    fun `expansion inside resolved value honors depth with policy`() {
        // THROW 策略 + 嵌套值中的未知变量：递归层同样抛
        registry.registerCustom("bad_outer", "内层 {{bad_inner}}")
        val thrower = PromptVariableExpander(registry, onUnknown = UnknownPolicy.THROW)
        assertThrows(UnknownPromptVariableException::class.java) {
            thrower.expand("{{bad_outer}}", ctx)
        }
        // 换 EMPTY 策略：内层未知变量清空
        val emptier = PromptVariableExpander(registry, onUnknown = UnknownPolicy.EMPTY)
        assertEquals("内层 ", emptier.expand("{{bad_outer}}", ctx))
    }
}
