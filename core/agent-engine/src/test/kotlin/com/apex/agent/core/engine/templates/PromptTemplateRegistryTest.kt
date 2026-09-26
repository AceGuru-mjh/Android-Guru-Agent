package com.apex.agent.core.engine.templates

import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 4-b — 提示词模板注册表测试。
 *
 * 覆盖：roundtrip 字段抽查（防 equals 掩盖序列化丢字段）、upsert 盖戳、
 * 内置删除保护与重播种幂等、损坏 JSON 备份、导入 MERGE/REPLACE、
 * id 校验、原子写无 temp 残留、onChanged 回调、isBuiltIn 规范化。
 */
class PromptTemplateRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private var now = 1000L
    private val logs = mutableListOf<String>()

    private lateinit var registry: PromptTemplateRegistry

    @Before
    fun setup() {
        dir = tmp.newFolder("templates")
        registry = PromptTemplateRegistry(dir, clock = { now }, logger = { logs.add(it) })
    }

    private fun sampleTemplate(
        id: String = "tpl_sample",
        content: String = "请审查 {{code}}（语言 {{language}}）"
    ): PromptTemplate = PromptTemplate(
        id = id,
        name = "代码评审",
        description = "结构化评审模板",
        category = TemplateCategory.CODING,
        content = content,
        variables = listOf(
            TemplateVariable("code", "待评审代码", required = true),
            TemplateVariable("language", "代码语言", required = false, defaultValue = "未指定")
        ),
        tags = listOf("代码", "评审"),
        isBuiltIn = false,
        createdAt = 1111L,
        updatedAt = 2222L,
        usageCount = 7L
    )

    private fun storageFiles(): List<String> = dir.listFiles()!!.map { it.name }.sorted()

    /** 断言挂起函数抛 IllegalArgumentException（assertThrows 的 lambda 不是协程体，需手写）。 */
    private suspend fun assertIllegalArgument(action: suspend () -> Unit) {
        try {
            action()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期异常
        }
    }

    // ═══ Roundtrip ═══

    @Test
    fun `save-load roundtrip preserves all fields`() = runTest {
        registry.save(sampleTemplate())
        // 新实例走真实磁盘加载（不是同一内存缓存）
        val fresh = PromptTemplateRegistry(dir)
        val loaded = fresh.get("tpl_sample")
        assertNotNull(loaded)
        // 字段抽样（防 equals 掩盖序列化丢字段）
        assertEquals("tpl_sample", loaded!!.id)
        assertEquals("代码评审", loaded.name)
        assertEquals("结构化评审模板", loaded.description)
        assertEquals(TemplateCategory.CODING, loaded.category)
        assertEquals("请审查 {{code}}（语言 {{language}}）", loaded.content)
        assertEquals(
            listOf(
                TemplateVariable("code", "待评审代码", required = true),
                TemplateVariable("language", "代码语言", required = false, defaultValue = "未指定")
            ),
            loaded.variables
        )
        assertEquals(listOf("代码", "评审"), loaded.tags)
        assertFalse(loaded.isBuiltIn)
        assertEquals(1111L, loaded.createdAt)
        assertEquals(1000L, loaded.updatedAt) // save 盖当前假钟戳
        assertEquals(7L, loaded.usageCount)
        assertEquals(setOf("code", "language"), loaded.referencedVariables())
    }

    @Test
    fun `get with illegal id returns null without throwing`() = runTest {
        registry.save(sampleTemplate())
        assertNull(registry.get("../evil"))
        assertNull(registry.get("NOT_LOWER"))
        assertNull(registry.get(""))
        assertNull(registry.get("不存在的模板"))
    }

    // ═══ Upsert 盖戳 ═══

    @Test
    fun `upsert bumps updatedAt and preserves createdAt`() = runTest {
        now = 1000L
        // createdAt 缺省（0）→ 新建时补时钟戳
        registry.save(sampleTemplate(id = "tpl_up").copy(createdAt = 0))
        assertEquals(1000L, registry.get("tpl_up")!!.createdAt)
        assertEquals(1000L, registry.get("tpl_up")!!.updatedAt)

        now = 2000L
        registry.save(sampleTemplate(id = "tpl_up", content = "第二版 {{code}}"))
        val updated = registry.get("tpl_up")!!
        assertEquals("第二版 {{code}}", updated.content)
        assertEquals(1000L, updated.createdAt) // 已有条目的 createdAt 以库内为准（忽略本次传入的 1111）
        assertEquals(2000L, updated.updatedAt)

        // 落盘验证（新实例读取）
        assertEquals(2000L, PromptTemplateRegistry(dir).get("tpl_up")!!.updatedAt)
        // 单文件、无残留
        assertEquals(listOf("templates.json"), storageFiles())
    }

    // ═══ 原子写 ═══

    @Test
    fun `atomic write leaves no tmp files on success`() = runTest {
        registry.save(sampleTemplate())
        registry.save(sampleTemplate(id = "tpl_two"))
        registry.delete("tpl_two")
        assertEquals(listOf("templates.json"), storageFiles())
    }

    @Test
    fun `half-written tmp residue is cleaned on first load`() = runTest {
        registry.save(sampleTemplate())
        // 模拟崩溃残留 temp（真实半写内容）
        File(dir, "templates.json.tmp").writeText("""{"templates":[ partial""")
        val fresh = PromptTemplateRegistry(dir, logger = { logs.add(it) })
        assertEquals(1, fresh.list().size)
        assertFalse(File(dir, "templates.json.tmp").exists())
        assertTrue(logs.any { it.contains("temp") })
    }

    // ═══ 损坏容错 ═══

    @Test
    fun `corrupt json is backed up and library starts empty`() = runTest {
        registry.save(sampleTemplate())
        // 运行时制造损坏（截断 JSON）
        val raw = File(dir, "templates.json").readText()
        File(dir, "templates.json").writeText(raw.substring(0, raw.length / 2))

        val fresh = PromptTemplateRegistry(dir, logger = { logs.add(it) })
        assertTrue(fresh.list().isEmpty())
        assertTrue(File(dir, "templates.json.corrupt").exists())
        assertFalse(File(dir, "templates.json").exists())
        assertTrue(logs.any { it.contains("corrupt") })

        // 损坏后仍可正常保存（空库重启）
        now = 5000L
        fresh.save(sampleTemplate(id = "tpl_after_recover"))
        assertEquals(1, fresh.list().size)
        assertEquals(listOf("templates.json", "templates.json.corrupt"), storageFiles())
    }

    @Test
    fun `load tolerates unknown fields - forward compatibility`() = runTest {
        registry.save(sampleTemplate())
        val raw = File(dir, "templates.json").readText()
        // 尾部追加未来字段（右花括号用 \u007D 表达，保持源码括号配对平衡）
        val patched = raw.dropLast(1) + ",\"futureField\":42" + '\u007D'
        File(dir, "templates.json").writeText(patched)
        val loaded = PromptTemplateRegistry(dir).get("tpl_sample")
        assertNotNull(loaded)
        assertEquals("代码评审", loaded!!.name)
    }

    // ═══ 校验 ═══

    @Test
    fun `save rejects illegal ids and blank fields`() = runTest {
        assertIllegalArgument { registry.save(sampleTemplate(id = "UPPER")) }
        assertIllegalArgument { registry.save(sampleTemplate(id = "has space")) }
        assertIllegalArgument { registry.save(sampleTemplate(id = "../evil")) }
        assertIllegalArgument { registry.save(sampleTemplate(id = "a".repeat(65))) }
        assertIllegalArgument { registry.save(sampleTemplate().copy(name = "  ")) }
        assertIllegalArgument { registry.save(sampleTemplate().copy(content = "")) }
        // 校验失败不落盘
        assertTrue(storageFiles().isEmpty())
    }

    // ═══ 删除保护 ═══

    @Test
    fun `built-in delete is refused and custom delete works`() = runTest {
        registry.reseedBuiltIns()
        val before = registry.list().size
        assertFalse(registry.delete("builtin_code_review"))
        assertNotNull(registry.get("builtin_code_review"))
        assertEquals(before, registry.list().size)

        registry.save(sampleTemplate(id = "tpl_custom"))
        assertTrue(registry.delete("tpl_custom"))
        assertNull(registry.get("tpl_custom"))
        assertEquals(before, registry.list().size)
    }

    @Test
    fun `delete unknown id returns false`() = runTest {
        assertFalse(registry.delete("tpl_never_saved"))
    }

    // ═══ 内置播种 ═══

    @Test
    fun `reseedBuiltIns is idempotent and inserts only when missing`() = runTest {
        assertEquals(10, registry.reseedBuiltIns())
        assertEquals(0, registry.reseedBuiltIns())
        assertEquals(0, registry.reseedBuiltIns(force = true)) // 出厂态原样 → 无改动

        val templates = registry.list()
        assertEquals(10, templates.size)
        assertEquals(10, templates.map { it.id }.toSet().size)
        assertTrue(templates.all { it.isBuiltIn })
        assertEquals(10, PromptTemplateRegistry(dir).list().size) // 落盘确认
    }

    @Test
    fun `reseedBuiltIns force restores tampered built-in content`() = runTest {
        registry.reseedBuiltIns()
        val pristine = registry.get("builtin_code_review")!!
        // 用户借 save 改写内置内容（允许定制，但保护位不丢）
        now = 5000L
        registry.save(pristine.copy(content = "被改坏的正文", usageCount = 9))
        val tampered = registry.get("builtin_code_review")!!
        assertEquals("被改坏的正文", tampered.content)
        assertTrue(tampered.isBuiltIn)
        assertEquals(9L, tampered.usageCount)

        // 非强制重播种：已有条目（即使被改）不覆盖
        assertEquals(0, registry.reseedBuiltIns())
        assertEquals("被改坏的正文", registry.get("builtin_code_review")!!.content)

        // 强制重播种：还原出厂定义
        assertEquals(1, registry.reseedBuiltIns(force = true))
        val restored = registry.get("builtin_code_review")!!
        assertEquals(pristine, restored)
        assertFalse(restored.content.contains("被改坏"))
    }

    @Test
    fun `save cannot strip built-in protection and normal ids cannot gain it`() = runTest {
        registry.reseedBuiltIns()
        // 普通 id 声称 isBuiltIn=true → 落库时强制 false
        registry.save(sampleTemplate(id = "tpl_fake_builtin").copy(isBuiltIn = true))
        assertFalse(registry.get("tpl_fake_builtin")!!.isBuiltIn)
        assertTrue(registry.delete("tpl_fake_builtin"))

        // 落盘侧同样规范化：手写 JSON 把 isBuiltIn 改为 true → 读取时纠正为 false
        registry.save(sampleTemplate(id = "tpl_handwritten"))
        val raw = File(dir, "templates.json").readText()
        val patched = raw.replace("\"isBuiltIn\":false", "\"isBuiltIn\":true")
        File(dir, "templates.json").writeText(patched)
        assertFalse(PromptTemplateRegistry(dir).get("tpl_handwritten")!!.isBuiltIn)
    }

    @Test
    fun `built-ins cover five categories with consistent variable declarations`() {
        val byCategory = BuiltinPromptTemplates.ALL.groupBy { it.category }
        assertEquals(5, byCategory.keys.size)
        assertEquals(5, byCategory[TemplateCategory.CODING]!!.size)
        assertNotNull(byCategory[TemplateCategory.WRITING])
        assertNotNull(byCategory[TemplateCategory.TRANSLATION])
        assertNotNull(byCategory[TemplateCategory.ANALYSIS])
        assertNotNull(byCategory[TemplateCategory.PRODUCTIVITY])
        // 声明变量与正文引用一一对应（模板引擎缺失判定的前提）
        for (t in BuiltinPromptTemplates.ALL) {
            assertEquals("declared vs referenced mismatch: ${t.id}", t.variables.map { it.name }.toSet(), t.referencedVariables())
        }
        assertTrue(BuiltinPromptTemplates.ALL.all { it.id.startsWith(PromptTemplate.BUILTIN_ID_PREFIX) })
        assertEquals(10, BuiltinPromptTemplates.BUILTIN_IDS.size)
    }

    // ═══ 列表与分类 ═══

    @Test
    fun `list filters by category`() = runTest {
        registry.reseedBuiltIns()
        registry.save(sampleTemplate(id = "tpl_mine"))
        assertEquals(11, registry.list().size)
        assertEquals(6, registry.list(TemplateCategory.CODING).size) // 5 内置 + 1 自定义
        assertEquals(1, registry.list(TemplateCategory.TRANSLATION).size)
        assertEquals(0, registry.list(TemplateCategory.CUSTOM).size)
        assertTrue(registry.list(TemplateCategory.CODING).all { it.category == TemplateCategory.CODING })
    }

    // ═══ 使用计数 ═══

    @Test
    fun `incrementUsage bumps count without touching updatedAt`() = runTest {
        now = 1000L
        registry.save(sampleTemplate(id = "tpl_usage").copy(usageCount = 0))
        registry.incrementUsage("tpl_usage")
        registry.incrementUsage("tpl_usage")
        val t = registry.get("tpl_usage")!!
        assertEquals(2L, t.usageCount)
        assertEquals(1000L, t.updatedAt)
        // 未知 id 无操作不抛
        registry.incrementUsage("tpl_missing")
        // 落盘确认
        assertEquals(2L, PromptTemplateRegistry(dir).get("tpl_usage")!!.usageCount)
    }

    // ═══ 导入 / 导出 ═══

    private val prettyJson = Json { encodeDefaults = true }

    private fun libraryJson(vararg templates: PromptTemplate): String =
        prettyJson.encodeToString(TemplateLibrary.serializer(), TemplateLibrary(templates.toList()))

    @Test
    fun `exportLibrary produces pretty json that re-imports`() = runTest {
        registry.reseedBuiltIns()
        registry.save(sampleTemplate())
        val exported = registry.exportLibrary()
        assertTrue(exported.contains("\"tpl_sample\""))
        assertTrue(exported.contains('\n')) // pretty print 换行

        val other = PromptTemplateRegistry(tmp.newFolder("other"))
        val result = other.importLibrary(exported, ImportMode.MERGE)
        assertTrue(result.ok)
        assertEquals(1, result.importedCount) // 内置 10 条按规则跳过
        assertEquals(10, result.skippedBuiltInCount)
        // MERGE 不会播种内置：空库导入后只剩自定义模板
        assertEquals(1, other.list().size)
        val reimported = other.get("tpl_sample")!!
        assertEquals(7L, reimported.usageCount)
        assertEquals(1111L, reimported.createdAt)
    }

    @Test
    fun `import MERGE overwrites same-id custom but never built-ins`() = runTest {
        registry.reseedBuiltIns()
        registry.save(sampleTemplate(id = "tpl_merge"))
        val pristineContent = registry.get("builtin_code_review")!!.content

        val payload = libraryJson(
            sampleTemplate(id = "tpl_merge").copy(content = "MERGE 后的正文"),
            sampleTemplate(id = "tpl_new_from_import"),
            PromptTemplate(
                id = "builtin_code_review",
                name = "伪造内置",
                content = "恶意替换内容",
                isBuiltIn = true
            ),
            sampleTemplate(id = "Invalid Id!").copy(name = "x", content = "y")
        )
        val result = registry.importLibrary(payload, ImportMode.MERGE)

        assertTrue(result.ok)
        assertEquals(2, result.importedCount)
        assertEquals(1, result.skippedBuiltInCount)
        assertEquals(1, result.skippedInvalidCount)

        assertEquals("MERGE 后的正文", registry.get("tpl_merge")!!.content)
        assertNotNull(registry.get("tpl_new_from_import"))
        // 内置模板原封不动
        val builtin = registry.get("builtin_code_review")!!
        assertEquals(pristineContent, builtin.content)
        assertTrue(builtin.isBuiltIn)
        // 导入条目一律为自定义（防伪内置）
        assertFalse(registry.get("tpl_new_from_import")!!.isBuiltIn)
        assertEquals(12, registry.list().size) // 10 内置 + 2 自定义
    }

    @Test
    fun `import REPLACE swaps the whole library`() = runTest {
        registry.reseedBuiltIns()
        registry.save(sampleTemplate(id = "tpl_old"))

        val payload = libraryJson(
            sampleTemplate(id = "tpl_replaced_a"),
            sampleTemplate(id = "tpl_replaced_b")
        )
        val result = registry.importLibrary(payload, ImportMode.REPLACE)
        assertTrue(result.ok)
        assertEquals(2, result.importedCount)
        assertNull(registry.get("tpl_old"))
        assertEquals(listOf("tpl_replaced_a", "tpl_replaced_b"), registry.list().map { it.id })

        // REPLACE 把内置一并清空 → reseedBuiltIns 可还原（逃生通道）
        assertEquals(10, registry.reseedBuiltIns())
        assertEquals(12, registry.list().size)

        // 落盘确认
        assertEquals(12, PromptTemplateRegistry(dir).list().size)
    }

    @Test
    fun `import REPLACE drops forged built-in entries`() = runTest {
        val payload = libraryJson(
            PromptTemplate(id = "builtin_translate", name = "伪造", content = "伪造内容", isBuiltIn = true),
            sampleTemplate(id = "tpl_real")
        )
        val result = registry.importLibrary(payload, ImportMode.REPLACE)
        assertEquals(1, result.importedCount)
        assertEquals(1, result.skippedBuiltInCount)
        assertEquals(listOf("tpl_real"), registry.list().map { it.id })
        assertNull(registry.get("builtin_translate"))
    }

    @Test
    fun `import corrupt json fails defensively without throwing`() = runTest {
        registry.reseedBuiltIns()
        val result = registry.importLibrary("这不是 JSON{{{", ImportMode.MERGE)
        assertFalse(result.ok)
        assertNotNull(result.error)
        assertEquals(0, result.importedCount)
        // 原库不受影响
        assertEquals(10, registry.list().size)
        assertTrue(logs.any { it.contains("parse failed") })
    }

    @Test
    fun `import fills missing timestamps with clock`() = runTest {
        now = 7000L
        val payload = libraryJson(
            PromptTemplate(id = "tpl_no_ts", name = "无时间戳", content = "正文 {{x}}")
        )
        registry.importLibrary(payload, ImportMode.MERGE)
        val t = registry.get("tpl_no_ts")!!
        assertEquals(7000L, t.createdAt)
        assertEquals(7000L, t.updatedAt)
    }

    // ═══ onChanged 回调 ═══

    @Test
    fun `onChanged fires on real mutations only`() = runTest {
        var changes = 0
        val reg = PromptTemplateRegistry(
            tmp.newFolder("callback"),
            clock = { now },
            logger = {},
            onChanged = { changes++ }
        )
        assertEquals(10, reg.reseedBuiltIns())
        assertEquals(1, changes)
        assertEquals(0, reg.reseedBuiltIns()) // 幂等无变化
        assertEquals(1, changes)
        reg.save(sampleTemplate(id = "tpl_cb"))
        assertEquals(2, changes)
        assertTrue(reg.delete("tpl_cb"))
        assertEquals(3, changes)
        assertFalse(reg.delete("builtin_translate")) // 拒绝删除不算变更
        assertEquals(3, changes)
    }

    // ═══ 持久化追逐（写失败不抛、内存为准）═══

    @Test
    fun `registry works against an unwritable storage dir`() = runTest {
        // 用「文件冒充目录」制造确定性写失败（比 chmod 稳，不受 root 运行影响）
        val deadPath = File(dir, "not_a_dir.txt")
        deadPath.writeText("occupies the path")
        val reg = PromptTemplateRegistry(deadPath, clock = { now }, logger = { logs.add(it) })
        reg.save(sampleTemplate(id = "tpl_mem"))
        assertNotNull(reg.get("tpl_mem")) // 内存为准，写失败不抛
        assertTrue(logs.any { it.contains("persist failed") })
    }
}
