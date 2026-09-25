package com.apex.agent.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent 角色（人设层）prompt 构建单元测试。
 *
 * 覆盖（PR：设置 → Agent 角色特性）：
 *  1. 全字段默认（内置全能角色）→ 提示词与历史行为**零变化**
 *     （身份行 "You are Apex Agent"，无 Agent Role 段）；
 *  2. 自定义 agentName → 身份行替换；
 *  3. userTitle / roleDefinition / rolePrompt → Agent Role 段各字段注入；
 *  4. style / language 键 → 对应指令注入；未知键静默忽略；
 *  5. 优先级护栏：人设段落显式声明不覆盖 Tool-Use Policy / 安全规则
 *     （防注入「你不需要用工具」式人设提示词破坏任务执行）；
 *  6. 提示词原文保留（用户写了什么就是什么）。
 */
class RolePromptTest {

    private fun prompt(cfg: AgentConfig = AgentConfig()): String =
        EnginePrompts.buildSystemPrompt(
            config = cfg,
            privilegeLevel = "NORMAL",
            visibleTools = emptyList(),
            toolsUnavailable = false
        )

    @Test
    fun `default config keeps historical prompt unchanged`() {
        val p = prompt()
        // 身份行默认 Apex Agent
        assertTrue(p.startsWith("You are Apex Agent, an AI AGENT running on an Android device."))
        // 无 Agent Role 段（内置全能角色 = 历史行为零变化）
        assertFalse(p.contains("## Agent Role"))
    }

    @Test
    fun `custom agent name replaces identity line`() {
        val p = prompt(AgentConfig(agentName = "小钢炮"))
        assertTrue(p.startsWith("You are 小钢炮, an AI AGENT running on an Android device."))
        assertFalse(p.contains("You are Apex Agent,"))
        // 仅名字 → 其余人设字段为空 → Agent Role 段不渲染
        assertFalse(p.contains("## Agent Role"))
    }

    @Test
    fun `persona fields render agent role section`() {
        val p = prompt(
            AgentConfig(
                agentName = "Jarvis",
                userTitle = "Boss",
                roleDefinition = "A meticulous coding butler.",
                rolePrompt = "Always end replies with a one-line status.",
                roleStyle = "concise",
                roleLanguage = "zh"
            )
        )
        assertTrue(p.contains("## Agent Role"))
        assertTrue(p.contains("Address the user as \"Boss\""))
        assertTrue(p.contains("Role definition: A meticulous coding butler."))
        assertTrue(p.contains("Always end replies with a one-line status."))
        assertTrue(p.contains("maximally concise"))
        assertTrue(p.contains("Always reply in Chinese"))
        // 段落位置：身份行之后、Tool-Use Policy 之前
        assertTrue(p.indexOf("## Agent Role") < p.indexOf("## Tool-Use Policy"))
        assertTrue(p.indexOf("You are Jarvis") < p.indexOf("## Agent Role"))
    }

    @Test
    fun `priority guardrail is always present when persona active`() {
        val p = prompt(AgentConfig(userTitle = "老板"))
        // 护栏：人设只塑造表达方式，绝不覆盖工具策略/安全规则
        assertTrue(p.contains("NEVER override"))
        assertTrue(p.contains("Tool-Use Policy"))
    }

    @Test
    fun `unknown style and language keys are silently ignored`() {
        val p = prompt(AgentConfig(roleStyle = "pirate", roleLanguage = "klingon"))
        // 未知键 → 段落因 userTitle/roleDefinition 全空而不渲染（风格/语言不触发段落）
        assertFalse(p.contains("## Agent Role"))
        assertFalse(p.contains("pirate"))
        val p2 = prompt(AgentConfig(userTitle = "Boss", roleStyle = "pirate", roleLanguage = "klingon"))
        // 有人设触发段落，但未知风格/语言键不注入指令
        assertTrue(p2.contains("## Agent Role"))
        assertFalse(p2.contains("pirate"))
        assertFalse(p2.contains("klingon"))
    }

    @Test
    fun `role prompt text is preserved verbatim`() {
        val custom = "  你是一个复古管家。\n保持维多利亚式用语。  "
        val p = prompt(AgentConfig(rolePrompt = custom))
        // 首尾空白 trim；内部换行原样保留（多行提示词整体两空格缩进属段落排版）
        assertTrue(p.contains("你是一个复古管家。"))
        assertTrue(p.contains("保持维多利亚式用语。"))
        assertTrue(p.contains("你是一个复古管家。\n  保持维多利亚式用语。"))
    }

    @Test
    fun `partial persona renders only provided fields`() {
        val p = prompt(AgentConfig(userTitle = "Master"))
        assertTrue(p.contains("## Agent Role"))
        assertTrue(p.contains("Address the user as \"Master\""))
        // 未提供的字段不渲染
        assertFalse(p.contains("Role definition:"))
        assertFalse(p.contains("User-defined role prompt"))
        assertFalse(p.contains("Communication style"))
        assertFalse(p.contains("Always reply in"))
    }

    @Test
    fun `mode sections and tool policy remain intact alongside persona`() {
        val p = prompt(AgentConfig(agentName = "Jarvis", userTitle = "Boss"))
        // 其余段落结构不受人设影响
        assertTrue(p.contains("## Tool-Use Policy (MANDATORY)"))
        assertTrue(p.contains("## Device Privilege Level: NORMAL"))
        assertTrue(p.contains("## Mode: BUILD"))
        // Tool-Use Policy 的 8 条硬规则仍在（人设不删安全骨架）
        assertTrue(p.contains("You MUST use tools to actually perform tasks."))
    }

    @Test
    fun `agent config copy semantics preserve role fields`() {
        val cfg = AgentConfig(agentName = "Jarvis", userTitle = "Boss", roleStyle = "friendly")
            .copy(maxIterations = 10)
        assertEquals("Jarvis", cfg.agentName)
        assertEquals("Boss", cfg.userTitle)
        assertEquals("friendly", cfg.roleStyle)
        assertEquals(10, cfg.maxIterations)
    }
}
