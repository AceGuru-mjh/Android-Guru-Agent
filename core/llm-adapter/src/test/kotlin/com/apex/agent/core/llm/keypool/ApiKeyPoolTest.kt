package com.apex.agent.core.llm.keypool

import com.apex.agent.core.llm.KeyRotationMode
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ApiKeyPool] 单元测试（Task 4-a）。
 *
 * 仓库测试纪律：JUnit4 + runTest + 手写 fake + 假钟注入（`() -> Long`），
 * 无 mock 框架、不依赖墙钟睡眠。断言值全部先读源码核实（退避 = 基数
 * 30_000ms 起、每次翻倍、封顶 1_800_000ms）。
 *
 * 覆盖矩阵：
 * - SEQUENTIAL 轮询顺序 / 绕回 / 跳过禁用；
 * - Operit 三态门（无标记全放行 / 有标记只放行 AVAILABLE）；
 * - 401/403 → UNAVAILABLE；429/5xx/网络 → COOLDOWN 精确退避；
 * - 冷却到点自愈 + reportSuccess / markTested 显式复活；
 * - RikkaHub LRU 偏好（未用优先 → LRU → 列表序）；
 * - DISABLED 粘滞；四种失败结局；池管理（增删改启停重置 / 游标补偿）；
 * - rotateCursor 粘性轮换；并发 acquire 总量守恒；快照脱敏；
 * - 持久化 JSON 往返 + 旧 JSON 缺字段回退默认值。
 */
class ApiKeyPoolTest {

    // ── 手写假钟 ──────────────────────────────────────────────

    /** 可手动前进的假钟：now 从 1_000 起（非 0——避免"0 = 从未用过"哨兵歧义）。 */
    private class FakeClock(var now: Long = 1_000L) {
        val getter: () -> Long = { now }
        fun advance(ms: Long) { now += ms }
    }

    // ── 构造工具 ──────────────────────────────────────────────

    private fun entry(
        id: String,
        key: String = "sk-$id",
        enabled: Boolean = true,
        status: KeyStatus = KeyStatus.UNTESTED,
        usageCount: Long = 0,
        lastUsedAt: Long = 0,
        cooldownUntilMs: Long = 0
    ) = ApiKeyEntry(
        id = id, label = "label-$id", key = key, enabled = enabled, status = status,
        usageCount = usageCount, lastUsedAt = lastUsedAt, cooldownUntilMs = cooldownUntilMs
    )

    private fun poolOf(
        vararg entries: ApiKeyEntry,
        cursorIndex: Int = 0,
        preferUnusedKeys: Boolean = true,
        clock: () -> Long = { 1_000L }
    ) = ApiKeyPool(
        KeyPoolState(entries = entries.toList(), cursorIndex = cursorIndex, preferUnusedKeys = preferUnusedKeys),
        clock
    )

    private fun idsOf(vararg entries: ApiKeyEntry) = entries.joinToString(",") { it.id }

    // ═════════════ SEQUENTIAL 轮询 ═════════════

    @Test
    fun `sequential rotation order and wrap around`() = runTest {
        val pool = poolOf(entry("a"), entry("b"), entry("c"))
        val order = (1..6).map { pool.acquire(KeyRotationMode.SEQUENTIAL).selectedId }
        assertEquals("a,b,c,a,b,c", order.joinToString(","))

        // 游标随选中前进：a(idx0)→cursor1 → b→2 → c→3(size，指向末尾之外) → 绕回 a→1 …
        // 6 次后停在 3（第 6 次选中 c(idx2) → cursor 3）
        assertEquals(3, pool.currentState().cursorIndex)
    }

    @Test
    fun `sequential skips disabled keys`() = runTest {
        // 头部禁用：b,c 轮询
        val pool = poolOf(entry("a", enabled = false), entry("b"), entry("c"))
        val order = (1..4).map { pool.acquire(KeyRotationMode.SEQUENTIAL).selectedId }
        assertEquals("b,c,b,c", order.joinToString(","))

        // 中间禁用：a,c 轮询
        val pool2 = poolOf(entry("a"), entry("b", enabled = false), entry("c"))
        val order2 = (1..4).map { pool2.acquire(KeyRotationMode.SEQUENTIAL).selectedId }
        assertEquals("a,c,a,c", order2.joinToString(","))
    }

