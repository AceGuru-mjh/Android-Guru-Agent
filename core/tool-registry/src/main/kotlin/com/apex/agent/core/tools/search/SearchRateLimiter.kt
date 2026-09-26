package com.apex.agent.core.tools.search

/**
 * 搜索限流器（每供应商一个令牌桶，假钟注入）。
 *
 * 目的：API 型搜索供应商普遍按次计费 / 有 QPS 配额，本地先用令牌桶
 * 兜住突发——注册表在每次尝试前 [tryAcquire]，耗尽即跳过该供应商换
 * 下一个（免 key 爬虫供应商也各自计桶，防反爬封禁）。
 *
 * 语义：
 * - 桶初始满（capacity 个令牌）；
 * - 按时钟流逝匀速回填（refillPerMinute / 分钟），回填上限 capacity；
 * - 时钟回拨（elapsed <= 0）不扣不加，保证假钟测试可任意推进。
 */
class SearchRateLimiter(
    private val clock: () -> Long,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val refillPerMinute: Double = DEFAULT_REFILL_PER_MINUTE
) {
    init {
        require(capacity >= 1) { "capacity must be >= 1, got $capacity" }
        require(refillPerMinute >= 0.0) { "refillPerMinute must be >= 0, got $refillPerMinute" }
    }

    private class BucketState(var tokens: Double, var lastRefillMs: Long)

    private val lock = Any()
    private val buckets = HashMap<String, BucketState>()

    /**
     * 尝试获取一个令牌：够则扣一个返回 true；不足返回 false（不欠账）。
     */
    fun tryAcquire(providerId: String): Boolean = synchronized(lock) {
        val now = clock()
        val bucket = buckets.getOrPut(providerId) { BucketState(capacity.toDouble(), now) }
        refill(bucket, now)
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            true
        } else {
            false
        }
    }

    /** 当前可用令牌数（向下取整；查询本身不消耗、不触发新建副作用之外的状态）。 */
    fun availablePermits(providerId: String): Int = synchronized(lock) {
        val now = clock()
        val bucket = buckets.getOrPut(providerId) { BucketState(capacity.toDouble(), now) }
        refill(bucket, now)
        bucket.tokens.toInt()
    }

    /** 匀速回填：elapsed 毫秒 × 每分钟速率 ÷ 60000，封顶 capacity。 */
    private fun refill(bucket: BucketState, now: Long) {
        val elapsedMs = now - bucket.lastRefillMs
        if (elapsedMs <= 0) return
        val gained = elapsedMs * refillPerMinute / 60_000.0
        bucket.tokens = minOf(capacity.toDouble(), bucket.tokens + gained)
        bucket.lastRefillMs = now
    }

    companion object {
        /** 默认容量：5 次突发。 */
        const val DEFAULT_CAPACITY = 5

        /** 默认回填：每分钟 10 个令牌（6 秒 1 个）。 */
        const val DEFAULT_REFILL_PER_MINUTE = 10.0
    }
}
