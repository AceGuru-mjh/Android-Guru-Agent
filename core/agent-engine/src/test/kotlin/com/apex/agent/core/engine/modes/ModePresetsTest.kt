package com.apex.agent.core.engine.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #168 自定义模式预设单测 —— 内置完整性、Store 往返、选中解析、
 * upsert/remove/duplicate 操作、旧单串指令迁移（幂等）。
 */
class ModePresetsTest {

    // ═══ 内置预设完整性 ═══

    @Test
    fun `builtin presets contain exactly four well-formed entries`() {
        assertEquals(4, BuiltinModePresets.ALL.size)
        assertEquals(4, BuiltinModePresets.ALL.map { it.id }.toSet().size) // id 唯一
        BuiltinModePresets.ALL.forEach { preset ->
            assertTrue("内置预设 ${preset.id} 应标记 builtin", preset.builtin)
            assertTrue("内置预设 ${preset.id} 应有名称", preset.name.isNotBlank())
            assertTrue(
                "内置预设 ${preset.id} 指令应 ≥5 行（高质量提示词）",
                preset.instruction.lineSequence().count { it.isNotBlank() } >= 5
            )
            assertTrue(preset.id.startsWith(ModePreset.BUILTIN_ID_PREFIX))
        }
    }

    @Test
    fun `builtin byId resolves known ids and rejects unknown`() {
        assertNotNull(BuiltinModePresets.byId("builtin_translator"))
        assertNotNull(BuiltinModePresets.byId("builtin_brainstorm"))
        assertNull(BuiltinModePresets.byId("builtin_nonexistent"))
    }

    @Test
    fun `builtin instructions use hard constraint style`() {
        // MUST/NEVER 级别措辞（提示词工程可执行性约束，非建议式）
        val all = BuiltinModePresets.ALL.joinToString("\n") { it.instruction }
        assertTrue(all.contains("NEVER") || all.contains("MUST"))
    }

    // ═══ Store 往返 ═══

    @Test
    fun `in-memory store round-trips save and load`() {
        val store = InMemoryModePresetStore()
        assertTrue(store.load().isEmpty())

        val presets = listOf(
            ModePreset(id = "p1", name = "one", instruction = "i1", createdAt = 2),
            ModePreset(id = "p2", name = "two", instruction = "i2", createdAt = 1)
        )
        store.save(presets)
        assertEquals(presets, store.load())

        // save 是全量覆写（不是追加）
        store.save(listOf(presets[0]))
        assertEquals(1, store.load().size)
    }

    @Test
    fun `in-memory store returns snapshot copies`() {
        val store = InMemoryModePresetStore(
            listOf(ModePreset(id = "p1", name = "one", instruction = "i1"))
        )
        val snapshot = store.load()
        store.save(emptyList()) // 改动 store 不应影响先前快照
        assertEquals(1, snapshot.size)
    }

    // ═══ 生效全集与选中解析 ═══

    @Test
    fun `effective presets put builtins first then user presets by createdAt`() {
        val user = listOf(
            ModePreset(id = "u2", name = "newer", instruction = "i", createdAt = 200),
            ModePreset(id = "u1", name = "older", instruction = "i", createdAt = 100)
        )
        val all = effectiveModePresets(user)
        assertEquals(6, all.size) // 4 内置 + 2 用户
        assertEquals(4, all.take(4).count { it.builtin }) // 内置在前
        assertEquals(listOf("u1", "u2"), all.drop(4).map { it.id }) // 用户按时间序
    }

    @Test
    fun `selected preset resolves builtin and user ids`() {
        val user = listOf(ModePreset(id = "u1", name = "mine", instruction = "custom-instr"))
        assertEquals(
            "builtin_translator",
            selectedModePreset(user, "builtin_translator")?.id
        )
        assertEquals("custom-instr", selectedModePreset(user, "u1")?.instruction)
    }

