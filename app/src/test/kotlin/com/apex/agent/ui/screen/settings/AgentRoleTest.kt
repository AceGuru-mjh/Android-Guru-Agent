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

    // ═══ v6 Coding 专家模板（用户规格：全栈置顶 / Git / Android / 各语言专家）═══

    @Test
    fun `coding experts pin full-stack first with git and android specialists`() {
        val experts = AgentRole.CODING_EXPERTS
        // 置顶全栈 + Git 角色 + Android 专家（用户点名的三个）
        assertEquals(AgentRole.BUILTIN_CODING_FULL_STACK_ID, experts[0].id)
        assertEquals("builtin_coding_git", experts[1].id)
        assertEquals("builtin_coding_android", experts[2].id)
        // 全部内置（不可删/编辑）且定义段非空（引擎人设通道有料）
        experts.forEach { e ->
            assertTrue("${e.id} 必须内置", e.isBuiltIn)
            assertTrue("${e.id} 定义不能为空", e.roleDefinition.isNotBlank())
        }
        // 每门主流语言都有一个专家：id 覆盖核对（用户规格逐语言点验）
        val ids = experts.map { it.id }.toSet()
        listOf(
            "builtin_coding_kotlin", "builtin_coding_java", "builtin_coding_python",
            "builtin_coding_javascript", "builtin_coding_typescript", "builtin_coding_go",
            "builtin_coding_rust", "builtin_coding_cpp", "builtin_coding_csharp",
            "builtin_coding_swift", "builtin_coding_php", "builtin_coding_ruby",
            "builtin_coding_sql", "builtin_coding_shell", "builtin_coding_frontend"
        ).forEach { id ->
            assertTrue("缺少语言专家 $id", id in ids)
        }
    }

    @Test
    fun `coding roles list places built-in experts before custom roles`() {
        var s = AgentSettings()
            .withRoleUpserted(AgentRole(id = "r1", name = "自定义"))
        val list = s.codingRoles()
        // 内置专家在前（全栈置顶），自定义接续可用（跨模式复用）
        assertEquals(AgentRole.BUILTIN_CODING_FULL_STACK_ID, list[0].id)
        assertEquals(list.size, AgentRole.CODING_EXPERTS.size + 1)
        assertEquals("自定义", list.last().name)
    }

    @Test
    fun `activeCodingRole defaults to full-stack and resolves dangling id`() {
        // 缺省：全栈置顶
        assertEquals(
            AgentRole.BUILTIN_CODING_FULL_STACK_ID,
            AgentSettings().activeCodingRole().id
        )
        // 激活 Git 专家
        var s = AgentSettings().withCodingRoleActivated("builtin_coding_git")
        assertEquals("builtin_coding_git", s.codeActiveRoleId)
        assertEquals("Git 专家", s.activeCodingRole().name)
        // 悬空 id（手改 JSON）→ 诚实回落全栈
        val raw = json.encodeToString(AgentSettings.serializer(), s)
            .replace("builtin_coding_git", "gone")
        val dangling = json.decodeFromString<AgentSettings>(raw)
        assertEquals("gone", dangling.codeActiveRoleId)
        assertEquals(
            AgentRole.BUILTIN_CODING_FULL_STACK_ID,
            dangling.activeCodingRole().id
        )
    }

    @Test
    fun `coding role activation ignores unknown ids and accepts custom roles`() {
        var s = AgentSettings()
            .withRoleUpserted(AgentRole(id = "r1", name = "自定义"))
        // 未知 id 静默忽略
        s = s.withCodingRoleActivated("nonexistent")
        assertEquals(AgentRole.BUILTIN_CODING_FULL_STACK_ID, s.codeActiveRoleId)
        // 自定义角色也能在 Coding 工位激活（跨模式复用）
        s = s.withCodingRoleActivated("r1")
        assertEquals("r1", s.codeActiveRoleId)
        assertEquals("自定义", s.activeCodingRole().name)
    }

    @Test
    fun `legacy settings json gets default coding role without migration`() {
        // 老用户落盘 JSON（v6 之前，无 codeActiveRoleId）→ 反序列化即全栈缺省
        val legacy = "{\"defaultMode\":\"build\",\"thinkLevel\":\"standard\",\"maxIterations\":20}"
        val settings = json.decodeFromString<AgentSettings>(legacy)
        assertEquals(AgentRole.BUILTIN_CODING_FULL_STACK_ID, settings.codeActiveRoleId)
        // 与 Agent 屏角色互不干扰
        assertEquals(AgentRole.BUILTIN_ALL_ROUNDER_ID, settings.activeRoleId)
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
