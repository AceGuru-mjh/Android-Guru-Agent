package com.apex.agent.core.tools.search

/**
 * 搜索结果缓存（LRU + TTL，线程安全）。
 *
 * - LRU：LinkedHashMap(accessOrder = true)，访问即刷新新鲜度，
 *   容量超限时从最久未用端逐出；
 * - TTL：所有读写入口先 [pruneExpired]（假钟注入，测试不睡眠）；
 * - 键：providerId + sanitized 查询参数的规范串（域名列表排序后
 *   参与，保证 "a,b" 与 "b,a" 同键；maxResults 参与键——不同条数
 *   视为不同请求，宁可多打一次供应商也不返回缺斤短两的缓存）。
 *
 * 只缓存成功响应（error == null），失败不占容量。
 */
class SearchResultCache(
    private val clock: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    init {
        require(ttlMs > 0) { "ttlMs must be positive, got $ttlMs" }
        require(maxEntries >= 1) { "maxEntries must be >= 1, got $maxEntries" }
    }

    private class Entry(val storedAtMs: Long, val response: SearchResponse)

    private val lock = Any()

    /** accessOrder = true：get 与 put 都把条目移到最近使用端。 */
    private val map = LinkedHashMap<String, Entry>(16, 0.75f, true)

    /**
     * 取缓存命中：过期先行清理；命中返回 cached = true 的副本
     * （[SearchResponse] 是不可变数据类，copy 即快照）。
     */
    fun get(providerId: String, query: SearchQuery): SearchResponse? = synchronized(lock) {
        pruneExpired()
        val entry = map[cacheKey(providerId, query)] ?: return null
        entry.response.copy(cached = true)
    }

    /** 写入成功响应（error 响应直接忽略）；随后按容量从 LRU 端逐出。 */
    fun put(providerId: String, query: SearchQuery, response: SearchResponse) {
        if (response.error != null) return
        synchronized(lock) {
            pruneExpired()
            map[cacheKey(providerId, query)] = Entry(clock(), response)
            evictOverCapacity()
        }
    }

    fun clear() = synchronized(lock) { map.clear() }

    /** 当前有效条目数（先做过期清理，测试可依赖其确定性）。 */
    fun size(): Int = synchronized(lock) {
        pruneExpired()
        map.size
    }

    /** 超容量逐出：accessOrder 迭代序即 LRU 序，从头部删到够量。 */
    private fun evictOverCapacity() {
        val iterator = map.entries.iterator()
        while (map.size > maxEntries && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    /** 过期清理：clock() - storedAt >= ttl 即删（假钟推进即过期）。 */
    private fun pruneExpired() {
        val now = clock()
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (now - entry.storedAtMs >= ttlMs) {
                iterator.remove()
            }
        }
    }

    /** 缓存键：供应商 + 全部语义参数的规范串（internal 供单测断言）。 */
    internal fun cacheKey(providerId: String, query: SearchQuery): String {
        val q = query.sanitized()
        return buildString {
            append(providerId).append('|')
            append("q=").append(q.query).append('|')
            append("n=").append(q.maxResults).append('|')
            append("safe=").append(q.safeSearch).append('|')
            append("t=").append(q.timeRange ?: "").append('|')
            append("inc=").append(q.includeDomains.sorted().joinToString(",")).append('|')
            append("exc=").append(q.excludeDomains.sorted().joinToString(",")).append('|')
            append("lang=").append(q.language)
        }
    }

    companion object {
        /** 默认 TTL：5 分钟（搜索结果时效性与命中率折中）。 */
        const val DEFAULT_TTL_MS = 300_000L

        /** 默认容量：64 条（约等于 8 个查询 × 8 供应商，内存占用可忽略）。 */
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
