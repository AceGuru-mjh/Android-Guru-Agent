package com.apex.agent.core.tools.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SearchResultCache] 单测：假钟驱动 TTL 过期、LRU 逐出次序、容量上限。
 * 纪律：不睡眠——所有时间推进都是改一个 var 后读 clock()。
 */
class SearchResultCacheTest {

    private class FakeClock(var now: Long = 1_000L) {
        val read: () -> Long = { now }
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun query(text: String, maxResults: Int = 5): SearchQuery =
        SearchQuery(query = text, maxResults = maxResults)

    private fun okResponse(text: String, itemCount: Int = 2): SearchResponse =
        SearchResponse(
            query = query(text),
            providerId = "fake-provider",
            items = (0 until itemCount).map {
                SearchResultItem(title = "t$it", url = "https://example.com/$text/$it", snippet = "s")
            },
            tookMs = 42L
        )

    @Test
    fun `hit returns cached copy flagged as cached`() {
        val clock = FakeClock()
        val cache = SearchResultCache(clock.read, ttlMs = 300_000L)

        cache.put("p1", query("kotlin"), okResponse("kotlin"))

        val hit = cache.get("p1", query("kotlin"))
        assertNotNull(hit)
        assertTrue(hit!!.cached)
        assertEquals(2, hit.items.size)
        assertEquals("t0", hit.items[0].title)
        // 未过期前反复命中
        clock.advance(299_999L)
        assertNotNull(cache.get("p1", query("kotlin")))
    }

    @Test
    fun `entry expires after ttl with fake clock`() {
        val clock = FakeClock()
        val cache = SearchResultCache(clock.read, ttlMs = 300_000L)

        cache.put("p1", query("rust"), okResponse("rust"))
        clock.advance(300_000L) // 恰好到达 TTL（>= 判定过期）

        assertNull(cache.get("p1", query("rust")))
        assertEquals(0, cache.size())
    }

    @Test
    fun `rewrite of same key refreshes ttl`() {
        val clock = FakeClock()
        val cache = SearchResultCache(clock.read, ttlMs = 300_000L)

        cache.put("p1", query("go"), okResponse("go", itemCount = 1))
        clock.advance(200_000L)
        cache.put("p1", query("go"), okResponse("go", itemCount = 3)) // 重新写入刷新时间戳
        clock.advance(200_000L) // 距首次写入 400s，但距刷新仅 200s

        val hit = cache.get("p1", query("go"))
        assertNotNull(hit)
        assertEquals(3, hit!!.items.size)
    }

    @Test
    fun `different query text or max results is a miss`() {
        val clock = FakeClock()
        val cache = SearchResultCache(clock.read, ttlMs = 300_000L)

        cache.put("p1", query("kotlin"), okResponse("kotlin"))

        assertNull(cache.get("p1", query("kotlin!")))
        assertNull(cache.get("p1", query("kotlin", maxResults = 8)))
        assertNull(cache.get("other-provider", query("kotlin")))
        // 原键不受影响
        assertNotNull(cache.get("p1", query("kotlin")))
    }

    @Test
    fun `unsanitized query text hits the same key`() {
        val clock = FakeClock()
        val cache = SearchResultCache(clock.read, ttlMs = 300_000L)

        cache.put("p1", query("  kotlin  "), okResponse("kotlin"))

        // 键以 sanitized 形态计算：两侧空白不改变命中
        assertNotNull(cache.get("p1", query("kotlin")))
    }

    @Test
    fun `failure responses are never cached`() {
        val clock = FakeClock()
        val cache = SearchResultCache(clock.read, ttlMs = 300_000L)
        val failure = SearchResponse(
            query = query("boom"), providerId = "p1", items = emptyList(),
            tookMs = 1L, error = SearchProviderError.network("down")
        )

        cache.put("p1", query("boom"), failure)

        assertNull(cache.get("p1", query("boom")))
        assertEquals(0, cache.size())
    }

    @Test
    fun `lru evicts least recently used beyond capacity`() {
        val clock = FakeClock()
        val cache = SearchResultCache(clock.read, ttlMs = 300_000L, maxEntries = 3)

        cache.put("p", query("a"), okResponse("a"))
        cache.put("p", query("b"), okResponse("b"))
        cache.put("p", query("c"), okResponse("c"))
        assertEquals(3, cache.size())

        // 访问 a：b 变为最久未用
        assertNotNull(cache.get("p", query("a")))
        // 写入第 4 条：容量超限，从 LRU 端逐出 b
        cache.put("p", query("d"), okResponse("d"))

        assertEquals(3, cache.size())
        assertNull(cache.get("p", query("b")))
        assertNotNull(cache.get("p", query("a")))
        assertNotNull(cache.get("p", query("c")))
        assertNotNull(cache.get("p", query("d")))
    }

    @Test
    fun `capacity cap holds even with many distinct keys`() {
        val clock = FakeClock()
        val cache = SearchResultCache(clock.read, ttlMs = 300_000L, maxEntries = 4)

        for (i in 0 until 20) {
            cache.put("p", query("topic-$i"), okResponse("topic-$i"))
        }

        assertEquals(4, cache.size())
        // 最新的四条还在，最早的全部被逐出
        assertNull(cache.get("p", query("topic-0")))
        assertNull(cache.get("p", query("topic-15")))
        assertNotNull(cache.get("p", query("topic-19")))
        assertNotNull(cache.get("p", query("topic-16")))
    }

    @Test
    fun `clear empties everything`() {
        val clock = FakeClock()
        val cache = SearchResultCache(clock.read, ttlMs = 300_000L)

        cache.put("p1", query("x"), okResponse("x"))
        cache.put("p2", query("y"), okResponse("y"))
        assertEquals(2, cache.size())

        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get("p1", query("x")))
        assertNull(cache.get("p2", query("y")))
    }

    @Test
    fun `expired entries are pruned lazily on access`() {
        val clock = FakeClock()
        val cache = SearchResultCache(clock.read, ttlMs = 1_000L)

        cache.put("p1", query("old"), okResponse("old"))
        cache.put("p2", query("older"), okResponse("older"))
        // TTL 内两条都在
        assertEquals(2, cache.size())

        clock.advance(5_000L)
        // 任何读入口都先做过期清理
        assertNull(cache.get("p1", query("old")))
        assertEquals(0, cache.size())
    }

    @Test
    fun `constructor rejects non positive ttl and capacity`() {
        var thrown = false
        try {
            SearchResultCache({ 0L }, ttlMs = 0L)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
        thrown = false
        try {
            SearchResultCache({ 0L }, maxEntries = 0)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
