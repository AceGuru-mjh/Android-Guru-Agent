package com.apex.agent.core.tools.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextRelevance] 单测：分词（ASCII + CJK bigram）、BM25 排序、
 * 零命中/空输入边界、阈值裁剪。
 */
class TextRelevanceTest {

    // ═══ 分词 ═══

    @Test
    fun `tokenize splits ascii words and lowercases them`() {
        val tokens = TextRelevance.tokenize("Fetch USER-Profile v2")
        assertEquals(listOf("fetch", "user", "profile", "v2"), tokens)
    }

    @Test
    fun `tokenize emits cjk bigrams for chinese runs`() {
        val tokens = TextRelevance.tokenize("获取用户资料")
        assertEquals(listOf("获取", "取用", "用户", "户资", "资料"), tokens)
    }

    @Test
    fun `tokenize keeps isolated single cjk char as its own token`() {
        val tokens = TextRelevance.tokenize("表 a 键")
        assertEquals(listOf("表", "a", "键"), tokens)
    }

    @Test
    fun `tokenize on blank returns empty`() {
        assertTrue(TextRelevance.tokenize("   ").isEmpty())
    }

    // ═══ 排序 ═══

    @Test
    fun `rank puts the most doc first and returns indices`() {
        val docs = listOf(
            "今天天气不错，去公园散步",             // 无关
            "API key 存放在 config.env 里",        // 命中 api + key + config
            "把 markdown 转成表格"                  // 无关
        )
        val ranked = TextRelevance.rank("where is the api key config", docs, limit = 2)
        assertEquals(1, ranked.size)
        assertEquals(1, ranked[0].index)
        assertTrue(ranked[0].score >= TextRelevance.MIN_RELEVANT_SCORE)
    }

    @Test
    fun `rank matches chinese query against chinese docs`() {
        val docs = listOf(
            "上季度销售报告已归档",
            "用户反馈：登录页超时",
            "修复登录超时问题：会话保持参数错误"
        )
        val ranked = TextRelevance.rank("登录超时怎么修", docs, limit = 3)
        assertTrue(ranked.isNotEmpty())
        // 最相关的应是同时含「登录」与「超时」的第 3 条
        assertEquals(2, ranked[0].index)
    }

    @Test
    fun `rank returns empty when nothing matches`() {
        val docs = listOf("alpha beta", "gamma delta")
        assertTrue(TextRelevance.rank("完全无关的查询词组", docs).isEmpty())
    }

    @Test
    fun `rank on empty docs or blank query returns empty`() {
        assertTrue(TextRelevance.rank("query", emptyList()).isEmpty())
        assertTrue(TextRelevance.rank("  ", listOf("doc")).isEmpty())
    }

    @Test
    fun `rank respects limit`() {
        val docs = (1..10).map { "doc number $it about retry policy" }
        val ranked = TextRelevance.rank("retry policy doc", docs, limit = 3)
        assertTrue(ranked.size <= 3)
    }

    @Test
    fun `idf downweights terms present in every doc`() {
        // "common" 出现在全部文档（IDF≈0），"rare" 只出现在一篇 ——
        // 含 rare 的文档必须排前。
        val docs = listOf(
            "common words only",
            "common words and a rare keyword",
            "common everywhere"
        )
        val ranked = TextRelevance.rank("rare common", docs, limit = 3)
        assertEquals(1, ranked.first().index)
    }
}
