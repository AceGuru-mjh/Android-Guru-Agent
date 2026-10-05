package com.apex.agent.core.code.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardAgentCatalog 目录完整性测试：
 * 角色分工 / 预算序 / 只读硬约束 / 档位映射。
 */
class StandardAgentsTest {

    @Test
    fun `catalog has six definitions covering all kinds`() {
        // v3：REVIEWER 评审画像入目录（主代理三 + 子代理三 = 六）
        assertEquals(6, StandardAgentCatalog.ALL.size)
        StandardAgentKind.entries.forEach { kind ->
            assertNotNull("missing definition for ${kind.key}", StandardAgentCatalog.definitionOf(kind))
        }
    }

    @Test
    fun `primary vs subagent split`() {
        assertEquals(
            listOf(StandardAgentKind.BUILD, StandardAgentKind.PLAN, StandardAgentKind.GENERAL),
            StandardAgentCatalog.PRIMARY_KINDS
        )
        assertEquals(
            listOf(StandardAgentKind.EXPLORE, StandardAgentKind.RESEARCH, StandardAgentKind.REVIEWER),
            StandardAgentCatalog.SUBAGENT_KINDS
        )
    }

    @Test
    fun `plan and explore are readonly profiles`() {
        assertTrue(StandardAgentCatalog.PLAN.isReadOnly)
        assertTrue(StandardAgentCatalog.EXPLORE.isReadOnly)
        assertTrue(StandardAgentCatalog.RESEARCH.isReadOnly)
        assertTrue("评审员只读", StandardAgentCatalog.REVIEWER.isReadOnly)
        assertFalse(StandardAgentCatalog.BUILD.isReadOnly)
        assertFalse(StandardAgentCatalog.GENERAL.isReadOnly)
    }

    @Test
    fun `only primary profiles include synthetic task tool`() {
        StandardAgentCatalog.ALL.forEach { def ->
            assertEquals(
                "task 合成工具只给主代理（防子代理递归派发）: ${def.kind.key}",
                def.kind.role == StandardAgentRole.PRIMARY,
                def.includeSyntheticTools
            )
        }
    }

    @Test
    fun `plan profile tool surface excludes write tools`() {
        val allow = StandardAgentCatalog.PLAN.toolAllowlist
        // 前缀白名单不包含通配空串 → 规划师不会看到 code_write / shell_execute
        assertFalse(allow.any { it.isEmpty() })
        assertTrue(allow.contains("code_read"))
        assertTrue(allow.any { it.startsWith("code_git_") })
    }

    @Test
    fun `turn budgets are ordered sanely`() {
        val build = StandardAgentCatalog.BUILD.defaultMaxTurns
        val plan = StandardAgentCatalog.PLAN.defaultMaxTurns
        val explore = StandardAgentCatalog.EXPLORE.defaultMaxTurns
        assertTrue("build 应有最大回合预算", build > plan)
        assertTrue("主代理预算 > 子代理预算", build > explore)
        assertTrue(plan > 4)
        assertTrue(explore >= 8)
    }

    @Test
    fun `primaryFor maps execution modes`() {
        assertEquals(
            StandardAgentKind.BUILD,
            StandardAgentCatalog.primaryFor("build").kind
        )
        assertEquals(
            StandardAgentKind.PLAN,
            StandardAgentCatalog.primaryFor("PLAN").kind
        )
        // 历史档（spec/reflect/...）兜底 general
        assertEquals(
            StandardAgentKind.GENERAL,
            StandardAgentCatalog.primaryFor("spec").kind
        )
    }

    @Test
    fun `kind fromKey parses and rejects garbage`() {
        assertEquals(StandardAgentKind.EXPLORE, StandardAgentKind.fromKey("explore"))
        assertEquals(StandardAgentKind.RESEARCH, StandardAgentKind.fromKey(" RESEARCH "))
        assertEquals(null, StandardAgentKind.fromKey("copilot"))
        assertEquals(null, StandardAgentKind.fromKey(null))
        assertEquals(null, StandardAgentKind.fromKey(""))
    }

    @Test
    fun `task subagent types all parse via kind catalog`() {
        // syntheticTaskTool 的 subagent_type 取值域（explore/research/general/reviewer）
        // 必须都能经 fromKey 解析成画像——工具描述与目录的跨层契约
        listOf("explore", "research", "general", "reviewer").forEach { key ->
            assertNotNull("task subagent_type '$key' 必须能解析为画像", StandardAgentKind.fromKey(key))
        }
        assertEquals(
            StandardAgentKind.GENERAL,
            StandardAgentKind.fromKey("general")
        )
    }
}
