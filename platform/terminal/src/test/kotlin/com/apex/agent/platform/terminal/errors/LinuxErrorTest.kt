package com.apex.agent.platform.terminal.errors

import org.junit.Assert.*
import org.junit.Test

/**
 * T76: LinuxEnvironmentError / LinuxErrorCode 单元测试 —— 结构化错误模型。
 */
class LinuxErrorTest {

    @Test fun `all T76 spec error codes exist`() {
        // T76 §22 的具名错误码必须全部存在
        val codes = LinuxErrorCode.values().map { it.name }.toSet()
        assertTrue(codes.contains("ROOTFS_NOT_READY"))
        assertTrue(codes.contains("PROOT_UNAVAILABLE"))
        assertTrue(codes.contains("NETWORK_DNS_FAILED"))
        assertTrue(codes.contains("NETWORK_TLS_FAILED"))
        assertTrue(codes.contains("APT_UNAVAILABLE"))
        assertTrue(codes.contains("APT_LOCKED"))
        assertTrue(codes.contains("APT_FAILED"))
        assertTrue(codes.contains("PACKAGE_NOT_FOUND"))
        assertTrue(codes.contains("PACKAGE_INSTALL_FAILED"))
        assertTrue(codes.contains("BOOTSTRAP_FAILED"))
        assertTrue(codes.contains("WORKSPACE_UNAVAILABLE"))
        assertTrue(codes.contains("ENVIRONMENT_INVALID"))
    }

    @Test fun `APT_LOCKED is retryable`() {
        // T76 §23: Agent 收到 APT_LOCKED 应知道 retry later
        assertTrue(LinuxErrorCode.APT_LOCKED.recoverable)
        assertTrue(LinuxErrorCode.APT_LOCKED.shouldRetry)
    }

    @Test fun `NETWORK_DNS_FAILED is repairable`() {
        // T76 §23: DNS 失败 → environment repair required
        assertTrue(LinuxErrorCode.NETWORK_DNS_FAILED.repairable)
    }

    @Test fun `PACKAGE_NOT_FOUND is not retryable`() {
        // Agent 应改换请求，不是重试
        assertFalse(LinuxErrorCode.PACKAGE_NOT_FOUND.recoverable)
        assertFalse(LinuxErrorCode.PACKAGE_NOT_FOUND.repairable)
    }

    @Test fun `ROOTFS_NOT_READY is repairable`() {
        assertTrue(LinuxErrorCode.ROOTFS_NOT_READY.repairable)
    }

    @Test fun `PROOT_UNAVAILABLE is not repairable`() {
        // 需要 reinstall APK
        assertFalse(LinuxErrorCode.PROOT_UNAVAILABLE.repairable)
    }

    @Test fun `toMap includes all fields`() {
        val err = LinuxEnvironmentError.aptLocked("lock held by pid 1234")
        val map = err.toMap()
        assertEquals("APT_LOCKED", map["code"])
        assertEquals("lock held by pid 1234", map["message"])
        assertEquals(true, map["recoverable"])
        assertEquals("apt", map["stage"])
    }

    @Test fun `factory methods produce correct codes`() {
        assertEquals(LinuxErrorCode.ROOTFS_NOT_READY, LinuxEnvironmentError.rootfsNotReady("x").code)
        assertEquals(LinuxErrorCode.PROOT_UNAVAILABLE, LinuxEnvironmentError.prootUnavailable("x").code)
        assertEquals(LinuxErrorCode.APT_LOCKED, LinuxEnvironmentError.aptLocked("x").code)
        assertEquals(LinuxErrorCode.PACKAGE_NOT_FOUND, LinuxEnvironmentError.packageNotFound("git").code)
        assertEquals(LinuxErrorCode.BOOTSTRAP_FAILED, LinuxEnvironmentError.bootstrapFailed("APT_UPDATE", "err").code)
        assertEquals(LinuxErrorCode.NETWORK_DNS_FAILED, LinuxEnvironmentError.networkDnsFailed("x").code)
        assertEquals(LinuxErrorCode.NETWORK_TLS_FAILED, LinuxEnvironmentError.networkTlsFailed("x").code)
        assertEquals(LinuxErrorCode.UNKNOWN, LinuxEnvironmentError.unknown("x").code)
    }

    @Test fun `each code has non-blank stage`() {
        for (code in LinuxErrorCode.values()) {
            assertTrue("${code.name} stage must not be blank", code.stage.isNotBlank())
        }
    }
}
