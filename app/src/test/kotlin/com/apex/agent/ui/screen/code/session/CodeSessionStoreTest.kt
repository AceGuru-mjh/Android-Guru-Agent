package com.apex.agent.ui.screen.code.session

import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.ui.screen.code.CodeChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [CodeSessionStore] 与 [CodeSessionModels] 纯函数的行为契约测试（纯 JVM）。
 *
 * 覆盖面：空读、往返一致、多工作区隔离、坏档隔离、clear 语义、文件名清洗
 * （路径穿越 / 超长 id）、JSON 前后兼容（未知字段 / 缺省字段）、映射层的
 * isStreaming 过滤与文本截断、消息 400 上限、freshIds 递增与未知 role 容错、
 * todo 双向映射。
 */
class CodeSessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Store：读取与往返 ────────────────────────────────────────────

    @Test
    fun `load returns null when no snapshot exists`() {
        val store = CodeSessionStore(tmp.newFolder("empty"))

        assertNull(store.load("anything"))
        assertNull(store.load(""))
    }

    @Test
    fun `save then load round-trips full snapshot`() {
        val dir = tmp.newFolder("snapshots")
        val store = CodeSessionStore(dir)
        val snap = CodeSessionSnapshot(
            workspaceId = "alpha",
            messages = listOf(
                StorableCodeMessage("USER", "帮我写个排序函数"),
                StorableCodeMessage("ASSISTANT", "好的，先看下文件"),
                StorableCodeMessage("TOOL", "3 行已写入", "code_write", false, 1520),
                StorableCodeMessage("SYSTEM", "会话已恢复")
            ),
            todos = listOf(
                StorableTodo("实现功能", "completed", "high"),
                StorableTodo("补测试", "in_progress", "medium"),
                StorableTodo("清理日志", "pending", "low")
            ),
            lastActiveFile = "src/main.kt",
            updatedAt = 1720000000000L
        )

        store.save(snap)

        assertEquals(snap, store.load("alpha"))
    }

    @Test
    fun `empty snapshot round-trips with defaults`() {
        val store = CodeSessionStore(tmp.newFolder("snapshots"))
        val snap = CodeSessionSnapshot(workspaceId = "blank")

        store.save(snap)

        assertEquals(snap, store.load("blank"))
        assertNull(store.load("blank")!!.lastActiveFile)
        assertEquals(0L, store.load("blank")!!.updatedAt)
    }

    @Test
    fun `workspaces are isolated from each other`() {
        val store = CodeSessionStore(tmp.newFolder("snapshots"))
        val snapA = CodeSessionSnapshot("alpha", listOf(StorableCodeMessage("USER", "A")))
        val snapB = CodeSessionSnapshot("beta", listOf(StorableCodeMessage("USER", "B")))
        store.save(snapA)
        store.save(snapB)

        assertEquals("A", store.load("alpha")!!.messages.single().text)
        assertEquals("B", store.load("beta")!!.messages.single().text)

        // 删 alpha 不影响 beta
        store.clear("alpha")
        assertNull(store.load("alpha"))
        assertEquals("B", store.load("beta")!!.messages.single().text)
    }

    // ── Store：坏档隔离 ──────────────────────────────────────────────

    @Test
    fun `corrupt json is quarantined and load returns null`() {
        val dir = tmp.newFolder("snapshots")
        val store = CodeSessionStore(dir)
        val file = File(dir, "ws_w1.json")
        file.writeText("这不是 JSON {{{")

        assertNull(store.load("w1"))
        // 坏档留档 + 原文件移除
        assertTrue(File(dir, "ws_w1.json.corrupt").exists())
        assertFalse(file.exists())
        assertEquals("这不是 JSON {{{", File(dir, "ws_w1.json.corrupt").readText())

        // 隔离后可正常重写恢复，坏档不再干扰
        val fresh = CodeSessionSnapshot("w1", listOf(StorableCodeMessage("USER", "重生")))
        store.save(fresh)
        assertEquals(fresh, store.load("w1"))
        assertTrue(File(dir, "ws_w1.json.corrupt").exists())
    }

    // ── Store：clear 语义 ────────────────────────────────────────────

    @Test
    fun `clear removes snapshot and stays silent when absent`() {
        val store = CodeSessionStore(tmp.newFolder("snapshots"))
        store.save(CodeSessionSnapshot("w1", listOf(StorableCodeMessage("USER", "x"))))

        store.clear("w1")
        assertNull(store.load("w1"))

        // 不存在的 id 与重复 clear 均不崩
        store.clear("never-exists")
        store.clear("w1")
    }

    // ── Store：文件名清洗 ────────────────────────────────────────────

    @Test
    fun `workspace id with path traversal stays inside base dir`() {
        val sessions = tmp.newFolder("sessions")
        val store = CodeSessionStore(sessions)
        val snap = CodeSessionSnapshot("../evil", listOf(StorableCodeMessage("USER", "x")))

        store.save(snap)

        // 文件落在 baseDir 内且只有一个
        val files = sessions.listFiles()!!
        assertEquals(1, files.size)
        assertTrue(files[0].name.startsWith("ws_"))
        assertTrue(files[0].name.endsWith(".json"))
        // 父目录层面没有逃逸产物
        val strays = tmp.root.listFiles()!!.filter { it.name != "sessions" }
        assertTrue("逃逸文件: $strays", strays.isEmpty())
        // 同一 id 可读回（清洗结果确定性）
        assertEquals(snap, store.load("../evil"))
    }

    @Test
    fun `overlong workspace id is capped and prefix-sharing ids stay distinct`() {
        val store = CodeSessionStore(tmp.newFolder("snapshots"))
        val idA = "w".repeat(300)
        val idB = "w".repeat(299) + "v"

        store.save(CodeSessionSnapshot(idA, listOf(StorableCodeMessage("USER", "A"))))
        store.save(CodeSessionSnapshot(idB, listOf(StorableCodeMessage("USER", "B"))))

        // 文件名主干被压到 64 字符以内（ws_ + 64 + .json）
        val files = File(tmp.root, "snapshots").listFiles()!!
        assertEquals(2, files.size)
        assertTrue(files.all { it.name.length <= "ws_".length + 64 + ".json".length })
        // 共享前缀的两个长 id 互不覆盖
        assertEquals("A", store.load(idA)!!.messages.single().text)
        assertEquals("B", store.load(idB)!!.messages.single().text)
    }

    @Test
    fun `constructor creates base dir when missing`() {
        val dir = File(tmp.root, "fresh")
        assertFalse(dir.exists())

        val store = CodeSessionStore(dir)

        assertTrue(dir.exists())
        store.save(CodeSessionSnapshot("w1"))
        assertNotNull(store.load("w1"))
    }

    // ── Store：JSON 前后兼容 ─────────────────────────────────────────

    @Test
    fun `json tolerates unknown fields and missing optional fields`() {
        val dir = tmp.newFolder("snapshots")
        val store = CodeSessionStore(dir)
        // 模拟「新版本写的文件被旧代码读」：多出未知字段；可选字段全部缺省
        File(dir, "ws_w1.json").writeText(
            """
            {"workspaceId":"w1",
             "messages":[
               {"role":"USER","text":"hi","futureField":123},
               {"role":"TOOL","text":"out"}
             ],
             "todos":[],
             "brandNewTopField":true}
            """.trimIndent()
        )

        val snap = store.load("w1")

        assertNotNull(snap)
        assertEquals("w1", snap!!.workspaceId)
        assertEquals(2, snap.messages.size)
        assertEquals("hi", snap.messages[0].text)
        // 缺省字段走默认值
        assertNull(snap.messages[1].toolName)
        assertTrue(snap.messages[1].toolSuccess)
        assertEquals(0L, snap.messages[1].durationMs)
        assertNull(snap.lastActiveFile)
        assertEquals(0L, snap.updatedAt)
    }

    // ── Models：纯函数 ───────────────────────────────────────────────

    @Test
    fun `truncation boundary is exact at limit`() {
        val exact = "a".repeat(CODE_SNAPSHOT_TEXT_LIMIT)
        assertEquals(exact, truncateForSnapshot(exact))

        val over = "a".repeat(CODE_SNAPSHOT_TEXT_LIMIT + 1)
        assertEquals(
            "a".repeat(CODE_SNAPSHOT_TEXT_LIMIT) + CODE_SNAPSHOT_TRUNCATION_SUFFIX,
            truncateForSnapshot(over)
        )
    }

    @Test
    fun `cappedForSnapshot drops oldest beyond max`() {
        val messages = (0..404).map { StorableCodeMessage("USER", "m$it") }

        val capped = messages.cappedForSnapshot()

        assertEquals(CODE_SNAPSHOT_MAX_MESSAGES, capped.size)
        assertEquals("m5", capped.first().text)
        assertEquals("m404", capped.last().text)
        // 不超上限时原样返回
        assertEquals(2, messages.take(2).cappedForSnapshot().size)
    }

    @Test
    fun `role helpers validate and degrade unknown values`() {
        assertEquals("USER", "user".toKnownCodeRoleOrNull())
        assertEquals("ASSISTANT", "  Assistant ".toKnownCodeRoleOrNull())
        assertNull("wizard".toKnownCodeRoleOrNull())
        assertNull("".toKnownCodeRoleOrNull())
        // 降级语义：未知值落到 SYSTEM，内容不丢
        assertEquals("SYSTEM", "wizard".toRoleOrSystem())
        assertEquals("TOOL", " tool ".toRoleOrSystem())
    }

    // ── Mappers：消息互转 ────────────────────────────────────────────

    @Test
    fun `toStorable drops streaming messages and truncates long text`() {
        val longText = "x".repeat(CODE_SNAPSHOT_TEXT_LIMIT + 1)
        val messages = listOf(
            CodeChatMessage(1, CodeChatMessage.Role.USER, "hello"),
            CodeChatMessage(2, CodeChatMessage.Role.ASSISTANT, "流式中的半截", isStreaming = true),
            CodeChatMessage(3, CodeChatMessage.Role.ASSISTANT, longText),
            CodeChatMessage(
                4, CodeChatMessage.Role.TOOL, "输出", toolName = "code_read",
                toolSuccess = false, durationMs = 123
            )
        )

        val storable = messages.toStorable()

        // 流式条目被过滤，其余保序
        assertEquals(3, storable.size)
        assertEquals(listOf("USER", "ASSISTANT", "TOOL"), storable.map { it.role })
        assertEquals("hello", storable[0].text)
        // 超长文本截断 + 标记后缀
        assertEquals(longText.take(CODE_SNAPSHOT_TEXT_LIMIT) + CODE_SNAPSHOT_TRUNCATION_SUFFIX, storable[1].text)
        // 工具卡字段完整保留
        assertEquals("code_read", storable[2].toolName)
        assertFalse(storable[2].toolSuccess)
        assertEquals(123L, storable[2].durationMs)
    }

    @Test
    fun `toStorable caps messages at 400 dropping oldest`() {
        val messages = (0..404).map {
            CodeChatMessage(it.toLong(), CodeChatMessage.Role.USER, "m$it")
        }

        val storable = messages.toStorable()

        assertEquals(CODE_SNAPSHOT_MAX_MESSAGES, storable.size)
        assertEquals("m5", storable.first().text)
        assertEquals("m404", storable.last().text)
    }

    @Test
    fun `withFreshIds assigns sequential ids and preserves all fields`() {
        val storable = listOf(
            StorableCodeMessage("USER", "你好"),
            StorableCodeMessage("ASSISTANT", "在的"),
            StorableCodeMessage("TOOL", "完成", "code_write", false, 42),
            StorableCodeMessage("SYSTEM", "系统提示")
        )

        val restored = storable.withFreshIds(100)

        // id 从起始值连续递增
        assertEquals(listOf(100L, 101L, 102L, 103L), restored.map { it.id })
        // role 字符串往返无损
        assertEquals(
            listOf(
                CodeChatMessage.Role.USER,
                CodeChatMessage.Role.ASSISTANT,
                CodeChatMessage.Role.TOOL,
                CodeChatMessage.Role.SYSTEM
            ),
            restored.map { it.role }
        )
        // 字段与瞬态
        assertEquals("你好", restored[0].text)
        assertEquals("code_write", restored[2].toolName)
        assertFalse(restored[2].toolSuccess)
        assertEquals(42L, restored[2].durationMs)
        assertTrue(restored.all { !it.isStreaming })
    }

    @Test
    fun `withFreshIds skips unknown role and keeps ids gapless`() {
        val storable = listOf(
            StorableCodeMessage("USER", "合法"),
            StorableCodeMessage("WEIRD_ROLE", "脏数据"),
            StorableCodeMessage("assistant", "小写容错")
        )

        val restored = storable.withFreshIds(7)

        // 未知 role 被跳过，其余 id 连续无洞
        assertEquals(2, restored.size)
        assertEquals(listOf(7L, 8L), restored.map { it.id })
        assertEquals(CodeChatMessage.Role.USER, restored[0].role)
        assertEquals(CodeChatMessage.Role.ASSISTANT, restored[1].role)
    }

    // ── Mappers：todo 互转 ───────────────────────────────────────────

    @Test
    fun `todo mapping round-trips both ways`() {
        val todos = listOf(
            CodeTodoTool.Todo("实现功能", "completed", "high"),
            CodeTodoTool.Todo("补测试", "in_progress", "medium"),
            CodeTodoTool.Todo("清理", "pending", "low")
        )

        val storable = todos.toStorable()

        assertEquals(
            listOf(
                StorableTodo("实现功能", "completed", "high"),
                StorableTodo("补测试", "in_progress", "medium"),
                StorableTodo("清理", "pending", "low")
            ),
            storable
        )
        // 反向还原后与原始清单等值（CodeTodoTool 全量覆盖式更新，整体回填即可）
        assertEquals(todos, storable.toCodeTodos())
        assertEquals(emptyList<CodeTodoTool.Todo>(), emptyList<StorableTodo>().toCodeTodos())
    }
}
