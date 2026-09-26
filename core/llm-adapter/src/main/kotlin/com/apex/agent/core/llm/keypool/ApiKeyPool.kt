package com.apex.agent.core.llm.keypool

import com.apex.agent.core.llm.KeyRotationMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * # API Key 池（Task 4-a）
 *
 * 融合 Operit `MultiApiKeyProvider`（三态可用性 + 持久化轮询游标）与
 * RikkaHub `LruKeyRoulette`（未用优先 + LRU）的纯 JVM key 池。
 * apex-agent 的 `ProviderConfig.apiKeys` / [KeyRotationMode] 数据层早已预埋，
 * 本类补上选择 / 健康 / 退避的全部池内逻辑；HTTP 重试编排由
 * [KeyPoolLlmClient]（请求层包装器）负责——**池不碰网络**。
 *
 * ## 线程模型
 * - 所有变更操作（acquire / report 系列 / add / remove / setEnabled /
 *   rotateCursor / replaceState）走 [Mutex] 串行化——同一时刻只有一次选 key，
 *   游标不会被并发抢跑（Operit 的 getApiKey 也是 Mutex 包裹）；
 * - 每次变更是**不可变克隆**（KeyPoolState.copy + 新 List），旧状态无所有权问题；
 * - [snapshot] 无锁直读 [state] 的 @Volatile 副本；[currentState] 持锁读全量。
 *
 * ## acquire 语义（按 [KeyRotationMode]）
 *
 * 候选过滤（所有模式共用，Operit 三态门 + 死锁规避回退，两档制）：
 * 1. `enabled == false` 一律排除；
 * 2. **一档候选** = AVAILABLE + 冷却到点的 COOLDOWN（自愈，选中时状态刷新回
 *    AVAILABLE）——即 Operit 的"有任何可用性结论就只用结论良好的 key"：
 *    一档非空时 UNTESTED 被门住（测过的结论优先于没测的）；
 * 3. **回退档** = UNTESTED：仅当一档全灭（全死 / 全冷却）时启用未测 key，
 *    而不是直接判死整池——Operit 的标记只来自人工测试器，本池标记还来自
 *    线上流量（reportFailure 上报），严格三态门会让"新鲜池第一次限流"
 *    即全池死锁（试过的 key 进冷却、没试过的被门住、永远选不出 key）；
 * 4. UNAVAILABLE（401/403 判死或人工标记）永远出局，需 markTested / resetKey
 *    显式复活；冷却中的 key 到点自动回归一档（免死锁）。
 *
 * 候选内取 key（skippedCount = entries.size - 候选数）：
 * - **DISABLED**：永远取列表顺序第一个候选（sticky、不动游标）。
 *   "第一个 enabled key" 在坏 key 被过滤后自然滑向下一位——跨调用级兜底，
 *   但单次调用内不轮换（轮换语义由 [KeyPoolLlmClient] 按 DISABLED 关闭）。
 * - **SEQUENTIAL**：从游标起向后找第一个候选，绕回开头，选中后游标 = 下标 + 1
 *   （Operit 的 currentKeyIndex 轮询；禁用 key 在扫描时被自然跳过）。
 * - **ON_ERROR / ON_RATE_LIMIT**（粘性）：
 *   - `preferUnusedKeys == false`：同 SEQUENTIAL 的扫描，但选中后游标**回写
 *     选中下标**（粘住当前 key）；轮换只发生在 [rotateCursor]（由 client
 *     包装器在 reportFailure 之后按模式调用）或当前 key 被过滤出局时；
 *   - `preferUnusedKeys == true`（默认，RikkaHub LruKeyRoulette 语义）：
 *     候选先按"从未用过（usageCount == 0）桶优先、桶内列表序"再按
 *     "lastUsedAt 升序（LRU）、并列取列表序"排序取首个——负载自然分摊，
 *     且**裸 acquire 不改统计**，无上报时重复 acquire 结果稳定（可测试性）。
 * - 候选为空时按"是否存在冷却中的 key"返回 ALL_COOLING_DOWN（有救）或
 *   ALL_UNAVAILABLE（无救）；池空 → POOL_EMPTY；全禁用 → ALL_DISABLED。
 *
 * ## reportFailure 分类（池内纯状态机）
 * - 401 / 403 → UNAVAILABLE（key 大概率死了，不设冷却，需显式复活）；
 * - 429 / 5xx / code == 0（网络层哨兵）→ COOLDOWN + 指数退避
 *   `now + min(BASE * 2^(连续失败数-1), MAX)`（首次 30s，60s，120s … 封顶 30min）；
 * - 其它（400/404 等请求级错误、-1 无码错误）→ 只累计失败统计，不动状态
 *   （请求体换个 key 也一样错，不惩罚 key）。
 *
 * @param initial 初始状态（app 层从持久化 JSON 恢复时传入）
 * @param clock   假钟注入——测试用 `() -> Long` 驱动冷却 / LRU 时间分支，
 *                不依赖墙钟睡眠（仓库测试纪律）
 */
