package com.apex.agent.core.engine.persona

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * 4-e — 人设注册表测试（持久化 roundtrip / 损坏隔离 / 列表序 / 导入
 * 双通道 / 导出 / 世界书触发）。JUnit4 + TemporaryFolder + runTest + 假钟
 * 注入，无 mock（logger 为手写收集 lambda）。
 */
class PersonaRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var registry: PersonaRegistry

    /** 假钟：手动步进，驱动时间戳与排序断言。 */
    private var now: Long = 1000L

    /** 日志收集（替代 mock：断言留痕而不校验调用次数）。 */
    private val logs = mutableListOf<String>()

    @Before
    fun setup() {
        dir = tmp.newFolder("personas")
        now = 1000L
        logs.clear()
        registry = PersonaRegistry(dir, clock = { now }, logger = { logs.add(it) })
    }

    // ═══ 夹具 ═══

    private fun samplePersona(
        id: String = "serena",
        createdAt: Long = 500L,
        updatedAt: Long = 500L
    ): PersonaCard = PersonaCard(
        id = id,
        name = "Serena",
        roleDefinition = "You are roleplaying as Serena.\n\n## Description\nA wandering swordmistress.",
        rolePrompt = "Stay in character at all times.",
        firstMessage = "The door creaks as I step inside.",
        lorebook = Lorebook(
            name = "World Lore",
            description = "Kingdom lorebook.",
            entries = listOf(
                LorebookEntry(
                    keys = listOf("dragon"),
                    content = "Dragons rule the northern peaks.",
                    insertionOrder = 2
                ),
                LorebookEntry(
                    keys = listOf("tavern"),
                    content = "The Gilded Tankard is the local tavern.",
                    insertionOrder = 1
                )
            )
        ),
        tags = listOf("fantasy", "rpg"),
        source = PersonaSource.TAVERN_JSON,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private val v2CardJson = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "Serena",
            "description": "A wandering swordmistress of the northern peaks.",
            "personality": "Calm, observant, with dry humor.",
            "scenario": "A tavern at the edge of the kingdom.",
            "first_mes": "The door creaks as I step inside, rain on my cloak.",
            "system_prompt": "Stay in character at all times.",
            "post_history_instructions": "Keep replies under 200 words.",
            "tags": ["fantasy", "rpg"],
            "character_book": {
              "name": "World Lore",
              "entries": [
                {"keys": ["dragon"], "content": "Dragons rule the northern peaks.", "insertion_order": 2},
                {"keys": ["tavern"], "content": "The Gilded Tankard is the local tavern.", "insertion_order": 1}
              ]
            }
          }
        }
    """.trimIndent()

    // PNG 构造（与 PngCharacterCardReaderTest 同法：CRC 占位零）

    private fun png(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        for ((type, data) in chunks) {
            val len = data.size
            out.write(
                byteArrayOf(
                    (len ushr 24 and 0xFF).toByte(),
                    (len ushr 16 and 0xFF).toByte(),
                    (len ushr 8 and 0xFF).toByte(),
                    (len and 0xFF).toByte()
                )
            )
            out.write(type.toByteArray(Charsets.ISO_8859_1))
            out.write(data)
            out.write(byteArrayOf(0, 0, 0, 0))
        }
        return out.toByteArray()
    }

    private fun textData(keyword: String, value: String): ByteArray {
        val kw = keyword.toByteArray(Charsets.ISO_8859_1)
        val v = value.toByteArray(Charsets.ISO_8859_1)
        val data = ByteArray(kw.size + 1 + v.size)
        kw.copyInto(data, 0)
        data[kw.size] = 0
        v.copyInto(data, kw.size + 1)
        return data
    }

    private fun cardPng(json: String = v2CardJson): ByteArray {
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return png(
            "IHDR" to ByteArray(13),
            "tEXt" to textData("chara", encoded),
            "IEND" to ByteArray(0)
        )
    }

    // ═══ save / load roundtrip ═══

    @Test
    fun `save-load roundtrip preserves all fields`() = runTest {
        val persona = samplePersona()
        now = 2000L
        registry.save(persona)

        val loaded = registry.load("serena")
        assertNotNull(loaded)
        // 关键字段抽查（防 equals 掩盖序列化丢字段）
        assertEquals("serena", loaded!!.id)
        assertEquals("Serena", loaded.name)
        assertTrue(loaded.roleDefinition.contains("You are roleplaying as Serena."))
        assertTrue(loaded.roleDefinition.contains("## Description"))
        assertEquals("Stay in character at all times.", loaded.rolePrompt)
        assertEquals("The door creaks as I step inside.", loaded.firstMessage)
        assertEquals("World Lore", loaded.lorebook.name)
        assertEquals(2, loaded.lorebook.entries.size)
        assertEquals("Dragons rule the northern peaks.", loaded.lorebook.entries[0].content)
        assertEquals(1, loaded.lorebook.entries[1].insertionOrder)
        assertEquals(listOf("fantasy", "rpg"), loaded.tags)
        assertEquals(PersonaSource.TAVERN_JSON, loaded.source)
        assertEquals(500L, loaded.createdAt)
        assertEquals(2000L, loaded.updatedAt)
        // 整体等值（盖章后）
        assertEquals(persona.copy(updatedAt = 2000L), loaded)
    }

    @Test
    fun `save stamps updatedAt and preserves explicit createdAt`() = runTest {
        now = 2000L
        val saved = registry.save(samplePersona(createdAt = 500L))
        assertEquals(500L, saved.createdAt)
        assertEquals(2000L, saved.updatedAt)
    }

    @Test
    fun `save fills createdAt from clock when zero`() = runTest {
        now = 3000L
        val saved = registry.save(samplePersona(createdAt = 0L, updatedAt = 0L))
        assertEquals(3000L, saved.createdAt)
        assertEquals(3000L, saved.updatedAt)
    }

    @Test
    fun `upsert save replaces previous snapshot atomically`() = runTest {
        val persona = samplePersona()
        registry.save(persona)
        now = 5000L
        registry.save(persona.copy(rolePrompt = "New prompt."))

        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!
        assertEquals(1, files.size)
        val loaded = registry.load("serena")!!
        assertEquals("New prompt.", loaded.rolePrompt)
        assertEquals(5000L, loaded.updatedAt)
        assertEquals(500L, loaded.createdAt)
    }

    @Test
    fun `save leaves no temp residue`() = runTest {
        registry.save(samplePersona())
        assertTrue(dir.listFiles { f -> f.name.endsWith(".tmp") }!!.isEmpty())
    }

    @Test
    fun `load missing persona returns null`() {
        assertNull(registry.load("nonexistent"))
    }

    @Test
    fun `illegal id save and load throw fast`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            registry.load("../../etc/passwd")
        }
        // assertThrows 的 Executable 非 suspend → 手写 try/catch（仓库惯例）
        try {
            registry.save(samplePersona(id = "BAD/ID"))
            throw AssertionError("expected IllegalArgumentException for BAD/ID")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            registry.save(samplePersona(id = ""))
            throw AssertionError("expected IllegalArgumentException for empty id")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ═══ 损坏隔离 ═══

    @Test
    fun `corrupt file is quarantined and does not break other personas`() = runTest {
        val good = samplePersona(id = "good-one")
        registry.save(good)
        // 制造损坏文件（运行时拼接，不落字面量括号失衡风险）
        File(dir, "broken.json").writeText("{ this is not ) valid json")

        val all = registry.list()
        assertEquals(1, all.size)
        assertEquals("good-one", all[0].id)

        // 隔离区存在被移走的损坏文件；原位置清空；单点 load → null（不抛）
        assertTrue(File(dir, "broken.json.corrupt").exists())
        assertFalse(File(dir, "broken.json").exists())
        assertNull(registry.load("broken"))
        // 留痕被记录
        assertTrue(logs.any { it.contains("broken.json") && it.contains("quarantining") })
    }

    @Test
    fun `corrupt quarantine on repeated corruption does not clobber previous quarantine`() {
        val first = "{ broken one"
        File(dir, "broken.json").writeText(first)
        assertNull(registry.load("broken"))
        File(dir, "broken.json").writeText("{ broken two")
        assertNull(registry.load("broken"))
        // 第二次隔离走时间戳后缀，不覆盖首份证据
        val quarantined = dir.listFiles { f -> f.name.startsWith("broken.json.corrupt") }!!
        assertEquals(2, quarantined.size)
    }

    // ═══ delete ═══

    @Test
    fun `delete removes file and returns true once`() = runTest {
        registry.save(samplePersona())
        assertTrue(registry.delete("serena"))
        assertNull(registry.load("serena"))
        assertFalse(File(dir, "serena.json").exists())
        // 幂等：再删 → false（不存在）
        assertFalse(registry.delete("serena"))
    }

    @Test
    fun `delete clears temp residue too`() = runTest {
        registry.save(samplePersona())
        // 模拟崩溃残留 temp
        File(dir, "serena.json.tmp").writeText("{ partial")
        assertTrue(registry.delete("serena"))
        assertTrue(dir.listFiles { f -> f.name.startsWith("serena") }!!.isEmpty())
    }

    // ═══ list 排序 ═══

    @Test
    fun `list sorts by updatedAt descending`() = runTest {
        now = 1000L
        registry.save(samplePersona(id = "old-card"))
        now = 2000L
        registry.save(samplePersona(id = "mid-card"))
        now = 3000L
        registry.save(samplePersona(id = "new-card"))

        val ids = registry.list().map { it.id }
        assertEquals(listOf("new-card", "mid-card", "old-card"), ids)
    }

    @Test
    fun `empty store lists empty`() {
        assertTrue(registry.list().isEmpty())
    }

    // ═══ importTavernJson ═══

    @Test
    fun `importTavernJson happy path composes and persists`() = runTest {
        now = 4000L
        val result = registry.importTavernJson(v2CardJson, "imported-serena")
        assertTrue(result.isSuccess)
        val persona = result.getOrThrow()
        assertEquals("imported-serena", persona.id)
        assertEquals("Serena", persona.name)
        assertEquals(PersonaSource.TAVERN_JSON, persona.source)
        assertTrue(persona.roleDefinition.contains("You are roleplaying as Serena."))
        assertTrue(persona.roleDefinition.contains("## Description"))
        assertTrue(persona.rolePrompt.contains("Stay in character at all times."))
        assertTrue(persona.rolePrompt.contains("Keep replies under 200 words."))
        assertEquals("The door creaks as I step inside, rain on my cloak.", persona.firstMessage)
        assertEquals(2, persona.lorebook.entries.size)
        assertEquals(4000L, persona.updatedAt)

        // 落盘可复读
        val loaded = registry.load("imported-serena")
        assertNotNull(loaded)
        assertEquals(persona, loaded)
    }

    @Test
    fun `importTavernJson garbage folds to failure without writing file`() = runTest {
        val result = registry.importTavernJson("@@@ not json @@@", "bad-import")
        assertTrue(result.isFailure)
        val error = (result.exceptionOrNull() as? CardParseException)?.error
        assertEquals(CardParseError.NotJson, error)
        assertFalse(File(dir, "bad-import.json").exists())
        assertTrue(logs.any { it.contains("bad-import") })
    }

    @Test
    fun `importTavernJson missing name folds to failure`() = runTest {
        val json = """{"spec": "chara_card_v2", "data": {"description": "no name"}}"""
        val result = registry.importTavernJson(json, "noname-import")
        assertTrue(result.isFailure)
        assertEquals(
            CardParseError.MissingName,
            (result.exceptionOrNull() as? CardParseException)?.error
        )
        assertFalse(File(dir, "noname-import.json").exists())
    }

    @Test
    fun `importTavernJson illegal id folds to failure without throwing`() = runTest {
        for (badId in listOf("BAD_ID", "../evil", "a".repeat(65), "")) {
            val result = registry.importTavernJson(v2CardJson, badId)
            assertTrue("expected failure for id: $badId", result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(dir.listFiles { f -> f.name.endsWith(".json") }!!.isEmpty())
    }

    // ═══ importTavernPng ═══

    @Test
    fun `importTavernPng happy path extracts composes and persists`() = runTest {
        now = 5000L
        val result = registry.importTavernPng(cardPng(), "png-import")
        assertTrue(result.isSuccess)
        val persona = result.getOrThrow()
        assertEquals("png-import", persona.id)
        assertEquals("Serena", persona.name)
        assertEquals(PersonaSource.TAVERN_PNG, persona.source)
        assertTrue(persona.roleDefinition.contains("## Personality"))
        assertEquals(2, persona.lorebook.entries.size)
        assertEquals(5000L, persona.updatedAt)
        assertNotNull(registry.load("png-import"))
    }

    @Test
    fun `importTavernPng without card data folds to failure`() = runTest {
        val plainPng = png("IHDR" to ByteArray(13), "IEND" to ByteArray(0))
        val result = registry.importTavernPng(plainPng, "no-card")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PersonaImportException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no SillyTavern card"))
        assertFalse(File(dir, "no-card.json").exists())
    }

    @Test
    fun `importTavernPng not a png folds to failure`() = runTest {
        val result = registry.importTavernPng("definitely not png bytes".toByteArray(), "not-png")
        assertTrue(result.isFailure)
        assertFalse(File(dir, "not-png.json").exists())
    }

    @Test
    fun `importTavernPng illegal id folds to failure`() = runTest {
        val result = registry.importTavernPng(cardPng(), "../evil")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    // ═══ export ═══

    @Test
    fun `export returns pretty json that decodes back to the same persona`() {
        val persona = samplePersona()
        val exported = registry.export(persona)
        // pretty：含换行缩进
        assertTrue(exported.contains("\n"))
        assertTrue(exported.contains("\"Serena\""))
        // 回读等值
        val decoded = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }.decodeFromString(PersonaCard.serializer(), exported)
        assertEquals(persona, decoded)
    }

    // ═══ applyLorebook ═══

    private fun lorebookPersona(entries: List<LorebookEntry>): PersonaCard =
        samplePersona().copy(lorebook = Lorebook(name = "b", entries = entries))

    @Test
    fun `applyLorebook triggers on keyword contained in recent messages`() {
        val persona = lorebookPersona(
            listOf(LorebookEntry(keys = listOf("dragon"), content = "Dragon lore.", insertionOrder = 0))
        )
        val triggered = registry.applyLorebook(
            persona,
            listOf("Hello there.", "Tell me about the DRAGON of the north.")
        )
        assertEquals(listOf("Dragon lore."), triggered)
    }

    @Test
    fun `applyLorebook is case insensitive by default`() {
        val persona = lorebookPersona(
            listOf(LorebookEntry(keys = listOf("tavern"), content = "Tavern lore.", insertionOrder = 0))
        )
        val triggered = registry.applyLorebook(persona, listOf("The TAVERN is warm tonight."))
        assertEquals(listOf("Tavern lore."), triggered)
    }

    @Test
    fun `applyLorebook respects case sensitive entries`() {
        val persona = lorebookPersona(
            listOf(
                LorebookEntry(
                    keys = listOf("Tavern"),
                    content = "Case-sensitive lore.",
                    caseSensitive = true,
                    insertionOrder = 0
                )
            )
        )
        // 小写不触发（caseSensitive=true）
        assertEquals(
            emptyList<String>(),
            registry.applyLorebook(persona, listOf("Let us visit the tavern."))
        )
        // 精确大小写触发
        assertEquals(
            listOf("Case-sensitive lore."),
            registry.applyLorebook(persona, listOf("Let us visit the Tavern."))
        )
    }

    @Test
    fun `applyLorebook only scans the last scanDepth messages`() {
        val persona = lorebookPersona(
            listOf(LorebookEntry(keys = listOf("sword"), content = "Sword lore.", insertionOrder = 0))
        )
        val messages = listOf(
            "I picked up the sword.",   // 索引 0：默认窗口(4)之外
            "We walk on.",
            "We rest.",
            "We march.",
            "We arrive."
        )
        // 默认 scanDepth=4 → 只看后 4 条 → sword 不触发
        assertEquals(emptyList<String>(), registry.applyLorebook(persona, messages))
        // scanDepth 放大到 5 → 命中首条
        assertEquals(
            listOf("Sword lore."),
            registry.applyLorebook(persona, messages, scanDepth = 5)
        )
    }

    @Test
    fun `applyLorebook skips disabled entries`() {
        val persona = lorebookPersona(
            listOf(
                LorebookEntry(keys = listOf("dragon"), content = "Enabled lore.", insertionOrder = 0),
                LorebookEntry(keys = listOf("dragon"), content = "Disabled lore.", enabled = false, insertionOrder = 1)
            )
        )
        val triggered = registry.applyLorebook(persona, listOf("The dragon sleeps."))
        assertEquals(listOf("Enabled lore."), triggered)
    }

    @Test
    fun `applyLorebook orders triggered entries by insertionOrder`() {
        val persona = lorebookPersona(
            listOf(
                LorebookEntry(keys = listOf("dragon"), content = "Late (order 30).", insertionOrder = 30),
                LorebookEntry(keys = listOf("tavern"), content = "Early (order 10).", insertionOrder = 10),
                LorebookEntry(keys = listOf("forest"), content = "Mid (order 20).", insertionOrder = 20)
            )
        )
        val triggered = registry.applyLorebook(
            persona,
            listOf("The dragon, the tavern and the forest.")
        )
        assertEquals(listOf("Early (order 10).", "Mid (order 20).", "Late (order 30)."), triggered)
    }

    @Test
    fun `applyLorebook any key of an entry can trigger`() {
        val persona = lorebookPersona(
            listOf(LorebookEntry(keys = listOf("wyrm", "drake"), content = "Serpent lore.", insertionOrder = 0))
        )
        assertEquals(
            listOf("Serpent lore."),
            registry.applyLorebook(persona, listOf("A drake circles above."))
        )
    }

    @Test
    fun `applyLorebook edge cases return empty`() {
        val persona = lorebookPersona(
            listOf(
                LorebookEntry(keys = listOf(""), content = "Blank key lore.", insertionOrder = 0),
                LorebookEntry(keys = listOf("dragon"), content = "Dragon lore.", insertionOrder = 1)
            )
        )
        // 空 key 永不触发；空消息窗口 / 非正 scanDepth / 空消息 → 空
        assertEquals(emptyList<String>(), registry.applyLorebook(persona, listOf("anything")))
        assertEquals(emptyList<String>(), registry.applyLorebook(persona, emptyList()))
        assertEquals(
            emptyList<String>(),
            registry.applyLorebook(persona, listOf("The dragon"), scanDepth = 0)
        )
        // 无世界书的人设 → 空
        assertEquals(emptyList<String>(), registry.applyLorebook(samplePersona().copy(lorebook = Lorebook()), listOf("dragon")))
    }
}