    @Test
    fun `dangling or blank selection resolves to null`() {
        val user = listOf(ModePreset(id = "u1", name = "mine", instruction = "i"))
        assertNull(selectedModePreset(user, ""))            // 未选
        assertNull(selectedModePreset(user, "deleted_id"))  // 悬空（被删）
        assertNull(selectedModePreset(emptyList(), ""))     // 无预设
    }

    // ═══ upsert / remove / duplicate ═══

    @Test
    fun `upsert appends new preset and overwrites existing by id`() {
        val initial = listOf(ModePreset(id = "p1", name = "v1", instruction = "i1"))
        // 新建：追加
        val appended = upsertModePreset(initial, ModePreset(id = "p2", name = "new", instruction = "i2"))
        assertEquals(listOf("p1", "p2"), appended.map { it.id })
        // 编辑：同 id 覆盖（位置不变）
        val updated = upsertModePreset(appended, ModePreset(id = "p1", name = "v1-edited", instruction = "i1-new"))
        assertEquals(listOf("p1", "p2"), updated.map { it.id })
        assertEquals("v1-edited", updated[0].name)
        assertEquals("i1-new", updated[0].instruction)
        // 入参不被修改（纯函数）
        assertEquals("v1", initial[0].name)
    }

    @Test
    fun `remove filters out only the target id`() {
        val presets = listOf(
            ModePreset(id = "p1", name = "one", instruction = "i"),
            ModePreset(id = "p2", name = "two", instruction = "i")
        )
        assertEquals(listOf("p2"), removeModePreset(presets, "p1").map { it.id })
        assertEquals(presets, removeModePreset(presets, "missing")) // 无此 id 原样返回
    }

    @Test
    fun `duplicate as custom gets new id and drops builtin flag`() {
        val builtin = BuiltinModePresets.TRANSLATOR
        val copy = builtin.duplicateAsCustom()
        assertFalse(copy.id == builtin.id)
        assertFalse(copy.builtin)
        assertTrue(copy.instruction == builtin.instruction) // 指令原样带走
        assertTrue(copy.name.startsWith(builtin.name))
    }

    // ═══ 旧单串指令迁移 ═══

    @Test
    fun `legacy instruction migrates into a named preset when list is empty`() {
        val migrated = migrateLegacyCustomInstruction(emptyList(), "永远用中文回答")
        assertNotNull(migrated)
        assertEquals(ModePreset.MIGRATED_PRESET_ID, migrated!!.id)
        assertEquals("永远用中文回答", migrated.instruction)
        assertFalse(migrated.builtin)
    }

    @Test
    fun `migration is skipped for blank legacy instruction`() {
        assertNull(migrateLegacyCustomInstruction(emptyList(), null))
        assertNull(migrateLegacyCustomInstruction(emptyList(), ""))
        assertNull(migrateLegacyCustomInstruction(emptyList(), "   "))
    }

    @Test
    fun `migration never overwrites existing user presets`() {
        val existing = listOf(ModePreset(id = "p1", name = "mine", instruction = "i"))
        assertNull(migrateLegacyCustomInstruction(existing, "旧指令"))
    }

    @Test
    fun `migration is idempotent when migrated preset already exists`() {
        val already = listOf(
            ModePreset(id = ModePreset.MIGRATED_PRESET_ID, name = "已迁移", instruction = "i")
        )
        assertNull(migrateLegacyCustomInstruction(already, "旧指令"))
    }

    // ═══ summary 工具 ═══

    @Test
    fun `summary takes first non-blank line with truncation`() {
        val preset = ModePreset(
            id = "p",
            name = "n",
            instruction = "\n\n" + "很长的指令首行".repeat(20) + "\n第二行"
        )
        val summary = preset.summary(maxChars = 30)
        assertTrue(summary.length <= 31) // 30 + 省略号
        assertTrue(summary.endsWith("…"))
        // 空指令 → 空摘要
        assertTrue(ModePreset(id = "p", name = "n", instruction = " \n ").summary().isEmpty())
    }

    @Test
    fun `newId generates preset-prefixed unique ids`() {
        val a = ModePreset.newId()
        val b = ModePreset.newId()
        assertTrue(a.startsWith("preset_"))
        assertFalse(a == b)
    }
}
