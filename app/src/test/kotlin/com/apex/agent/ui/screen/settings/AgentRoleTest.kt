package com.apex.agent.ui.screen.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Agent 角色（人设层）数据操作单元测试。
 *
 * 覆盖（PR：设置 → Agent 角色特性）：
 *  1. AgentSettings schema 演进：旧 JSON（无 agentRoles/activeRoleId）反序列化
 *     → 内置全能角色缺省（老用户升级零迁移）；
 *  2. 角色增改删/激活的副本语义（withRoleUpserted/withRoleRemoved/withRoleActivated）；
 *  3. 悬空 activeRoleId（角色被删）→ 诚实回落内置；
 *  4. 内置角色不可编辑/不可删除（withRoleUpserted 拒绝、ALL_ROUNDER 常量合成）；
 *  5. 序列化往返（持久化保真）。
 */
class AgentRoleTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `legacy settings json without role fields falls back to built-in`() {
        // 老用户落盘 JSON（v1.3.x，无角色字段）
        val legacy = """{"defaultMode":"build","thinkLevel":"standard","maxIterations":20}"""
        val settings = json.decodeFromString<AgentSettings>(legacy)
        assertTrue(settings.agentRoles.isEmpty())
        assertEquals(AgentRole.BUILTIN_ALL_ROUNDER_ID, settings.activeRoleId)
        // 角色视图：内置在前，激活 = 内置全能
        val active = settings.activeRole()
        assertTrue(active.isBuiltIn)
        assertEquals(1, settings.allRoles().size)
    }

    @Test
    fun `upsert inserts then updates custom role`() {
        var s = AgentSettings()
        val role = AgentRole(
            id = "role_1", name = "管家", userTitle = "老板",
            emoji = "🫖", roleDefinition = "维多利亚式管家", systemPrompt = "保持敬语",
            style = "professional", replyLanguage = "zh"
        )
        s = s.withRoleUpserted(role)
        assertEquals(1, s.agentRoles.size)
        assertEquals("role_1", s.agentRoles[0].id)

        // 更新（同 id）
        s = s.withRoleUpserted(role.copy(name = "大管家"))
        assertEquals(1, s.agentRoles.size)
        assertEquals("大管家", s.agentRoles[0].name)
    }

    @Test
    fun `upsert sanitizes fields and rejects blank name`() {
        var s = AgentSettings()
        s = s.withRoleUpserted(
            AgentRole(id = "r", name = "  老王  ", userTitle = " 师傅 ",
                emoji = "", roleDefinition = " 定 义 ", systemPrompt = " 提 示 ")
        )
        val r = s.agentRoles[0]
        assertEquals("老王", r.name)
        assertEquals("师傅", r.userTitle)
        assertEquals("🤖", r.emoji)          // 空 emoji 兜底
        assertEquals("定 义", r.roleDefinition)
        assertEquals("提 示", r.systemPrompt)

        // 空名静默拒绝（UI 层另有必填校验；数据层双保险）
        val unchanged = s.withRoleUpserted(AgentRole(id = "r2", name = "   "))
        assertEquals(s, unchanged)
    }

    @Test
    fun `built-in role cannot be upserted`() {
        val s = AgentSettings()
        val threw = runCatching {
            s.withRoleUpserted(AgentRole.ALL_ROUNDER.copy(name = "fake"))
        }.isFailure
        assertTrue("内置角色必须拒绝编辑", threw)
    }

    @Test
    fun `remove deletes custom role and falls back active to built-in`() {
        var s = AgentSettings()
            .withRoleUpserted(AgentRole(id = "r1", name = "A"))
            .withRoleUpserted(AgentRole(id = "r2", name = "B"))
            .withRoleActivated("r1")
        assertEquals("r1", s.activeRoleId)

        s = s.withRoleRemoved("r1")
        assertEquals(1, s.agentRoles.size)                       // 只剩 B
        assertEquals(AgentRole.BUILTIN_ALL_ROUNDER_ID, s.activeRoleId)  // 激活回落内置

        // 删除非激活角色不影响激活
        s = s.withRoleActivated("r2")
        s = s.withRoleRemoved(AgentRole.BUILTIN_ALL_ROUNDER_ID)  // 内置 id 不在自定义列表
        assertEquals("r2", s.activeRoleId)
    }

    @Test
    fun `activate ignores unknown ids and accepts built-in`() {
        var s = AgentSettings()
        s = s.withRoleActivated("nonexistent")
        assertEquals(AgentRole.BUILTIN_ALL_ROUNDER_ID, s.activeRoleId)
        s = s.withRoleActivated(AgentRole.BUILTIN_ALL_ROUNDER_ID)
        assertEquals(AgentRole.BUILTIN_ALL_ROUNDER_ID, s.activeRoleId)
    }

    @Test
    fun `allRoles places built-in first and activeRole resolves dangling id`() {
        var s = AgentSettings()
            .withRoleUpserted(AgentRole(id = "r1", name = "A"))
            .withRoleUpserted(AgentRole(id = "r2", name = "B"))
            .withRoleActivated("r1")   // 先激活 r1，JSON 里才有可替换的 activeRoleId 值
        val all = s.allRoles()
        assertEquals(3, all.size)
        assertTrue(all[0].isBuiltIn)   // 内置在前
        assertEquals("A", all[1].name)

        // 悬空激活 id（手改 JSON / 未来版本删除角色）→ 诚实回落
        val raw = json.encodeToString(AgentSettings.serializer(), s)
            .replace("\"activeRoleId\":\"r1\"", "\"activeRoleId\":\"gone\"")
        val dangling = json.decodeFromString<AgentSettings>(raw)
        assertEquals("gone", dangling.activeRoleId)
        assertEquals(AgentRole.ALL_ROUNDER, dangling.activeRole())
    }

    @Test
    fun `settings round-trip preserves roles`() {
        val original = AgentSettings()
            .withRoleUpserted(
                AgentRole(
                    id = "r1", name = "管家", userTitle = "老板", emoji = "🫖",
                    roleDefinition = "定义", systemPrompt = "提示",
                    style = "concise", replyLanguage = "zh"
                )
            )
            .withRoleActivated("r1")
        val restored = json.decodeFromString<AgentSettings>(
            json.encodeToString(AgentSettings.serializer(), original)
        )
        assertEquals(original, restored)
        assertEquals("管家", restored.activeRole().name)
        assertEquals("老板", restored.activeRole().userTitle)
    }
}
