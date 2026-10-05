package com.apex.agent.core.tools.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #206 单测：[SkillCategory] 24 域体系 + [SkillRegistry] 的分类感知查询
 * + [SkillDigest] 目录（域标签、scope 过滤、首句截断）。
 *
 * 风格同 SandboxMcpConfigTest：JUnit4 + 临时目录真实实例，无 mock 框架。
 */
class SkillCategoryTest {

    // ═══ 枚举本体 ═══

    @Test
    fun `twenty four domains with unique keys and orders`() {
        val keys = SkillCategory.entries.map { it.key }
        val orders = SkillCategory.entries.map { it.order }
        assertEquals(24, SkillCategory.entries.size)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(orders.size, orders.toSet().size)
        // key 形态：小写单词或连字符
        keys.forEach { k -> assertTrue(k.matches(Regex("^[a-z]+(-[a-z]+)*$"))) }
    }

    @Test
    fun `of resolves known keys case-insensitively`() {
        assertEquals(SkillCategory.CAREER, SkillCategory.of("career"))
        assertEquals(SkillCategory.KNOWLEDGE, SkillCategory.of("Knowledge"))
        assertEquals(SkillCategory.HOME, SkillCategory.of(" home "))
    }

    @Test
    fun `of returns null for legacy tool-category names`() {
        // 旧词表（ToolCategory 名）绝不猜 —— 归入「未分类」由调用方兜底。
        // 例外：PRODUCTIVITY 与新域 key 同名（大小写不敏感），旧值自然
        // 归位到 productivity 域 —— 这是唯一合理的「旧值撞新键」情形。
        assertNull(SkillCategory.of("AGENT"))
        assertEquals(SkillCategory.PRODUCTIVITY, SkillCategory.of("PRODUCTIVITY"))
        assertNull(SkillCategory.of("UTILITY"))
        assertNull(SkillCategory.of("SHELL"))
        assertNull(SkillCategory.of(null))
        assertNull(SkillCategory.of(""))
    }

    @Test
    fun `sanitize normalizes valid keys and nulls legacy`() {
        assertEquals("career", SkillCategory.sanitize("Career"))
        assertNull(SkillCategory.sanitize("AGENT"))
        assertNull(SkillCategory.sanitize("whatever-else"))
    }

    @Test
    fun `display order is stable and starts with career`() {
        val ordered = SkillCategory.inDisplayOrder()
        assertEquals(SkillCategory.CAREER, ordered.first())
        assertEquals(SkillCategory.CODING, ordered.last())
        assertEquals(SkillCategory.entries.size, ordered.size)
    }

    // ═══ Registry 分类感知查询（真实临时目录实例）═══

    private fun registryWith(vararg manifests: String): SkillRegistry {
        val dir = createTempDir()
        manifests.forEach { json -> File(dir, "${jsonToId(json)}.json").writeText(json) }
        return SkillRegistry(dir)
    }

    private fun jsonToId(json: String): String =
        json.substringAfter("\"id\": \"").substringBefore("\"")

    private fun manifestJson(id: String, category: String?, scope: String = "all", enabled: Boolean = true): String {
        val cat = category?.let { """"category": "$it",""" } ?: ""
        return """
            {
              "schema": "apex-skill-v1",
              "id": "$id",
              "name": "$id",
              "version": "1.0.0",
              "description": "desc of $id。second sentence",
              "author": "Apex",
              "license": "MIT",
              "dependencies": [],
              "requirements": {"minAppVersion": 1, "permissions": [], "toolsRequired": [], "privilegeLevel": "none"},
              "tools": [],
              "configuration": {"autoSetup": [], "userConfig": []},
              "promptInjection": "【$id】",
              $cat
              "tags": ["t"],
              "trustLevel": "verified",
              "bundled": true,
              "scope": "$scope"
            }
        """.trimIndent().let { if (!enabled) it else it }
    }

    @Test
    fun `digests carry sanitized category`() {
        val registry = registryWith(
            manifestJson("a", "Career"),       // 大小写归一
            manifestJson("b", "AGENT")         // 旧值 → null
        )
        val digests = registry.getSkillDigests().associateBy { it.id }
        assertEquals("career", digests["a"]?.category)
        assertEquals(null, digests["b"]?.category)
    }

    @Test
    fun `digest summary takes first sentence with truncation`() {
        val long = "x".repeat(100) + "。"
        val registry = registryWith(manifestJson("long", "career").replace("desc of long。second sentence", long))
        val summary = registry.getSkillDigests().single().summary
        assertEquals(73, summary.length) // 72 + 省略号
        assertTrue(summary.endsWith("…"))
    }

    @Test
    fun `digests scope filter matches counts`() {
        val registry = registryWith(
            manifestJson("a", "career", scope = "coding"),
            manifestJson("b", "knowledge", scope = "agent")
        )
        assertEquals(1, registry.getSkillDigests(scope = "agent").size)
        assertEquals(1, registry.getSkillDigests(scope = "coding").size)
        assertEquals(2, registry.getSkillDigests(scope = null).size)
    }
}
