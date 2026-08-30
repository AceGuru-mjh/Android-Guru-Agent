package com.apex.agent.platform.terminal.environment

import com.apex.agent.platform.terminal.workspace.GuestUserHome
import org.junit.Assert.*
import org.junit.Test

/**
 * T76: LinuxEnvironmentManager 单元测试 —— 三层 env 模型 + apt env 变体。
 */
class LinuxEnvironmentManagerTest {

    private val mgr = LinuxEnvironmentManager()

    @Test fun `interactive guest env has required keys`() {
        val env = mgr.interactiveGuestEnv()
        for (key in LinuxEnvironmentManager.REQUIRED_GUEST_KEYS) {
            assertNotNull("$key must be present in interactive env", env[key])
        }
    }

    @Test fun `interactive env does NOT contain DEBIAN_FRONTEND`() {
        // T76 §6: 交互式 shell 绝不继承 DEBIAN_FRONTEND=noninteractive
        val env = mgr.interactiveGuestEnv()
        assertNull("interactive env must NOT set DEBIAN_FRONTEND", env["DEBIAN_FRONTEND"])
    }

    @Test fun `apt env contains DEBIAN_FRONTEND noninteractive`() {
        val env = mgr.aptGuestEnv()
        assertEquals("noninteractive", env["DEBIAN_FRONTEND"])
    }

    @Test fun `apt env contains DEBIAN_PRIORITY critical`() {
        val env = mgr.aptGuestEnv()
        assertEquals("critical", env["DEBIAN_PRIORITY"])
    }

    @Test fun `apt env sets TERM dumb`() {
        val env = mgr.aptGuestEnv()
        assertEquals("dumb", env["TERM"])
    }

    @Test fun `apt env does not leak to interactive`() {
        // 调用 aptGuestEnv 不应污染后续 interactiveGuestEnv
        mgr.aptGuestEnv()
        val interactive = mgr.interactiveGuestEnv()
        assertNull(interactive["DEBIAN_FRONTEND"])
    }

    @Test fun `HOME is persistent root path`() {
        assertEquals(GuestUserHome.GUEST_PATH, mgr.interactiveGuestEnv()["HOME"])
    }

    @Test fun `PATH is guest standard`() {
        assertEquals(LinuxEnvironmentManager.GUEST_PATH, mgr.interactiveGuestEnv()["PATH"])
    }

    @Test fun `requestEnv overrides defaults`() {
        val env = mgr.interactiveGuestEnv(mapOf("TERM" to "vt100", "CUSTOM" to "yes"))
        assertEquals("vt100", env["TERM"])
        assertEquals("yes", env["CUSTOM"])
    }

    @Test fun `LC_ALL is set to C UTF-8`() {
        assertEquals("C.UTF-8", mgr.interactiveGuestEnv()["LC_ALL"])
    }

    @Test fun `PWD and OLDPWD are default cwd`() {
        val env = mgr.interactiveGuestEnv()
        assertEquals("/workspace", env["PWD"])
        assertEquals("/workspace", env["OLDPWD"])
    }

    @Test fun `validateGuestEnv passes for interactive env`() {
        val validation = mgr.validateGuestEnv(mgr.interactiveGuestEnv())
        assertTrue(validation.valid)
        assertTrue(validation.missingKeys.isEmpty())
    }

    @Test fun `validateGuestEnv fails when required key missing`() {
        val env = mgr.interactiveGuestEnv().toMutableMap()
        env.remove("HOME")
        val validation = mgr.validateGuestEnv(env)
        assertFalse(validation.valid)
        assertTrue(validation.missingKeys.contains("HOME"))
    }

    @Test fun `isAptEnv detects noninteractive`() {
        assertTrue(mgr.isAptEnv(mgr.aptGuestEnv()))
        assertFalse(mgr.isAptEnv(mgr.interactiveGuestEnv()))
    }

    @Test fun `bootstrap env has PWD set to root home`() {
        val env = mgr.bootstrapGuestEnv()
        assertEquals(GuestUserHome.GUEST_PATH, env["PWD"])
        assertEquals("noninteractive", env["DEBIAN_FRONTEND"])
    }
}
