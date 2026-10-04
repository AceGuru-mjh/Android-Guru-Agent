package com.apex.agent.core.engine

import com.apex.agent.core.engine.orchestrator.OrchestratorPrompts
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability Introspection 提示词组装测试。
 *
 * 覆盖（PR：能力自省与自主扩展）：
 * 1. 系统提示词新增 "## Capability Expansion Playbook" 段：六条扩展梯度
 *    （工具目录 / capability_report 自省 / 技能市场 / MCP / Linux 工具链 /
 *    提权请求）+ 「验证后再报成功」与「没爬完梯度不许说不可能」护栏；
 * 2. 权限段换源 PrivilegeLadder（阶梯全貌 + 升级路径 + 不盲试护栏），
 *    heading 保留原始串（既有测试兼容）；
 * 3. Tool-Use Policy 第 8 条指向扩展攻略；
 * 4. 段落顺序：权限段 → 扩展攻略 → Live Environment；
 * 5. BUILD 编排线（OrchestratorPrompts）同步：权限单行简介 + 升级指引 +
 *    扩展通道提示（旧版只有一行裸等级）。
 */
class CapabilityPromptTest {

    private fun prompt(cfg: AgentConfig = AgentConfig(), privilege: String = "SHIZUKU"): String =
        EnginePrompts.buildSystemPrompt(
            config = cfg,
            privilegeLevel = privilege,
            visibleTools = emptyList(),
            toolsUnavailable = false
        )

    // ── Capability Expansion Playbook ─────────────────────────

    @Test
    fun `playbook section lists all six expansion routes`() {
        val p = prompt()
        assertTrue(p.contains("## Capability Expansion Playbook (MANDATORY before saying I can't)"))
        // 六条梯度逐一在场
        assertTrue(p.contains("1. INSTALLED TOOLS"))
        assertTrue(p.contains("2. LIVE SELF-CHECK"))
        assertTrue(p.contains("capability_report()"))
        assertTrue(p.contains("3. SKILLS"))
        assertTrue(p.contains("market_search(query)"))
        assertTrue(p.contains("4. MCP SERVERS"))
        assertTrue(p.contains("5. LINUX TOOLCHAIN"))
        assertTrue(p.contains("terminal.ubuntu.ensure"))
        assertTrue(p.contains("6. PRIVILEGE WALL"))
    }

    @Test
    fun `playbook carries verify-before-success and never-impossible guards`() {
        val p = prompt()
        assertTrue(p.contains("VERIFY it took effect"))
        assertTrue(p.contains("Never claim a task is impossible before routes 1-5 have been tried"))
    }

    @Test
    fun `playbook is static and present with default config`() {
        // 无任何可选参数（既有调用方零变化路径）也注入攻略段
        val p = EnginePrompts.buildSystemPrompt(
            config = AgentConfig(),
            privilegeLevel = "NORMAL",
            visibleTools = emptyList(),
            toolsUnavailable = false
        )
        assertTrue(p.contains("## Capability Expansion Playbook"))
    }

    @Test
    fun `chat mode omits the playbook to avoid tool-routing contradiction`() {
        // CHAT 模式零工具（EMPTY_TOOL_PLAN）：攻略指向的工具全部不可调用，
        // 注入会与「建议切换 AGENT 模式」的引导自相矛盾 → 必须省略。
        val p = EnginePrompts.buildSystemPrompt(
            config = AgentConfig(mode = AgentMode.CHAT),
            privilegeLevel = "NORMAL",
            visibleTools = emptyList(),
            toolsUnavailable = false
        )
        assertTrue(!p.contains("## Capability Expansion Playbook"))
        // 权限阶梯段仍在（知道自己是谁与权限无关，保持历史行为）
        assertTrue(p.contains("## Device Privilege Level: NORMAL"))
    }

    // ── 权限阶梯段（换源 PrivilegeLadder）────────────────────

    @Test
    fun `privilege section renders ladder and upgrade path`() {
        val p = prompt(privilege = "SHIZUKU")
        assertTrue(p.contains("## Device Privilege Level: SHIZUKU"))
        assertTrue(p.contains("NORMAL_SHELL < SHIZUKU < ROOT"))
        assertTrue(p.contains("Upgrade path:"))
        assertTrue(p.contains("Never blind-retry"))
    }

    @Test
    fun `privilege heading keeps raw string while body normalizes`() {
        // 历史行为：heading 用调用方原始串（RolePromptTest 断言 NORMAL）
        val p = prompt(privilege = "NORMAL")
        assertTrue(p.contains("## Device Privilege Level: NORMAL"))
        // 正文按规范化等级渲染
        assertTrue(p.contains("yours: NORMAL_SHELL"))
    }

    // ── Tool-Use Policy 第 8 条 ───────────────────────────────

    @Test
    fun `tool use policy points to the playbook`() {
        val p = prompt()
        assertTrue(p.contains("climb the Capability Expansion Playbook"))
    }

    // ── 段落顺序 ─────────────────────────────────────────────

    @Test
    fun `playbook sits between privilege and live environment`() {
        val p = EnginePrompts.buildSystemPrompt(
            config = AgentConfig(),
            privilegeLevel = "ROOT",
            visibleTools = emptyList(),
            environmentSummary = "environment: network=on",
            toolsUnavailable = false
        )
        val privilegeIdx = p.indexOf("## Device Privilege Level")
        val playbookIdx = p.indexOf("## Capability Expansion Playbook")
        val envIdx = p.indexOf("## Live Environment")
        assertTrue(privilegeIdx in 0 until playbookIdx)
        assertTrue(playbookIdx < envIdx)
    }

    // ── BUILD 编排线（OrchestratorPrompts）────────────────────

    @Test
    fun `orchestrator prompt carries privilege brief upgrade and expansion routes`() {
        val provider = object : PrivilegeInfoProvider {
            override fun currentLevel(): String = "SHIZUKU"
        }
        val p = OrchestratorPrompts.buildSystemPrompt(
            config = AgentConfig(mode = AgentMode.BUILD),
            privilegeInfoProvider = provider
        )
        // 权限单行简介（替代旧版裸等级）+ 升级指引
        assertTrue(p.contains("Privilege: SHIZUKU"))
        assertTrue(p.contains("CAN "))
        assertTrue(p.contains("CANNOT "))
        // 扩展通道
        assertTrue(p.contains("tool_search/tool_open"))
        assertTrue(p.contains("skill_install"))
        assertTrue(p.contains("mcp_connect"))
        assertTrue(p.contains("capability_report()"))
        assertTrue(p.contains("Never declare a task impossible"))
    }

    @Test
    fun `orchestrator prompt without provider still carries expansion routes`() {
        val p = OrchestratorPrompts.buildSystemPrompt(
            config = AgentConfig(mode = AgentMode.BUILD),
            privilegeInfoProvider = null
        )
        // 无权限提供者 → 无权限段，但扩展通道仍在
        assertTrue(p.contains("On-demand capability expansion"))
        assertTrue(!p.contains("Privilege: "))
    }
}