class ApiKeyPool(
    initial: KeyPoolState = KeyPoolState(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val mutex = Mutex()

    /** 最新状态副本——写侧持锁换引用，读侧无锁直读（快照 / 诊断）。 */
    @Volatile
    private var state: KeyPoolState = initial.normalized()

    // ═══════════════════════════════ 核心：选 key ═══════════════════════════════

    /**
     * 按 [mode] 选一把 key。确定性：同状态 + 同时钟读数 → 同结果
     * （无 Random——RikkaHub 的 Default 轮盘用随机，这里为可测试性改 LRU）。
     *
     * 变更副作用（持锁内完成）：
     * - SEQUENTIAL：游标前进到 选中下标+1；
     * - 粘性模式：游标回写选中下标（preferUnusedKeys=true 时该游标不参与
     *   选择，仅为 rotateCursor 的锚点记账）；
     * - 选中"冷却已到点"的 key 时顺带把状态刷新回 AVAILABLE（自愈）。
     */
    suspend fun acquire(mode: KeyRotationMode): KeySelection = mutex.withLock {
        val now = clock()
        val s = state

        if (s.entries.isEmpty()) {
            return@withLock KeySelection(
                outcome = KeySelectionOutcome.POOL_EMPTY,
                skippedCount = 0,
                reason = "key pool has no entries"
            )
        }

        val enabled = s.entries.filter { it.enabled }
        if (enabled.isEmpty()) {
            return@withLock KeySelection(
                outcome = KeySelectionOutcome.ALL_DISABLED,
                skippedCount = s.entries.size,
                reason = "all ${s.entries.size} key(s) are disabled"
            )
        }

        // 两档候选（见类 KDoc）：一档 = 结论良好（AVAILABLE + 冷却到点自愈），
        // 一档非空时 UNTESTED 被门住（Operit 三态）；一档全灭 → 回退未测 key
        // （避免线上报错标记把新鲜池直接判死）。
        val bestTier: List<Int> = s.entries.withIndex()
            .filter { (_, e) ->
                e.enabled && !e.isCoolingDown(now) &&
                    e.status != KeyStatus.UNAVAILABLE && e.status != KeyStatus.UNTESTED
            }
            .map { (i, _) -> i }
        val untestedTier: List<Int> = s.entries.withIndex()
            .filter { (_, e) -> e.enabled && e.status == KeyStatus.UNTESTED }
            .map { (i, _) -> i }
        val candidateIndexes: List<Int> = if (bestTier.isNotEmpty()) bestTier else untestedTier

        val skipped = s.entries.size - candidateIndexes.size

        if (candidateIndexes.isEmpty()) {
            val anyCooling = enabled.any { it.isCoolingDown(now) }
            return@withLock if (anyCooling) {
                KeySelection(
                    outcome = KeySelectionOutcome.ALL_COOLING_DOWN,
                    skippedCount = skipped,
                    reason = "no candidate key; ${enabled.count { it.isCoolingDown(now) }} " +
                        "key(s) still cooling down (wait for cooldown to expire)"
                )
            } else {
                KeySelection(
                    outcome = KeySelectionOutcome.ALL_UNAVAILABLE,
                    skippedCount = skipped,
                    reason = "no candidate key; all enabled keys are UNAVAILABLE " +
                        "or cooling (run availability test, reset, or wait)"
                )
            }
        }

        when (mode) {
            KeyRotationMode.DISABLED -> {
                // 永远第一个候选：sticky、不动游标、不受 preferUnusedKeys 影响
                val idx = candidateIndexes.first()
                commitSelection(idx, skipped, moveCursor = false)
            }

            KeyRotationMode.SEQUENTIAL -> {
                val anchor = s.cursorIndex.coerceIn(0, s.entries.size)
                val idx = candidateIndexes.firstOrNull { it >= anchor }
                    ?: candidateIndexes.first() // 绕回
                commitSelection(idx, skipped, moveCursor = true)
            }

            KeyRotationMode.ON_ERROR,
            KeyRotationMode.ON_RATE_LIMIT -> {
                if (s.preferUnusedKeys) {
                    // RikkaHub LruKeyRoulette：未用优先 → LRU → 列表序并列破
                    val idx = candidateIndexes.sortedWith(
                        compareBy(
                            { i -> if (s.entries[i].usageCount == 0L) 0 else 1 },
                            { i -> s.entries[i].lastUsedAt },
                            { i -> i }
                        )
                    ).first()
                    commitSelection(idx, skipped, moveCursor = false, stickyCursor = true)
                } else {
                    val anchor = s.cursorIndex.coerceIn(0, s.entries.size)
                    val idx = candidateIndexes.firstOrNull { it >= anchor }
                        ?: candidateIndexes.first()
                    commitSelection(idx, skipped, moveCursor = false, stickyCursor = true)
                }
            }
        }
    }

    /**
     * 持锁内提交选中结果：刷新自愈状态 + 按模式维护游标。
     *
     * @param moveCursor  SEQUENTIAL：游标 = 选中下标 + 1（下次从后一位扫）
     * @param stickyCursor 粘性模式：游标 = 选中下标（粘住；rotateCursor 从此前进）
     */
    private fun commitSelection(
        index: Int,
        skippedCount: Int,
        moveCursor: Boolean,
        stickyCursor: Boolean = false
    ): KeySelection {
        val s = state
        val entry = s.entries[index]
        val selfHealed = entry.status == KeyStatus.COOLDOWN
        val newEntries = if (selfHealed) {
            s.entries.mapIndexed { i, e ->
                if (i == index) e.copy(status = KeyStatus.AVAILABLE) else e
            }
        } else {
            s.entries
        }
        val newCursor = when {
            moveCursor -> (index + 1).coerceIn(0, newEntries.size)
            stickyCursor -> index
            else -> s.cursorIndex
        }
        state = s.copy(entries = newEntries, cursorIndex = newCursor)

        val reason = buildString {
            append("selected '").append(entry.label.ifEmpty { entry.id })
            append("' at index ").append(index)
            if (selfHealed) append(" (cooldown expired, status self-healed to AVAILABLE)")
        }
        return KeySelection(
            entry = state.entries[index],
            outcome = KeySelectionOutcome.SELECTED,
            skippedCount = skippedCount,
            reason = reason
        )
    }

    // ═══════════════════════════ 健康上报 ═══════════════════════════

    /**
     * 成功上报：usageCount++、lastUsedAt=now、连续失败清零、状态翻 AVAILABLE、
     * 清冷却。UNAVAILABLE 例外——鉴权死 key 不因一次成功复活（防偶发成功
     * 掩盖真死 key；复活走 markTested / resetKey）。
     */
    suspend fun reportSuccess(keyId: String): Unit = mutex.withLock {
        val now = clock()
        updateEntryLocked(keyId) { e ->
            e.copy(
                usageCount = e.usageCount + 1,
                lastUsedAt = now,
                consecutiveFailures = 0,
                status = if (e.status == KeyStatus.UNAVAILABLE) KeyStatus.UNAVAILABLE
                else KeyStatus.AVAILABLE,
                cooldownUntilMs = 0
            )
        }
    }

    /**
     * 失败上报。分类见类 KDoc——401/403 判死；429/5xx/网络（code == 0 哨兵）
     * 冷却指数退避；其余（含 -1 无码错误）只记统计。message 截 200 字符
     * （防巨响应体撑爆持久化）。
     */
    suspend fun reportFailure(keyId: String, httpCode: Int, message: String = ""): Unit =
        mutex.withLock {
            val now = clock()
            updateEntryLocked(keyId) { e ->
                val consecutive = e.consecutiveFailures + 1
                val base = e.copy(
                    totalFailures = e.totalFailures + 1,
                    consecutiveFailures = consecutive,
                    lastErrorCode = httpCode,
                    lastErrorMessage = message.take(MAX_ERROR_MESSAGE_LENGTH)
                )
                when {
                    httpCode == 401 || httpCode == 403 -> base.copy(
                        status = KeyStatus.UNAVAILABLE,
                        cooldownUntilMs = 0
                    )

                    httpCode == 429 || httpCode in 500..599 || httpCode == 0 -> base.copy(
                        status = KeyStatus.COOLDOWN,
                        cooldownUntilMs = now + cooldownBackoffMs(consecutive)
                    )

                    else -> base // 请求级错误（400/404…）与无码错误（-1）：不动状态
                }
            }
        }

    /**
     * 人工可用性标记（Operit ApiKeyPoolAvailabilityTester 的回写口）：
     * 只翻状态与清冷却，不动任何计数（测试器结果与调用统计是两本账）。
     */
    suspend fun markTested(keyId: String, available: Boolean): Unit = mutex.withLock {
        updateEntryLocked(keyId) { e ->
            if (available) e.copy(status = KeyStatus.AVAILABLE, cooldownUntilMs = 0)
            else e.copy(status = KeyStatus.UNAVAILABLE, cooldownUntilMs = 0)
        }
    }

    /** 完整重置一把 key：状态 / 计数 / 错误 / 冷却全部归零（重新开始）。 */
    suspend fun resetKey(keyId: String): Unit = mutex.withLock {
        updateEntryLocked(keyId) { e ->
            e.copy(
                status = KeyStatus.UNTESTED,
                usageCount = 0,
                totalFailures = 0,
                consecutiveFailures = 0,
                lastUsedAt = 0,
                lastErrorCode = 0,
                lastErrorMessage = "",
                cooldownUntilMs = 0
            )
        }
    }

    // ═══════════════════════════ 池管理 ═══════════════════════════

    /**
     * 新增 key（upsert 语义）：id 已存在则原位替换（幂等），否则追加到队尾
     * ——追加位置影响 DISABLED 的"第一个"与列表序 tiebreak，故只在队尾加。
     */
    suspend fun addKey(entry: ApiKeyEntry): Unit = mutex.withLock {
        val s = state
        val idx = s.entries.indexOfFirst { it.id == entry.id }
        state = if (idx >= 0) {
            s.copy(entries = s.entries.mapIndexed { i, e -> if (i == idx) entry else e })
        } else {
            s.copy(entries = s.entries + entry).normalized()
        }
    }

    /**
     * 删除 key。游标补偿：删的是游标之前的条目 → 游标减一，粘性锚点不漂移
     * （否则删除后粘性模式会跳过一把 key）。
     */
    suspend fun removeKey(keyId: String): Unit = mutex.withLock {
        val s = state
        val idx = s.entries.indexOfFirst { it.id == keyId }
        if (idx < 0) return@withLock
        val newCursor = if (idx < s.cursorIndex) s.cursorIndex - 1 else s.cursorIndex
        state = s.copy(
            entries = s.entries.filterNot { it.id == keyId },
            cursorIndex = newCursor
        ).normalized()
    }

    /** 启用 / 禁用一把 key（用户在设置页手动下线 / 上线）。 */
    suspend fun setEnabled(keyId: String, enabled: Boolean): Unit = mutex.withLock {
        updateEntryLocked(keyId) { it.copy(enabled = enabled) }
    }

    /**
     * 显式前进游标一格（绕回）。[KeyPoolLlmClient] 在粘性模式
     * （ON_ERROR / ON_RATE_LIMIT）reportFailure 后调用它实现"换下一把"；
     * SEQUENTIAL 自带前进、DISABLED 不看游标，调用也无害。
     */
    suspend fun rotateCursor(): Unit = mutex.withLock {
        val s = state
        if (s.entries.isEmpty()) return@withLock
        val size = s.entries.size
        val cur = s.cursorIndex.coerceIn(0, size - 1)
        state = s.copy(cursorIndex = (cur + 1) % size)
    }

    // ═══════════════════════════ 读侧 / 持久化 ═══════════════════════════

    /** 脱敏健康快照（非 suspend，无锁读 volatile 副本 + clock()）。 */
    fun snapshot(): KeyPoolSnapshot {
        val s = state
        val now = clock()
        val keys = s.entries.map { e ->
            KeyHealthInfo(
                id = e.id,
                label = e.label,
                keyMasked = maskKey(e.key),
                enabled = e.enabled,
                status = e.status,
                usageCount = e.usageCount,
                totalFailures = e.totalFailures,
                consecutiveFailures = e.consecutiveFailures,
                lastUsedAt = e.lastUsedAt,
                lastErrorCode = e.lastErrorCode,
                lastErrorMessage = e.lastErrorMessage,
                cooldownRemainingMs = if (e.isCoolingDown(now)) e.cooldownUntilMs - now else 0
            )
        }
        val enabledKeys = keys.filter { it.enabled }
        return KeyPoolSnapshot(
            keys = keys,
            cursorIndex = s.cursorIndex,
            rotationMode = s.rotationMode,
            preferUnusedKeys = s.preferUnusedKeys,
            totalKeys = keys.size,
            enabledKeys = enabledKeys.size,
            untestedKeys = enabledKeys.count { it.status == KeyStatus.UNTESTED },
            availableKeys = enabledKeys.count { it.status == KeyStatus.AVAILABLE },
            unavailableKeys = enabledKeys.count { it.status == KeyStatus.UNAVAILABLE },
            coolingDownKeys = enabledKeys.count { it.status == KeyStatus.COOLDOWN },
            totalUsageCount = keys.sumOf { it.usageCount },
            totalFailures = keys.sumOf { it.totalFailures }
        )
    }

    /** 当前完整状态（持久化层读取口；data class 本身即深拷贝语义的值对象）。 */
    suspend fun currentState(): KeyPoolState = mutex.withLock { state }

    /** 整体替换状态（持久化层恢复 / 测试注入口），入参规整后落位。 */
    suspend fun replaceState(newState: KeyPoolState): Unit = mutex.withLock {
        state = newState.normalized()
    }

    // ═══════════════════════════ 内部工具 ═══════════════════════════

    /** 持锁内的条目原位更新；id 不存在则静默跳过（防御式：不向上抛）。 */
    private inline fun updateEntryLocked(
        keyId: String,
        transform: (ApiKeyEntry) -> ApiKeyEntry
    ) {
        val s = state
        val idx = s.entries.indexOfFirst { it.id == keyId }
        if (idx < 0) return
        state = s.copy(
            entries = s.entries.mapIndexed { i, e -> if (i == idx) transform(e) else e }
        )
    }

    /** 游标规整到 [0, entries.size]（空池 → 0；防御外部注入的越界值）。 */
    private fun KeyPoolState.normalized(): KeyPoolState =
        copy(entries = entries.toList(), cursorIndex = cursorIndex.coerceIn(0, entries.size))

    companion object {
        /** 退避基数：首次冷却 30s。 */
        const val BASE_COOLDOWN_MS: Long = 30_000L

        /** 退避封顶：30min（最长冷却窗口）。 */
        const val MAX_COOLDOWN_MS: Long = 1_800_000L

        /** 持久化的最近错误消息截断长度。 */
        const val MAX_ERROR_MESSAGE_LENGTH: Int = 200

        /**
         * 第 [consecutiveFailures] 次连续失败对应的冷却时长：
         * `min(BASE * 2^(n-1), MAX)` —— 30s / 60s / 120s / 240s … 封顶 30min。
         *
         * 倍增用循环实现（一旦触顶就停）：n 很大时也不发生 Long 位移溢出。
         */
        fun cooldownBackoffMs(consecutiveFailures: Int): Long {
            var ms = BASE_COOLDOWN_MS
            var exp = consecutiveFailures - 1
            while (exp > 0 && ms < MAX_COOLDOWN_MS) {
                ms *= 2
                exp--
            }
            return ms.coerceAtMost(MAX_COOLDOWN_MS)
        }
    }
}
