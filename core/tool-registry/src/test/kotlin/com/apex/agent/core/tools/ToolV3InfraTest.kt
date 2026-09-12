package com.apex.agent.core.tools

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool System v3 基础设施测试：MCP 对齐注解、运行策略（超时/重试/退避/
 * 限流）、重试分类器、令牌桶限流器、熔断器状态机、环境能力态（TTL/
 * fail-open）、复合门控。
 *
 * 全部纯 JVM、真实实例（无 mock 框架），与 v2 的 ToolSystemV2InfraTest
 * 风格一致 —— v2 覆盖注册表/校验/统计，这里覆盖 v3 新增的执行硬化层。
 */
class ToolV3InfraTest {

    // ═════════════════════════════════════════════════════════════════
    // ToolAnnotations：工厂 + 推断 + retrySafe 派生
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `annotation factories produce coherent hint combinations`() {
        val read = ToolAnnotations.readOnly()
        assertTrue(read.readOnlyHint)
        assertFalse(read.destructiveHint)
        assertTrue(read.idempotentHint)
        assertTrue(read.retrySafe)

        val destructive = ToolAnnotations.destructive()
        assertFalse(destructive.readOnlyHint)
        assertTrue(destructive.destructiveHint)
        assertFalse(destructive.idempotentHint)
        assertFalse(destructive.retrySafe)

        val idempotent = ToolAnnotations.idempotentWrite()
        assertFalse(idempotent.readOnlyHint)
        assertTrue(idempotent.idempotentHint)
        assertTrue(idempotent.retrySafe)
    }

    @Test
    fun `infer maps known tool families to expected annotations`() {
        // 读族 → readOnly
        val readFile = ToolAnnotations.infer("read_file", ToolRisk.LOW)
        assertTrue(readFile.readOnlyHint)
        assertTrue(readFile.retrySafe)

        // 开放世界读 → openWorld
        val webFetch = ToolAnnotations.infer("web_fetch", ToolRisk.LOW)
        assertTrue(webFetch.openWorldHint)
        assertTrue(webFetch.readOnlyHint)

        // 破坏性一次性 → destructive
        val uninstall = ToolAnnotations.infer("app_uninstall", ToolRisk.HIGH)
        assertTrue(uninstall.destructiveHint)
        assertFalse(uninstall.retrySafe)

        // 幂等覆盖写 → idempotentWrite
        val writeFile = ToolAnnotations.infer("write_file", ToolRisk.MEDIUM)
        assertTrue(writeFile.idempotentHint)
        assertTrue(writeFile.retrySafe)

        // 未知 id → 风险级回退
        val unknownLow = ToolAnnotations.infer("totally_unknown_tool", ToolRisk.LOW)
        assertTrue(unknownLow.readOnlyHint)
        val unknownHigh = ToolAnnotations.infer("totally_unknown_tool", ToolRisk.HIGH)
        assertTrue(unknownHigh.destructiveHint)
    }

    @Test
    fun `metadata carries annotations and builder overrides them`() {
        val inferred = ToolMetadata.infer("clipboard")
        assertEquals(ToolAnnotations.infer("clipboard", inferred.risk), inferred.annotations)

        val declared = ToolMetadata.meta("my_tool") {
            risk(ToolRisk.MEDIUM)
            annotations(ToolAnnotations.mutating())
        }
        assertFalse(declared.annotations.readOnlyHint)
        assertFalse(declared.annotations.idempotentHint)
        assertFalse(declared.annotations.retrySafe)

        // 显式声明优先于 id 推断（summary 也带注解摘要）。
        assertTrue(declared.summary().contains("mutating"))
    }

    // ═════════════════════════════════════════════════════════════════
    // ToolRunPolicy：退避计算 + 解析器
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `retry delay uses full jitter within an exponentially growing cap`() {
        val policy = ToolRunPolicy(
            timeoutMs = 1_000,
            maxRetries = 3,
            baseRetryDelayMs = 100
        )
        // 确定性随机源：固定种子下退避落在 [0, cap] 且 cap 按次翻倍。
        val random = kotlin.random.Random(42)
        val first = policy.retryDelayMs(0, random)
        val second = policy.retryDelayMs(1, random)
        val third = policy.retryDelayMs(2, random)
        assertTrue(first in 0..100)
        assertTrue(second in 0..200)
        assertTrue(third in 0..400)

        // 基数为零 → 零退避（立即重试）。
        assertEquals(0L, ToolRunPolicy().retryDelayMs(0, random))

        // 负数/过大 attemptIndex 收敛到安全区间，不抛异常。
        assertTrue(policy.retryDelayMs(-5, random) in 0..100)
        assertTrue(policy.retryDelayMs(99, random) >= 0)
    }