    // ═════════════ 三态可用性门（Operit） ═════════════

    @Test
    fun `no availability mark means all enabled keys are candidates`() = runTest {
        val pool = poolOf(entry("a"), entry("b"), entry("c"))
        val order = (1..3).map { pool.acquire(KeyRotationMode.SEQUENTIAL).selectedId }
        assertEquals("a,b,c", order.joinToString(","))
        // 无任何标记 → skippedCount 恒 0
        assertEquals(0, pool.acquire(KeyRotationMode.SEQUENTIAL).skippedCount)
    }

    @Test
    fun `any mark gates out untested keys`() = runTest {
        val pool = poolOf(entry("a"), entry("b", status = KeyStatus.AVAILABLE), entry("c", status = KeyStatus.AVAILABLE))
        val order = (1..3).map { pool.acquire(KeyRotationMode.SEQUENTIAL).selectedId }
        assertEquals("b,c,b", order.joinToString(","))
        assertEquals(1, pool.acquire(KeyRotationMode.SEQUENTIAL).skippedCount)
    }

    @Test
    fun `unavailable keys are gated out until explicit revival`() = runTest {
        val pool = poolOf(entry("a", status = KeyStatus.AVAILABLE), entry("b", status = KeyStatus.UNAVAILABLE))
        val sel = pool.acquire(KeyRotationMode.SEQUENTIAL)
        assertEquals("a", sel.selectedId)
        assertEquals(1, sel.skippedCount)

        // 显式复活（Operit 可用性测试器语义）。复活前游标已到 1（选中过 a(idx0)），
        // 复活后 SEQUENTIAL 从游标续扫：b(idx1) → 绕回 a
        pool.markTested("b", available = true)
        val order = (1..2).map { pool.acquire(KeyRotationMode.SEQUENTIAL).selectedId }
        assertEquals("b,a", order.joinToString(","))
    }

    // ═════════════ 失败上报与退避 ═════════════

    @Test
    fun `auth failure marks unavailable with no cooldown`() = runTest {
        val pool = poolOf(entry("a"))
        pool.reportFailure("a", 401, "Unauthorized")
        val e = pool.currentState().entryById("a")!!
        assertEquals(KeyStatus.UNAVAILABLE, e.status)
        assertEquals(0L, e.cooldownUntilMs)
        assertEquals(1L, e.totalFailures)
        assertEquals(1, e.consecutiveFailures)
        assertEquals(401, e.lastErrorCode)
        assertEquals("Unauthorized", e.lastErrorMessage)

        val sel = pool.acquire(KeyRotationMode.SEQUENTIAL)
        assertEquals(KeySelectionOutcome.ALL_UNAVAILABLE, sel.outcome)
        assertEquals(1, sel.skippedCount)
        assertNull(sel.entry)
    }

    @Test
    fun `rate limit cooldown backoff math is exact with fake clock`() = runTest {
        val clock = FakeClock()
        val pool = poolOf(entry("a"), entry("b"), entry("c"), entry("d"), clock = clock.getter)

        // 连续失败 1/2/3 次 → 30s / 60s / 120s（基数 2^n-1 倍增）
        pool.reportFailure("a", 429)
        assertEquals(1_000L + 30_000L, pool.currentState().entryById("a")!!.cooldownUntilMs)
        pool.reportFailure("a", 429)
        assertEquals(1_000L + 60_000L, pool.currentState().entryById("a")!!.cooldownUntilMs)
        pool.reportFailure("a", 429)
        assertEquals(1_000L + 120_000L, pool.currentState().entryById("a")!!.cooldownUntilMs)
        assertEquals(KeyStatus.COOLDOWN, pool.currentState().entryById("a")!!.status)

        // 5xx 与网络层（code <= 0）同样进冷却
        pool.reportFailure("b", 503, "upstream boom")
        assertEquals(1_000L + 30_000L, pool.currentState().entryById("b")!!.cooldownUntilMs)
        pool.reportFailure("c", 0, "timeout")
        assertEquals(KeyStatus.COOLDOWN, pool.currentState().entryById("c")!!.status)

        // 请求级错误（400）只记统计、不动状态——换 key 也救不了请求体
        pool.reportFailure("d", 400, "bad request")
        val d = pool.currentState().entryById("d")!!
        assertEquals(KeyStatus.UNTESTED, d.status)
        assertEquals(0L, d.cooldownUntilMs)
        assertEquals(1L, d.totalFailures)
    }

