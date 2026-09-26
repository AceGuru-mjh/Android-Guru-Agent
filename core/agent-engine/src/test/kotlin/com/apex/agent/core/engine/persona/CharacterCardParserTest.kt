package com.apex.agent.core.engine.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4-e — SillyTavern 卡解析器测试（V2 嵌套 / V1 平面 / V3 路由 / 错误折叠 /
 * toPersona RikkaHub 组合）。纯函数断言，无 IO。
 */
class CharacterCardParserTest {

    private val fixedClock: () -> Long = { 1737000000000L }

    // ═══ 夹具 ═══

    private val v2FullJson = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "Serena",
            "description": "A wandering swordmistress of the northern peaks.",
            "personality": "Calm, observant, with dry humor.",
            "scenario": "A tavern at the edge of the kingdom.",
            "first_mes": "The door creaks as I step inside, rain on my cloak.",
            "mes_example": "Some example dialogue text.",
            "system_prompt": "Stay in character at all times.",
            "post_history_instructions": "Keep replies under 200 words.",
            "alternate_greetings": ["Alt greeting one.", "Alt greeting two."],
            "tags": ["fantasy", "rpg"],
            "creator": "cardsmith",
            "character_version": "1.2",
            "extensions": {
              "talkativeness": "0.5",
              "fav": false,
              "depth_prompt": {"depth": 4, "prompt": "inner voice"}
            },
            "character_book": {
              "name": "World Lore",
              "description": "Kingdom lorebook.",
              "entries": [
                {
                  "keys": ["dragon"],
                  "content": "Dragons rule the northern peaks.",
                  "enabled": true,
                  "insertion_order": 2,
                  "case_sensitive": false,
                  "position": 0
                },
                {
                  "keys": ["tavern"],
                  "content": "The Gilded Tankard is the local tavern.",
                  "enabled": true,
                  "insertion_order": 1,
                  "position": "after_char"
                }
              ]
            }
          }
        }
    """.trimIndent()

    private val v1FlatJson = """
        {
          "name": "Kai",
          "description": "A harbor town smuggler.",
          "personality": "Cheerful and shifty.",
          "first_mes": "Psst. You looking for passage south?"
        }
    """.trimIndent()

    // ═══ parseJson · V2 嵌套 ═══

    @Test
    fun `v2 nested json parses all fields`() {
        val result = CharacterCardParser.parseJson(v2FullJson)
        assertTrue(result.isSuccess)
        val card = result.getOrThrow()
        assertEquals("chara_card_v2", card.spec)
        assertEquals("2.0", card.specVersion)
        assertEquals("Serena", card.name)
        assertEquals("A wandering swordmistress of the northern peaks.", card.description)
        assertEquals("Calm, observant, with dry humor.", card.personality)
        assertEquals("A tavern at the edge of the kingdom.", card.scenario)
        assertEquals("The door creaks as I step inside, rain on my cloak.", card.firstMes)
        assertEquals("Some example dialogue text.", card.mesExample)
        assertEquals("Stay in character at all times.", card.systemPrompt)
        assertEquals("Keep replies under 200 words.", card.postHistoryInstructions)
        assertEquals(listOf("Alt greeting one.", "Alt greeting two."), card.alternateGreetings)
        assertEquals(listOf("fantasy", "rpg"), card.tags)
        assertEquals("cardsmith", card.creator)
        assertEquals("1.2", card.characterVersion)
    }

    @Test
    fun `v2 extensions of arbitrary shapes are flattened to strings`() {
        val card = CharacterCardParser.parseJson(v2FullJson).getOrThrow()
        // 布尔 → 字面内容；嵌套对象 → JSON 文本（可回读）
        assertEquals("0.5", card.extensions["talkativeness"])
        assertEquals("false", card.extensions["fav"])
        val depthPrompt = card.extensions["depth_prompt"]
        assertNotNull(depthPrompt)
        assertTrue(depthPrompt!!.contains("\"depth\""))
        assertTrue(depthPrompt.contains("inner voice"))
    }

    @Test
    fun `v2 lorebook entries parse with tolerant position and ordering fields`() {
        val card = CharacterCardParser.parseJson(v2FullJson).getOrThrow()
        val book = card.characterBook
        assertEquals("World Lore", book.name)
        assertEquals("Kingdom lorebook.", book.description)
        assertEquals(2, book.entries.size)
        val dragon = book.entries.first { it.keys.contains("dragon") }
        assertEquals("Dragons rule the northern peaks.", dragon.content)
        assertEquals(2, dragon.insertionOrder)
        assertFalse(dragon.caseSensitive)
        assertEquals(LorebookPosition.BEFORE_CHAR, dragon.position)
        val tavern = book.entries.first { it.keys.contains("tavern") }
        assertEquals(1, tavern.insertionOrder)
        assertEquals(LorebookPosition.AFTER_CHAR, tavern.position)
    }

    @Test
    fun `v2 lorebook in ST object-map form is normalized to a list`() {
        // SillyTavern 导出格式：entries 是以索引字符串为键的对象映射
        val mapFormJson = """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": {
                "name": "Nadia",
                "description": "Court alchemist.",
                "character_book": {
                  "entries": {
                    "0": {"keys": ["elixir"], "content": "The elixir of dawn.", "enabled": true, "insertion_order": 0},
                    "1": {"keys": ["court"], "content": "The court of Aldmere.", "enabled": false, "insertion_order": 1}
                  }
                }
              }
            }
        """.trimIndent()
        val card = CharacterCardParser.parseJson(mapFormJson).getOrThrow()
        val entries = card.characterBook.entries
        assertEquals(2, entries.size)
        assertEquals("The elixir of dawn.", entries[0].content)
        assertEquals("The court of Aldmere.", entries[1].content)
        assertFalse(entries[1].enabled)
    }

    @Test
    fun `v2 lorebook entry with legacy key field and string flags is tolerated`() {
        val legacyJson = """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": {
                "name": "Orin",
                "description": "Old world smith.",
                "character_book": {
                  "entries": [
                    {"key": "forge", "content": "The forge never cools.", "enabled": "false", "insertion_order": "3", "case_sensitive": "true"}
                  ]
                }
              }
            }
        """.trimIndent()
        val card = CharacterCardParser.parseJson(legacyJson).getOrThrow()
        val entry = card.characterBook.entries.single()
        assertEquals(listOf("forge"), entry.keys)
        assertEquals("The forge never cools.", entry.content)
        assertFalse(entry.enabled)
        assertEquals(3, entry.insertionOrder)
        assertTrue(entry.caseSensitive)
        // 未知 position 值 → 默认 BEFORE_CHAR
        assertEquals(LorebookPosition.BEFORE_CHAR, entry.position)
    }

    @Test
    fun `malformed lorebook entry is dropped without failing the card`() {
        val badEntryJson = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Pox",
                "character_book": {
                  "entries": ["not-an-object", {"keys": ["keep"], "content": "kept entry"}]
                }
              }
            }
        """.trimIndent()
        val card = CharacterCardParser.parseJson(badEntryJson).getOrThrow()
        assertEquals(1, card.characterBook.entries.size)
        assertEquals("kept entry", card.characterBook.entries[0].content)
    }

    // ═══ parseJson · V1 平面与 V3 ═══

    @Test
    fun `v1 flat json parses with defaults for absent fields`() {
        val result = CharacterCardParser.parseJson(v1FlatJson)
        assertTrue(result.isSuccess)
        val card = result.getOrThrow()
        assertEquals("chara_card_v1", card.spec)
        assertEquals("1.0", card.specVersion)
        assertEquals("Kai", card.name)
        assertEquals("A harbor town smuggler.", card.description)
        assertEquals("Psst. You looking for passage south?", card.firstMes)
        assertTrue(card.tags.isEmpty())
        assertTrue(card.alternateGreetings.isEmpty())
        assertEquals(Lorebook(), card.characterBook)
    }

    @Test
    fun `explicit v1 spec parses flat`() {
        val json = """{"spec": "chara_card_v1", "spec_version": "1.0", "name": "Rook", "description": "Thief."}"""
        val card = CharacterCardParser.parseJson(json).getOrThrow()
        assertEquals("chara_card_v1", card.spec)
        assertEquals("1.0", card.specVersion)
        assertEquals("Rook", card.name)
    }

    @Test
    fun `v3 spec routes through nested data`() {
        val json = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "V3-Card",
                "description": "A v3 card.",
                "first_mes": "Hello from v3."
              }
            }
        """.trimIndent()
        val card = CharacterCardParser.parseJson(json).getOrThrow()
        assertEquals("chara_card_v3", card.spec)
        assertEquals("3.0", card.specVersion)
        assertEquals("V3-Card", card.name)
        assertEquals("Hello from v3.", card.firstMes)
    }

    @Test
    fun `no spec but data object routes nested`() {
        val json = """{"data": {"name": "NoSpec", "description": "Nested anyway."}, "unrelated": 1}"""
        val card = CharacterCardParser.parseJson(json).getOrThrow()
        assertEquals("NoSpec", card.name)
        // 无根 spec → data 内也无 → 模型默认 spec 保持
        assertEquals("chara_card_v2", card.spec)
    }

    @Test
    fun `v2 spec without data object falls back to flat parsing`() {
        // 防御：个别导出工具声明 v2 但字段直接平铺
        val json = """{"spec": "chara_card_v2", "spec_version": "2.0", "name": "FlatV2", "description": "Flat fields."}"""
        val card = CharacterCardParser.parseJson(json).getOrThrow()
        assertEquals("FlatV2", card.name)
        assertEquals("Flat fields.", card.description)
        assertEquals("chara_card_v2", card.spec)
    }

    // ═══ parseJson · 错误折叠 ═══

    @Test
    fun `missing name yields typed MissingName error`() {
        val json = """{"spec": "chara_card_v2", "data": {"description": "no name here"}}"""
        val result = CharacterCardParser.parseJson(json)
        assertTrue(result.isFailure)
        val error = (result.exceptionOrNull() as? CardParseException)?.error
        assertEquals(CardParseError.MissingName, error)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("name"))
    }

    @Test
    fun `flat object without name yields MissingName error`() {
        val result = CharacterCardParser.parseJson("""{"description": "anonymous card"}""")
        assertTrue(result.isFailure)
        assertEquals(
            CardParseError.MissingName,
            (result.exceptionOrNull() as? CardParseException)?.error
        )
    }

    @Test
    fun `blank name yields MissingName error`() {
        val json = """{"spec": "chara_card_v2", "data": {"name": "   ", "description": "blank name"}}"""
        val result = CharacterCardParser.parseJson(json)
        assertTrue(result.isFailure)
        assertEquals(
            CardParseError.MissingName,
            (result.exceptionOrNull() as? CardParseException)?.error
        )
    }

    @Test
    fun `garbage text yields NotJson error`() {
        for (garbage in listOf("", "   ", "@@@ not json at all @@@", "42", "[1, 2, 3]", "\"a string\"")) {
            val result = CharacterCardParser.parseJson(garbage)
            assertTrue("expected failure for: $garbage", result.isFailure)
            assertEquals(
                CardParseError.NotJson,
                (result.exceptionOrNull() as? CardParseException)?.error
            )
        }
    }

    @Test
    fun `unknown spec without data yields UnknownSpec error`() {
        val json = """{"spec": "some_future_spec", "spec_version": "9.9"}"""
        val result = CharacterCardParser.parseJson(json)
        assertTrue(result.isFailure)
        val error = (result.exceptionOrNull() as? CardParseException)?.error
        assertEquals(CardParseError.UnknownSpec, error)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("unknown spec"))
    }

    @Test
    fun `unknown spec with data object still parses nested`() {
        val json = """{"spec": "future_spec", "data": {"name": "FutureCard", "description": "Data wins."}}"""
        val result = CharacterCardParser.parseJson(json)
        assertTrue(result.isSuccess)
        assertEquals("FutureCard", result.getOrThrow().name)
    }

    // ═══ toPersona · RikkaHub 组合 ═══

    @Test
    fun `toPersona composes roleDefinition with identity line and sections`() {
        val card = CharacterCardParser.parseJson(v2FullJson).getOrThrow()
        val persona = CharacterCardParser.toPersona(card, "serena", fixedClock)
        val role = persona.roleDefinition
        assertTrue(role.startsWith("You are roleplaying as Serena."))
        assertTrue(role.contains("## Description"))
        assertTrue(role.contains("A wandering swordmistress of the northern peaks."))
        assertTrue(role.contains("## Personality"))
        assertTrue(role.contains("Calm, observant, with dry humor."))
        assertTrue(role.contains("## Scenario"))
        assertTrue(role.contains("A tavern at the edge of the kingdom."))
        // 分节顺序：Description → Personality → Scenario
        val descIdx = role.indexOf("## Description")
        val persIdx = role.indexOf("## Personality")
        val scenIdx = role.indexOf("## Scenario")
        assertTrue(descIdx < persIdx && persIdx < scenIdx)
    }

    @Test
    fun `toPersona rolePrompt joins system prompt and post history instructions`() {
        val card = CharacterCardParser.parseJson(v2FullJson).getOrThrow()
        val persona = CharacterCardParser.toPersona(card, "serena", fixedClock)
        val prompt = persona.rolePrompt
        assertTrue(prompt.startsWith("Stay in character at all times."))
        assertTrue(prompt.contains("Keep replies under 200 words."))
        // 空行相接，非首尾直接粘连
        assertTrue(prompt.contains("at all times.\n\nKeep"))
    }

    @Test
    fun `toPersona firstMessage prefers first_mes`() {
        val card = CharacterCardParser.parseJson(v2FullJson).getOrThrow()
        val persona = CharacterCardParser.toPersona(card, "serena", fixedClock)
        assertEquals("The door creaks as I step inside, rain on my cloak.", persona.firstMessage)
    }

    @Test
    fun `toPersona falls back to first alternate greeting when first_mes empty`() {
        val card = CharacterCardParser.parseJson(v2FullJson).getOrThrow()
            .copy(firstMes = "   ")
        val persona = CharacterCardParser.toPersona(card, "serena", fixedClock)
        assertEquals("Alt greeting one.", persona.firstMessage)
    }

    @Test
    fun `toPersona firstMessage empty when no greeting at all`() {
        val card = CharacterCardParser.parseJson(v1FlatJson).getOrThrow()
            .copy(firstMes = "", alternateGreetings = emptyList())
        val persona = CharacterCardParser.toPersona(card, "kai", fixedClock)
        assertEquals("", persona.firstMessage)
    }

    @Test
    fun `toPersona omits empty sections from roleDefinition`() {
        // v1 夹具带 personality → 显式清空后再断言空分节省略
        val card = CharacterCardParser.parseJson(v1FlatJson).getOrThrow()
            .copy(personality = "", scenario = "")
        val persona = CharacterCardParser.toPersona(card, "kai", fixedClock)
        val role = persona.roleDefinition
        assertTrue(role.startsWith("You are roleplaying as Kai."))
        assertTrue(role.contains("## Description"))
        // 清空后不出现空标题
        assertFalse(role.contains("## Personality"))
        assertFalse(role.contains("## Scenario"))
    }

    @Test
    fun `toPersona carries lorebook tags and stamps clock`() {
        val card = CharacterCardParser.parseJson(v2FullJson).getOrThrow()
        val persona = CharacterCardParser.toPersona(card, "serena", fixedClock)
        assertEquals(card.characterBook, persona.lorebook)
        assertEquals(listOf("fantasy", "rpg"), persona.tags)
        assertEquals(1737000000000L, persona.createdAt)
        assertEquals(1737000000000L, persona.updatedAt)
        // 默认来源 IMPORTED（导入入口负责覆盖为具体来源）
        assertEquals(PersonaSource.IMPORTED, persona.source)
    }

    @Test
    fun `toPersona rolePrompt empty when card has neither prompt field`() {
        val card = CharacterCardParser.parseJson(v1FlatJson).getOrThrow()
        val persona = CharacterCardParser.toPersona(card, "kai", fixedClock)
        assertEquals("", persona.rolePrompt)
    }

    // ═══ Lorebook / 模型语义 ═══

    @Test
    fun `activeEntries filters disabled and sorts by insertionOrder`() {
        val book = Lorebook(
            name = "b",
            entries = listOf(
                LorebookEntry(keys = listOf("c"), content = "third", insertionOrder = 30),
                LorebookEntry(keys = listOf("a"), content = "first", insertionOrder = 10),
                LorebookEntry(keys = listOf("b"), content = "disabled", enabled = false, insertionOrder = 1),
                LorebookEntry(keys = listOf("d"), content = "second", insertionOrder = 20)
            )
        )
        val active = book.activeEntries()
        assertEquals(listOf("first", "second", "third"), active.map { it.content })
    }

    @Test
    fun `PersonaMapping DEFAULT documents every composed card field`() {
        val cardFields = PersonaMapping.DEFAULT.map { it.cardField }.toSet()
        val expected = setOf(
            "name", "description", "personality", "scenario",
            "system_prompt", "post_history_instructions",
            "first_mes", "character_book", "tags"
        )
        assertEquals(expected, cardFields)
        // 全部映射到合法的 PersonaCard 字段名
        val personaFields = setOf("roleDefinition", "rolePrompt", "firstMessage", "lorebook", "tags")
        assertTrue(PersonaMapping.DEFAULT.all { it.personaField in personaFields })
    }

    @Test
    fun `LorebookPosition order values follow the ST subset`() {
        assertEquals(0, LorebookPosition.BEFORE_CHAR.order)
        assertEquals(1, LorebookPosition.AFTER_CHAR.order)
        assertEquals(2, LorebookPosition.BEFORE_AN.order)
        assertEquals(3, LorebookPosition.AFTER_AN.order)
        assertEquals(4, LorebookPosition.values().size)
    }
}