    @Test
    fun `resolver prefers overrides then annotations then risk fallback`() {
        val resolver = DefaultToolRunPolicyResolver(
            overrides = mapOf(
                "shell_execute" to ToolRunPolicy(timeoutMs = 120_000)
            )
        )

        // 1. 显式覆盖最高优先。
        assertEquals(120_000L, resolver.resolve(FakeTool("shell_execute")).timeoutMs)

        // 2. ask_user 族：无超时无重试（用户可能几分钟才回答）。
        val ask = resolver.resolve(FakeTool("ask_user"))
        assertEquals(0L, ask.timeoutMs)
        assertEquals(0, ask.maxRetries)

        // 3. 开放世界工具 → network 策略（长超时 + 两轮重试）。
        val web = resolver.resolve(
            FakeTool("web_fetch", ToolAnnotations.openWorldRead())
        )
        assertEquals(ToolRunPolicy.network(), web)
        assertEquals(2, web.maxRetries)

        // 4. retrySafe 工具 → quickRead。
        val read = resolver.resolve(
            FakeTool("read_file", ToolAnnotations.readOnly())
        )
        assertEquals(ToolRunPolicy.quickRead(), read)

        // 5. 普通 mutating 工具 → 无盲重试。
        val mutating = resolver.resolve(
            FakeTool("edit_file", ToolAnnotations.mutating())
        )
        assertEquals(0, mutating.maxRetries)
    }

    // ═════════════════════════════════════════════════════════════════
    // RetryClassifier：终态 vs 可重试
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `retry classifier marks user and payload failures terminal`() {
        val terminal = listOf(
            "Error: permission denied: user said no",
            "Error: sandbox violation: path escapes workspace",
            "Error: invalid json: expecting property name",
            "Error: missing argument: 'path' is required",
            "Error: invalid argument: 'limit' must be an integer",
            "Error: not found: no such tool",
            "Error: cancelled by user"
        )
        terminal.forEach { text ->
            assertEquals("expected Terminal for: $text", RetryClassifier.Verdict.Terminal, RetryClassifier.classify(text))
        }
    }