    @Test
    fun `backoff caps at 30 minutes`() {
        val expected = listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_800_000L)
        expected.forEachIndexed { i, ms ->
            assertEquals("backoff for ${i + 1} failures", ms, ApiKeyPool.cooldownBackoffMs(i + 1))
        }
        // 深夜长跑也封顶
        assertEquals(1_800_000L, ApiKeyPool.cooldownBackoffMs(50))
        assertEquals(ApiKeyPool.MAX_COOLDOWN_MS, ApiKeyPool.cooldownBackoffMs(50))
        assertEquals(30_000L, ApiKeyPool.BASE_COOLDOWN_MS)
    }

    @Test
    fun `all keys cooling down returns ALL_COOLING_DOWN`() = runTest {
        val clock = FakeClock()
        val pool = poolOf(entry("a"), entry("b"), clock = clock.getter)
        pool.reportFailure("a", 429)
        pool.reportFailure("b", 429)

        val sel = pool.acquire(KeyRotationMode.SEQUENTIAL)
        assertEquals(KeySelectionOutcome.ALL_COOLING_DOWN, sel.outcome)
        assertEquals(2, sel.skippedCount)
        assertNull(sel.selectedEntry)

        // 冷却中的 key + 鉴权死 key 并存 → 仍有救（等冷却）→ ALL_COOLING_DOWN
        val pool2 = poolOf(entry("a"), entry("b"), clock = clock.getter)
        pool2.reportFailure("a", 429)
        pool2.reportFailure("b", 403, "forbidden")
        assertEquals(KeySelectionOutcome.ALL_COOLING_DOWN, pool2.acquire(KeyRotationMode.SEQUENTIAL).outcome)
    }

    @Test
    fun `untested keys serve as fallback when no good key remains`() = runTest {
        // 一档全灭（k1 鉴权死）→ 回退启用未测 key，而不是判死整池
        val pool = poolOf(entry("a"), entry("b"))
        pool.reportFailure("a", 401)
        assertEquals("b", pool.acquire(KeyRotationMode.SEQUENTIAL).selectedId)

        // 一档全冷却时同样回退未测 key（新鲜池首次限流不会死锁）
        val pool2 = poolOf(entry("a"), entry("b"))
        pool2.reportFailure("a", 429)
        assertEquals("b", pool2.acquire(KeyRotationMode.ON_ERROR).selectedId)
    }

    @Test
    fun `cooldown expiry self-heals key back into rotation`() = runTest {
        val clock = FakeClock()
        val pool = poolOf(entry("a"), clock = clock.getter)
        pool.reportFailure("a", 429) // 冷却到 1_000 + 30_000 = 31_000

        // 冷却窗口内：出局（无其它 key 可回退）
        assertEquals(KeySelectionOutcome.ALL_COOLING_DOWN, pool.acquire(KeyRotationMode.SEQUENTIAL).outcome)

        // 到点之后：自动回归候选，且状态刷新回 AVAILABLE（自愈）
        clock.advance(30_001)
        val sel = pool.acquire(KeyRotationMode.ON_ERROR)
        assertEquals("a", sel.selectedId)
        assertEquals(KeyStatus.AVAILABLE, pool.currentState().entryById("a")!!.status)
        assertTrue(sel.reason.contains("self-healed"))
    }

    @Test
    fun `reportSuccess lifts cooldown and resets consecutive failures`() = runTest {
        val clock = FakeClock()
        val pool = poolOf(entry("a"), clock = clock.getter)
        pool.reportFailure("a", 429)
        pool.reportFailure("a", 429)
        clock.advance(500)

        pool.reportSuccess("a")
        val e = pool.currentState().entryById("a")!!
        assertEquals(KeyStatus.AVAILABLE, e.status)
        assertEquals(0L, e.cooldownUntilMs)
        assertEquals(1L, e.usageCount)
        assertEquals(0, e.consecutiveFailures)
        assertEquals(1_500L, e.lastUsedAt)

        assertEquals("a", pool.acquire(KeyRotationMode.ON_ERROR).selectedId)
    }

    @Test
    fun `markTested flips status without touching counters`() = runTest {
        val pool = poolOf(entry("a"), entry("b"))
        pool.reportFailure("a", 429) // 计数 + 冷却
        pool.markTested("a", available = true)
        val a = pool.currentState().entryById("a")!!
        assertEquals(KeyStatus.AVAILABLE, a.status)
        assertEquals(1L, a.totalFailures) // 计数是另一本账，测试器只翻状态
        assertEquals(0L, a.cooldownUntilMs)

        pool.markTested("b", available = false)
        assertEquals(KeyStatus.UNAVAILABLE, pool.currentState().entryById("b")!!.status)
        assertEquals(0L, pool.currentState().entryById("b")!!.totalFailures)
    }

    @Test
    fun `success on auth-dead key does not silently revive it`() = runTest {
        val pool = poolOf(entry("a"))
        pool.reportFailure("a", 401)
        pool.reportSuccess("a") // 偶发成功不掩盖死 key
        val e = pool.currentState().entryById("a")!!
        assertEquals(KeyStatus.UNAVAILABLE, e.status)
        assertEquals(1L, e.usageCount) // 使用统计照记
        assertEquals(0, e.consecutiveFailures)
    }

    // ═════════════ LRU 偏好（RikkaHub） ═════════════

    @Test
    fun `lru preference picks unused first then least recently used`() = runTest {
        val clock = FakeClock()
        // 全部预先标记 AVAILABLE：三把都在一档候选里，LRU 偏好才有用武之地
        // （否则首次 reportSuccess 后其余未测 key 会被三态门住）
        val pool = poolOf(
            entry("a", status = KeyStatus.AVAILABLE),
            entry("b", status = KeyStatus.AVAILABLE),
            entry("c", status = KeyStatus.AVAILABLE),
            clock = clock.getter
        )

        // 未用桶（列表序）→ a
        assertEquals("a", pool.acquire(KeyRotationMode.ON_ERROR).selectedId)
        pool.reportSuccess("a") // lastUsed = 1_000

        clock.advance(100)
        assertEquals("b", pool.acquire(KeyRotationMode.ON_ERROR).selectedId) // 未用桶剩 b,c
        pool.reportSuccess("b") // lastUsed = 1_100

        clock.advance(100)
        assertEquals("c", pool.acquire(KeyRotationMode.ON_ERROR).selectedId) // 最后一把未用
        pool.reportSuccess("c") // lastUsed = 1_200

        clock.advance(100)
        assertEquals("a", pool.acquire(KeyRotationMode.ON_ERROR).selectedId) // 全用过 → LRU=a(1000)
        pool.reportSuccess("a") // 1_300

        clock.advance(100)
        assertEquals("b", pool.acquire(KeyRotationMode.ON_ERROR).selectedId) // LRU=b(1100)
    }

    @Test
    fun `lru preference orders purely by lastUsedAt when all keys are used`() = runTest {
        val pool = poolOf(
            entry("x", status = KeyStatus.AVAILABLE, usageCount = 1, lastUsedAt = 500),
            entry("y", status = KeyStatus.AVAILABLE, usageCount = 1, lastUsedAt = 300),
            entry("z", status = KeyStatus.AVAILABLE, usageCount = 1, lastUsedAt = 400)
        )
        assertEquals("y", pool.acquire(KeyRotationMode.ON_RATE_LIMIT).selectedId)
    }

    @Test
    fun `preferUnusedKeys off falls back to sticky list-order selection`() = runTest {
        val pool = poolOf(entry("a"), entry("b"), entry("c"), preferUnusedKeys = false)
        val order = (1..3).map { pool.acquire(KeyRotationMode.ON_ERROR).selectedId }
        assertEquals("a,a,a", order.joinToString(","))
    }

    // ═════════════ DISABLED 粘滞 ═════════════

    @Test
    fun `disabled mode is sticky on the first candidate`() = runTest {
        // 全部标记为 AVAILABLE：三把都是候选，才能证明 DISABLED 无视 LRU 偏好
        val pool = poolOf(
            entry("a", status = KeyStatus.AVAILABLE),
            entry("b", status = KeyStatus.AVAILABLE),
            entry("c", status = KeyStatus.AVAILABLE)
        )
        val order = (1..3).map { pool.acquire(KeyRotationMode.DISABLED).selectedId }
        assertEquals("a,a,a", order.joinToString(","))

        // 即便 a 已用过（LRU 偏好会想换 b）也粘住——DISABLED 的"永远第一个"优先
        pool.reportSuccess("a")
        assertEquals("a", pool.acquire(KeyRotationMode.DISABLED).selectedId)
        // 游标不受 DISABLED 影响
        assertEquals(0, pool.currentState().cursorIndex)
    }

    // ═════════════ 失败结局 ═════════════

    @Test
    fun `empty pool all disabled and all unavailable outcomes`() = runTest {
        assertEquals(
            KeySelectionOutcome.POOL_EMPTY,
            poolOf().acquire(KeyRotationMode.SEQUENTIAL).outcome
        )
        assertEquals(
            KeySelectionOutcome.ALL_DISABLED,
            poolOf(entry("a", enabled = false), entry("b", enabled = false))
                .acquire(KeyRotationMode.SEQUENTIAL).outcome
        )
        val pool = poolOf(entry("a"), entry("b"))
        pool.reportFailure("a", 401)
        pool.reportFailure("b", 401)
        val sel = pool.acquire(KeyRotationMode.SEQUENTIAL)
        assertEquals(KeySelectionOutcome.ALL_UNAVAILABLE, sel.outcome)
        assertEquals(2, sel.skippedCount)
    }

    // ═════════════ 池管理 ═════════════

    @Test
    fun `addKey appends and upserts by id`() = runTest {
        val pool = poolOf(entry("a"))
        pool.addKey(entry("b"))
        assertEquals(idsOf(entry("a"), entry("b")), idsOf(*pool.currentState().entries.toTypedArray()))

        // 同 id 原位替换（幂等）
        pool.addKey(entry("b", key = "sk-new-b", status = KeyStatus.AVAILABLE))
        val entries = pool.currentState().entries
        assertEquals(2, entries.size)
        assertEquals("sk-new-b", entries[1].key)
        assertEquals(KeyStatus.AVAILABLE, entries[1].status)
    }

    @Test
    fun `removeKey adjusts cursor so rotation does not skip`() = runTest {
        val pool = poolOf(entry("a"), entry("b"), entry("c"))
        pool.acquire(KeyRotationMode.SEQUENTIAL) // 选 a，游标 → 1
        pool.removeKey("a") // 删 idx0 < 游标1 → 游标补偿为 0
        assertEquals(0, pool.currentState().cursorIndex)
        val order = (1..3).map { pool.acquire(KeyRotationMode.SEQUENTIAL).selectedId }
        assertEquals("b,c,b", order.joinToString(","))
    }

    @Test
    fun `setEnabled toggles candidacy`() = runTest {
        val pool = poolOf(entry("a"), entry("b"))
        pool.setEnabled("a", false)
        assertEquals("b", pool.acquire(KeyRotationMode.SEQUENTIAL).selectedId)
        pool.setEnabled("a", true)
        val order = (1..2).map { pool.acquire(KeyRotationMode.SEQUENTIAL).selectedId }
        assertEquals("a,b", order.joinToString(","))
    }

    @Test
    fun `resetKey fully restores a key`() = runTest {
        val pool = poolOf(entry("a"))
        pool.reportFailure("a", 429, "slow down")
        pool.reportSuccess("a")
        pool.resetKey("a")
        val e = pool.currentState().entryById("a")!!
        assertEquals(KeyStatus.UNTESTED, e.status)
        assertEquals(0L, e.usageCount)
        assertEquals(0L, e.totalFailures)
        assertEquals(0, e.consecutiveFailures)
        assertEquals(0L, e.lastUsedAt)
        assertEquals(0, e.lastErrorCode)
        assertEquals("", e.lastErrorMessage)
    }

    @Test
    fun `resetKey on unknown id is a no-op`() = runTest {
        val pool = poolOf(entry("a"))
        pool.resetKey("ghost")
        assertEquals(1, pool.currentState().entries.size)
        assertEquals(KeyStatus.UNTESTED, pool.currentState().entryById("a")!!.status)
    }

    // ═════════════ rotateCursor ═════════════

    @Test
    fun `rotateCursor advances sticky selection and wraps`() = runTest {
        val pool = poolOf(entry("a"), entry("b"), entry("c"), preferUnusedKeys = false)

        assertEquals("a", pool.acquire(KeyRotationMode.ON_ERROR).selectedId) // 粘 a
        pool.rotateCursor() // → b
        assertEquals("b", pool.acquire(KeyRotationMode.ON_ERROR).selectedId)
        pool.rotateCursor() // → c
        assertEquals("c", pool.acquire(KeyRotationMode.ON_ERROR).selectedId)
        pool.rotateCursor() // 绕回 → a
        assertEquals("a", pool.acquire(KeyRotationMode.ON_ERROR).selectedId)
    }

    @Test
    fun `acquire never mutates usage statistics`() = runTest {
        val pool = poolOf(entry("a"), entry("b"))
        repeat(4) { pool.acquire(KeyRotationMode.SEQUENTIAL) }
        pool.currentState().entries.forEach {
            assertEquals(0L, it.usageCount)
            assertEquals(0L, it.lastUsedAt)
        }
    }

    // ═════════════ 并发 ═════════════

    @Test
    fun `concurrent acquires keep round-robin totals balanced`() = runTest {
        val pool = poolOf(entry("a"), entry("b"), entry("c"))
        val results = mutableListOf<KeySelection>()
        val jobs = (1..9).map {
            launch { results += pool.acquire(KeyRotationMode.SEQUENTIAL) }
        }
        jobs.joinAll() // 显式等子协程完成（断言前不能只靠 runTest 收尾隐等待）
        // Mutex 串行化：9 次 acquire 恰好 3 轮，每把 key 各 3 次（总量守恒）
        assertEquals(9, results.size)
        assertEquals(9, results.count { it.isSelected })
        listOf("a", "b", "c").forEach { id ->
            assertEquals("acquire count for $id", 3, results.count { it.selectedId == id })
        }
    }

    // ═════════════ 快照 / 秘钥卫生 ═════════════

    @Test
    fun `snapshot reports masked keys and cooldown math`() = runTest {
        val clock = FakeClock()
        val pool = poolOf(
            entry("a", key = "sk-abcdefgh12345"),
            entry("b", key = "shortkey", enabled = false),
            clock = clock.getter
        )
        pool.reportFailure("a", 429) // 冷却到 31_000；now = 1_000 → 剩 30_000
        clock.advance(1_000) // now = 2_000 → 剩 29_000
        pool.reportSuccess("a") // 复活 + 记一次使用
        pool.reportFailure("a", 429) // 再次冷却到 2_000 + 30_000 = 32_000 → 剩 30_000

        val snap = pool.snapshot()
        assertEquals(2, snap.totalKeys)
        assertEquals(1, snap.enabledKeys) // b 禁用
        // 状态计数只看 enabled：a 冷却中，无 AVAILABLE/UNTESTED/UNAVAILABLE
        assertEquals(1, snap.coolingDownKeys)
        assertEquals(0, snap.availableKeys)
        assertEquals(0, snap.untestedKeys)
        assertEquals(0, snap.unavailableKeys)
        assertEquals(1, snap.totalUsageCount)
        assertEquals(2, snap.totalFailures) // 两次 429 上报

        val a = snap.keys.first { it.id == "a" }
        assertEquals(30_000L, a.cooldownRemainingMs)
        assertEquals("sk-…2345", a.keyMasked) // 前3 + 后4 脱敏
        assertFalse(a.keyMasked.contains("abcdefgh"))

        val b = snap.keys.first { it.id == "b" }
        assertEquals("••••", b.keyMasked) // 短 key 全打码

        assertTrue(snap.summary().contains("enabled=1"))
    }

    @Test
    fun `selection reason and outcome carry no key material`() = runTest {
        val pool = poolOf(entry("a", key = "sk-SUPER-SECRET-MATERIAL"))
        val sel = pool.acquire(KeyRotationMode.ON_ERROR)
        val reasonText = sel.reason + sel.outcome.name
        assertFalse(reasonText.contains("sk-SUPER-SECRET-MATERIAL"))
        assertFalse(reasonText.contains("SECRET"))
    }

    // ═════════════ 持久化往返 ═════════════

    @Test
    fun `key pool state survives json round trip`() = runTest {
        val clock = FakeClock()
        val pool = poolOf(entry("a"), entry("b"), clock = clock.getter)
        pool.reportFailure("b", 429, "quota")
        pool.reportSuccess("a")

        val json = Json.encodeToString(KeyPoolState.serializer(), pool.currentState())
        val restored = Json.decodeFromString(KeyPoolState.serializer(), json)
        assertEquals(pool.currentState(), restored)

        // 恢复出来的池行为一致
        val restoredPool = ApiKeyPool(restored, clock.getter)
        assertEquals(pool.snapshot().summary(), restoredPool.snapshot().summary())
    }

    @Test
    fun `old json with missing fields loads via defaults`() = runTest {
        // KeyPoolState 只带 entries（旧版本落盘形态）→ 游标/模式/偏好回默认
        val stateJson = """{"entries":[{"id":"a","key":"sk-a"}]}"""
        val state = Json.decodeFromString(KeyPoolState.serializer(), stateJson)
        assertEquals(1, state.entries.size)
        assertEquals(0, state.cursorIndex)
        assertEquals(KeyRotationMode.DISABLED, state.rotationMode)
        assertTrue(state.preferUnusedKeys)

        // 条目缺字段：状态/计数全部默认
        val a = state.entries[0]
        assertEquals(KeyStatus.UNTESTED, a.status)
        assertTrue(a.enabled)
        assertEquals(0L, a.usageCount)
        assertEquals(0, a.consecutiveFailures)
        assertEquals("", a.label)

        // 空池 JSON
        val empty = Json.decodeFromString(KeyPoolState.serializer(), """{"entries":[]}""")
        assertTrue(empty.entries.isEmpty())
    }

    @Test
    fun `replaceState hydrates and normalizes injected cursor`() = runTest {
        val pool = poolOf(entry("a"))
        pool.replaceState(
            KeyPoolState(
                entries = listOf(entry("x"), entry("y"), entry("z")),
                cursorIndex = 99, // 越界 → 规整到 entries.size
                preferUnusedKeys = false
            )
        )
        assertEquals(3, pool.currentState().cursorIndex)
        // 规整后从队尾之外绕回：SEQUENTIAL 选中 x
        assertEquals("x", pool.acquire(KeyRotationMode.SEQUENTIAL).selectedId)
    }

    // ═════════════ ApiKeyEntry 纯函数 ═════════════

    @Test
    fun `isCoolingDown checks both status and time`() {
        val cooling = entry("a", status = KeyStatus.COOLDOWN, cooldownUntilMs = 100)
        assertTrue(cooling.isCoolingDown(99))
        assertFalse(cooling.isCoolingDown(100)) // 到点即出冷却（> 判定）
        assertFalse(cooling.isCoolingDown(101))

        // AVAILABLE 残留旧时间戳也不算冷却（状态与时间双检）
        assertFalse(entry("a", status = KeyStatus.AVAILABLE, cooldownUntilMs = 100).isCoolingDown(50))
    }

    @Test
    fun `maskKey never reveals short keys`() {
        assertEquals("••••", maskKey("abc"))
        assertEquals("••••", maskKey("12345678"))
        assertEquals("sk-…2345", maskKey("sk-abcdefgh12345"))
        assertTrue(maskKey("sk-abcdefgh12345").length < "sk-abcdefgh12345".length)
    }
}
