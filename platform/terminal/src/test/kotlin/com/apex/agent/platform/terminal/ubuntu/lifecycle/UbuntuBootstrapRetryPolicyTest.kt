package com.apex.agent.platform.terminal.ubuntu.lifecycle

import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T93: [UbuntuBootstrapRetryPolicy] / [UbuntuRetryThrottle] 决策矩阵测试（假钟驱动）。
 *
 * 覆盖「什么时候该自动补一次引导」的全部分支：降级注记、离线、DISK_FULL、
 * FAILED 现场、各 phase 的待补判定、冷却窗口（轮询受限 / 网络恢复不受限）、
 * 在途去重，以及闸门的抢占-记账-释放闭环。
 */
class UbuntuBootstrapRetryPolicyTest {

    private val cooldown = 900_000L // 15min（DEFAULT_COOLDOWN_MS）

    private fun decide(
        phase: Phase = Phase.READY,
        note: String? = "offline（failedStage=APT_UPDATE）",
        online: Boolean = true,
        lastAttemptAtMs: Long? = null,
        nowMs: Long = 10_000L,
        retryInFlight: Boolean = false,
        respectCooldown: Boolean = true
    ) = UbuntuBootstrapRetryPolicy.decide(
        phase = phase,
        bootstrapNote = note,
        online = online,
        lastAttemptAtMs = lastAttemptAtMs,
        nowMs = nowMs,
        cooldownMs = cooldown,
        retryInFlight = retryInFlight,
        respectCooldown = respectCooldown
    )

    // ── 基本放行/拦截 ──

    @Test fun `degraded READY with no prior attempt retries`() {
        val d = decide()
        assertTrue("expected retry, got ${d.reason}", d.shouldRetry)
    }

    @Test fun `offline never retries - waits for network regain`() {
        val d = decide(online = false)
        assertFalse(d.shouldRetry)
        assertTrue(d.reason.contains("离线"))
    }

    @Test fun `DISK_FULL note blocks retry - freeing space is the only fix`() {
        val d = decide(note = "PackageError:DISK_FULL — APT_UPDATE 前磁盘不足（需 100MB，可用 12MB）")
        assertFalse(d.shouldRetry)
        assertTrue(d.reason.contains("DISK_FULL"))
    }

    @Test fun `FAILED is never auto-retried - keep the failure scene`() {
        val d = decide(phase = Phase.FAILED, note = null)
        assertFalse(d.shouldRetry)
        assertTrue(d.reason.contains("FAILED"))
    }

    @Test fun `READY without note is already complete - no retry`() {
        val d = decide(note = null)
        assertFalse(d.shouldRetry)
        assertTrue(d.reason.contains("完整 READY"))
    }

    @Test fun `retryInFlight blocks duplicate retry`() {
        val d = decide(retryInFlight = true)
        assertFalse(d.shouldRetry)
        assertTrue(d.reason.contains("在途"))
    }

    // ── phase 待补矩阵 ──

    @Test fun `ROOTFS_READY and BOOTSTRAPPING count as pending bootstrap`() {
        assertTrue(decide(phase = Phase.ROOTFS_READY, note = null).shouldRetry)
        assertTrue(decide(phase = Phase.BOOTSTRAPPING, note = null).shouldRetry)
    }

    @Test fun `INSTALLING NOT_INSTALLED RECOVERING are owned by other flows`() {
        listOf(Phase.INSTALLING, Phase.NOT_INSTALLED, Phase.RECOVERING).forEach { p ->
            val d = decide(phase = p, note = null)
            assertFalse("phase=$p must not auto-retry: ${d.reason}", d.shouldRetry)
        }
    }

    // ── 冷却窗口 ──

    @Test fun `poll retry is blocked inside cooldown window`() {
        val last = 10_000L
        val d = decide(lastAttemptAtMs = last, nowMs = last + cooldown - 1)
        assertFalse(d.shouldRetry)
        assertTrue(d.reason.contains("冷却中"))
    }

    @Test fun `poll retry allowed once cooldown elapsed`() {
        val last = 10_000L
        val d = decide(lastAttemptAtMs = last, nowMs = last + cooldown)
        assertTrue("expected retry, got ${d.reason}", d.shouldRetry)
    }

    @Test fun `network regain path ignores cooldown - conditions just changed`() {
        val last = 10_000L
        val d = decide(
            lastAttemptAtMs = last,
            nowMs = last + 1_000,
            respectCooldown = false
        )
        assertTrue("network regain must retry immediately: ${d.reason}", d.shouldRetry)
    }

    // ── 闸门（抢占/记账/释放）──

    @Test fun `throttle stamps cooldown on begin and dedupes concurrent begins`() {
        var now = 5_000L
        val throttle = UbuntuRetryThrottle(cooldownMs = cooldown, clock = { now })
        assertEquals(null, throttle.lastAttemptAt())
        assertTrue("first begin must win", throttle.begin())
        assertEquals(5_000L, throttle.lastAttemptAt())
        assertFalse("second begin must lose while in flight", throttle.begin())
        // 在途 + 冷却双拦截
        val during = throttle.decide(Phase.READY, "note", online = true)
        assertFalse(during.shouldRetry)
        assertTrue(during.reason.contains("在途"))
        throttle.end()
        // 释放后仍处冷却窗口内 → 轮询被冷却拦下
        val afterRelease = throttle.decide(Phase.READY, "note", online = true)
        assertFalse(afterRelease.shouldRetry)
        assertTrue(afterRelease.reason.contains("冷却中"))
        // 假钟推进过冷却 → 放行
        now += cooldown
        val later = throttle.decide(Phase.READY, "note", online = true)
        assertTrue("expected retry after cooldown: ${later.reason}", later.shouldRetry)
    }

    @Test fun `throttle network regain ignores its own cooldown stamp`() {
        var now = 5_000L
        val throttle = UbuntuRetryThrottle(cooldownMs = cooldown, clock = { now })
        assertTrue(throttle.begin())
        throttle.end()
        now += 1_000
        val d = throttle.decide(Phase.READY, "note", online = true, respectCooldown = false)
        assertTrue("network regain must not wait for cooldown: ${d.reason}", d.shouldRetry)
    }

    @Test fun `default cooldown is 15 minutes and poll interval 1 minute`() {
        assertEquals(15 * 60 * 1000L, UbuntuBootstrapRetryPolicy.DEFAULT_COOLDOWN_MS)
        assertEquals(60 * 1000L, UbuntuBootstrapRetryPolicy.POLL_INTERVAL_MS)
    }
}