    @Test
    fun `retry classifier marks transient failures retryable`() {
        val retryable = listOf(
            "Error: timeout: tool call exceeded 30000ms budget",
            "Error: execution failed: I/O error in 'web_fetch'",
            "Error: execution failed: IllegalStateException in 'ui_dump'",
            "Error: some unrecognized failure shape"
        )
        retryable.forEach { text ->
            assertEquals(
                "expected Retryable for: $text",
                RetryClassifier.Verdict.Retryable,
                RetryClassifier.classify(text)
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // ToolRateLimiter：令牌桶语义
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `rate limiter grants up to allowance then denies with retry hint`() {
        val limiter = ToolRateLimiter()
        // 2/min：桶容量 2，耗尽后需等 1 个 token 回填（60000/2 = 30s）。
        val now = 1_000_000L
        assertEquals(ToolRateLimiter.Permission.Granted, limiter.tryAcquire("t", 2, now))
        assertEquals(ToolRateLimiter.Permission.Granted, limiter.tryAcquire("t", 2, now))
        val denied = limiter.tryAcquire("t", 2, now)
        assertTrue(denied is ToolRateLimiter.Permission.Denied)
        if (denied is ToolRateLimiter.Permission.Denied) {
            // 1 token @ (2/60000) tokens/ms = 30_000ms 后可用。
            assertTrue("retryInMs=${denied.retryInMs}", denied.retryInMs in 1..30_001)
        }
    }

    @Test
    fun `rate limiter refills continuously over time`() {
        val limiter = ToolRateLimiter()
        // 120/min = 0.002 tokens/ms：耗尽整桶后需要 500ms 回填 1 个 token。
        repeat(120) {
            assertEquals(ToolRateLimiter.Permission.Granted, limiter.tryAcquire("t", 120, 0L))
        }
        assertTrue(limiter.tryAcquire("t", 120, 0L) is ToolRateLimiter.Permission.Denied)
        assertEquals(ToolRateLimiter.Permission.Granted, limiter.tryAcquire("t", 120, 500L))
    }

    @Test
    fun `rate limiter is per tool and null limit disables it`() {
        val limiter = ToolRateLimiter()
        assertEquals(ToolRateLimiter.Permission.Granted, limiter.tryAcquire("a", 1, 0L))
        assertEquals(ToolRateLimiter.Permission.Granted, limiter.tryAcquire("b", 1, 0L))
        assertTrue(limiter.tryAcquire("a", 1, 0L) is ToolRateLimiter.Permission.Denied)
        // null 限额 = 不限流，恒 Granted。
        assertEquals(ToolRateLimiter.Permission.Granted, limiter.tryAcquire("a", null, 0L))
    }

    @Test
    fun `rate limiter bucket capacity is capped at the per-minute allowance`() {
        val limiter = ToolRateLimiter()
        // 初始桶满（5 tokens）→ 连续 5 次通过；第 6 次拒绝。
        repeat(5) {
            assertEquals(ToolRateLimiter.Permission.Granted, limiter.tryAcquire("t", 5, 0L))
        }
        assertTrue(limiter.tryAcquire("t", 5, 0L) is ToolRateLimiter.Permission.Denied)
        // 大时间跳跃不会让桶超过容量。
        assertEquals(ToolRateLimiter.Permission.Granted, limiter.tryAcquire("t", 5, 10L * 60_000L))
        repeat(4) {
            assertEquals(ToolRateLimiter.Permission.Granted, limiter.tryAcquire("t", 5, 10L * 60_000L))
        }
        assertTrue(limiter.tryAcquire("t", 5, 10L * 60_000L) is ToolRateLimiter.Permission.Denied)
    }

    // ═════════════════════════════════════════════════════════════════
    // ToolCircuitBreaker：CLOSED → OPEN → HALF_OPEN 状态机
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `breaker opens after threshold consecutive failures and denies with guidance`() {
        val breaker = ToolCircuitBreaker(failureThreshold = 3, openCooldownMs = 10_000L)
        var now = 0L

        // 三连败 → OPEN（注入时钟驱动开闸时间戳）。
        repeat(3) {
            assertEquals(ToolCircuitBreaker.BreakerVerdict.Allow(isProbe = false), breaker.check("t", now))
            breaker.recordFailure("t", "Error: execution failed: boom", nowMs = now)
        }
        assertEquals(ToolCircuitBreaker.State.OPEN, breaker.stateFor("t"))

        val denied = breaker.check("t", now)
        assertTrue(denied is ToolCircuitBreaker.BreakerVerdict.Deny)
        if (denied is ToolCircuitBreaker.BreakerVerdict.Deny) {
            assertEquals(10_000L, denied.retryInMs)
            assertEquals("Error: execution failed: boom", denied.lastError)
        }
    }

    @Test
    fun `breaker success resets the consecutive failure count`() {
        val breaker = ToolCircuitBreaker(failureThreshold = 3, openCooldownMs = 10_000L)
        breaker.recordFailure("t", "e1", nowMs = 0L)
        breaker.recordFailure("t", "e2", nowMs = 0L)
        breaker.recordSuccess("t")
        breaker.recordFailure("t", "e3", nowMs = 0L)
        // 只有 1 连败 → 仍 CLOSED。
        assertEquals(ToolCircuitBreaker.State.CLOSED, breaker.stateFor("t"))
    }

    @Test
    fun `breaker transitions to half open after cooldown and closes on probe success`() {
        val breaker = ToolCircuitBreaker(failureThreshold = 1, openCooldownMs = 10_000L)
        var now = 0L
        breaker.recordFailure("t", "boom", nowMs = now)
        assertEquals(ToolCircuitBreaker.State.OPEN, breaker.stateFor("t"))

        // 冷却期内拒绝。
        assertTrue(breaker.check("t", now) is ToolCircuitBreaker.BreakerVerdict.Deny)

        // 冷却结束 → 单次探测放行。
        now += 10_000L
        val probe = breaker.check("t", now)
        assertTrue(probe is ToolCircuitBreaker.BreakerVerdict.Allow)
        assertTrue((probe as ToolCircuitBreaker.BreakerVerdict.Allow).isProbe)

        // 探测成功 → CLOSED，恢复正常放行。
        breaker.recordSuccess("t")
        assertEquals(ToolCircuitBreaker.State.CLOSED, breaker.stateFor("t"))
        assertEquals(ToolCircuitBreaker.BreakerVerdict.Allow(isProbe = false), breaker.check("t", now))
    }

    @Test
    fun `breaker re-opens with widened cooldown when the probe fails`() {
        val breaker = ToolCircuitBreaker(failureThreshold = 1, openCooldownMs = 10_000L, maxCooldownMs = 100_000L)
        var now = 0L
        breaker.recordFailure("t", "boom", nowMs = now)

        // 第一次 OPEN：10s 冷却。
        now += 10_000L
        assertTrue(breaker.check("t", now) is ToolCircuitBreaker.BreakerVerdict.Allow)
        breaker.recordFailure("t", "probe failed", nowMs = now)

        // 探测失败 → 加宽到 20s。
        now += 5_000L
        assertTrue(breaker.check("t", now) is ToolCircuitBreaker.BreakerVerdict.Deny)
        now += 15_000L // 累计 20s。
        assertTrue(breaker.check("t", now) is ToolCircuitBreaker.BreakerVerdict.Allow)
    }

    @Test
    fun `breaker is per tool and report renders open breakers`() {
        val breaker = ToolCircuitBreaker(failureThreshold = 1, openCooldownMs = 10_000L)
        breaker.recordFailure("left", "boom", nowMs = 0L)
        assertEquals(ToolCircuitBreaker.State.CLOSED, breaker.stateFor("right"))

        val report = breaker.report()
        assertTrue(report.contains("left: OPEN"))
        assertTrue(report.contains("boom"))
        assertTrue(report.contains("all tool breakers closed") || !report.contains("right"))

        breaker.reset("left")
        assertEquals(ToolCircuitBreaker.State.CLOSED, breaker.stateFor("left"))
    }

    // ═════════════════════════════════════════════════════════════════
    // ToolEnvironmentState：三态语义 + TTL + fail-open
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `environment state is tri-state with explicit unknown`() {
        val state = ToolEnvironmentState()
        assertNull(state.get(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE))
        assertFalse(state.isSet(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE))
        assertFalse(state.isExplicitlyUnset(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE))

        state.set(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE, true)
        assertTrue(state.isSet(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE))

        state.set(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE, false)
        assertTrue(state.isExplicitlyUnset(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE))

        state.set(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE, null)
        assertNull(state.get(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE))
    }

    @Test
    fun `environment state ttl expires to unknown never to false`() {
        val state = ToolEnvironmentState()
        state.setWithTtl(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE, true, ttlMs = 40)
        assertTrue(state.isSet(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE))

        Thread.sleep(80)
        // 过期 → unknown（fail-open），绝不过期为 false。
        assertNull(state.get(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE))
        assertFalse(state.isExplicitlyUnset(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE))
    }

    @Test
    fun `environment summary renders known flags in canonical order`() {
        val state = ToolEnvironmentState()
        assertEquals("environment: (no capability telemetry yet)", state.summary())

        state.set(ToolEnvironmentState.Flags.UBUNTU_READY, true)
        state.set(ToolEnvironmentState.Flags.ACCESSIBILITY_READY, true)
        state.set(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE, false)
        val summary = state.summary()
        // 无障碍在键盘前（KNOWN_ORDER 固定次序）。
        assertTrue(
            "summary order wrong: $summary",
            summary.indexOf("accessibility_ready=on") < summary.indexOf("keyboard_active=off")
        )
        assertTrue(summary.contains("ubuntu_ready=on"))
        // 快照/摘要一致。
        assertEquals(
            mapOf(
                ToolEnvironmentState.Flags.ACCESSIBILITY_READY to true,
                ToolEnvironmentState.Flags.KEYBOARD_ACTIVE to false,
                ToolEnvironmentState.Flags.UBUNTU_READY to true
            ),
            state.snapshot()
        )
    }

    // ═════════════════════════════════════════════════════════════════
    // ToolEnvironmentGate + CompositeToolGate
    // ═════════════════════════════════════════════════════════════════

    @Test
    fun `environment gate denies only declared preconditions that are explicitly false`() = runTest {
        val state = ToolEnvironmentState()
        val gate = ToolEnvironmentGate(state)
        val typing = FakeEnvTool("input_text", listOf(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE))

        // 未知态（fail-open）：放行。
        assertEquals(GateDecision.Allow, gate.check(typing, "{}"))

        // 显式 false：拒绝 + 修复指引。
        state.set(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE, false)
        val denied = gate.check(typing, "{}")
        assertTrue(denied is GateDecision.Deny)
        if (denied is GateDecision.Deny) {
            assertTrue(denied.reason.contains("keyboard_active"))
            assertTrue(denied.reason.contains("ui_tap"))
            assertTrue(denied.reason.contains("Do not retry"))
        }

        // 显式 true：放行。
        state.set(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE, true)
        assertEquals(GateDecision.Allow, gate.check(typing, "{}"))

        // 未声明前置的工具永不拒绝。
        assertEquals(GateDecision.Allow, gate.check(FakeEnvTool("read_file", emptyList()), "{}"))
    }

    @Test
    fun `environment gate fix hints map known flags to actionable guidance`() = runTest {
        val state = ToolEnvironmentState()
        val gate = ToolEnvironmentGate(state)

        val cases = mapOf(
            ToolEnvironmentState.Flags.KEYBOARD_ACTIVE to "ui_tap",
            ToolEnvironmentState.Flags.UBUNTU_READY to "terminal.ubuntu.ensure",
            ToolEnvironmentState.Flags.TERMINAL_SESSION_OPEN to "terminal.create",
            ToolEnvironmentState.Flags.BROWSER_ATTACHED to "browser_navigate",
            ToolEnvironmentState.Flags.NETWORK_AVAILABLE to "device_info"
        )
        cases.forEach { (flag, expectedHint) ->
            state.set(flag, false)
            val tool = FakeEnvTool("probe_$flag", listOf(flag))
            val denied = gate.check(tool, "{}")
            assertTrue("expected deny for $flag", denied is GateDecision.Deny)
            if (denied is GateDecision.Deny) {
                assertTrue("hint '$expectedHint' missing: ${denied.reason}", denied.reason.contains(expectedHint))
            }
            state.set(flag, null)
        }
    }

    @Test
    fun `composite gate short-circuits on the first denial`() = runTest {
        val alwaysDeny = StaticGate(GateDecision.Deny("first"))
        val sawSecond = java.util.concurrent.atomic.AtomicBoolean(false)
        val second = object : ToolExecutionGate {
            override suspend fun check(tool: AgentTool, arguments: String): GateDecision {
                sawSecond.set(true)
                return GateDecision.Allow
            }
        }
        val composite = CompositeToolGate(alwaysDeny, second)
        val decision = composite.check(FakeEnvTool("t", emptyList()), "{}")
        assertTrue(decision is GateDecision.Deny)
        assertFalse("second gate must not run after a denial", sawSecond.get())
    }

    @Test
    fun `composite gate allows only when every gate allows`() = runTest {
        val composite = CompositeToolGate(
            StaticGate(GateDecision.Allow),
            StaticGate(GateDecision.Allow)
        )
        assertEquals(GateDecision.Allow, composite.check(FakeEnvTool("t", emptyList()), "{}"))
    }

    // ═════════════════════════════════════════════════════════════════
    // fixtures
    // ═════════════════════════════════════════════════════════════════

    /** 最小 AgentTool：id + 可选注解声明，execute 恒成功。 */
    private class FakeTool(
        override val id: String,
        private val annotations: ToolAnnotations? = null
    ) : AgentTool {
        override val name: String = id
        override val description: String = "fake"
        override val parametersSchema: String = "{}"
        override val metadata: ToolMetadata =
            if (annotations == null) ToolMetadata.infer(id)
            else ToolMetadata(id, ToolCategory.UTILITY, ToolRisk.LOW, emptyList(), annotations)

        override suspend fun execute(arguments: String): String = "OK"
    }

    /** 实现 EnvironmentAwareTool 的探针工具。 */
    private class FakeEnvTool(
        override val id: String,
        override val requiredEnv: List<String>
    ) : AgentTool, EnvironmentAwareTool {
        override val name: String = id
        override val description: String = "fake env tool"
        override val parametersSchema: String = "{}"

        override suspend fun execute(arguments: String): String = "OK"
    }

    /** 固定决策门（测试复合链）。 */
    private class StaticGate(private val decision: GateDecision) : ToolExecutionGate {
        override suspend fun check(tool: AgentTool, arguments: String): GateDecision = decision
    }
}
