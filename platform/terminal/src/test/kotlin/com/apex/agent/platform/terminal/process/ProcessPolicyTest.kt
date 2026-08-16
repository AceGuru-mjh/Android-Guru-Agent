package com.apex.agent.platform.terminal.process

import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.policy.CommandParser
import com.apex.agent.platform.terminal.policy.CommandPolicy
import com.apex.agent.platform.terminal.policy.CommandPolicyDecision
import com.apex.agent.platform.terminal.policy.CommandPolicyMode
import org.junit.Assert.*
import org.junit.Test

/**
 * Command Policy + Process tests (Spec PR #51 §9/§10/§12).
 */
class CommandParserTest {

    @Test fun `parses simple command`() {
        val p = CommandParser.parse("git status --short")
        assertEquals("git", p.executable)
        assertEquals(listOf("status", "--short"), p.arguments)
        assertFalse(p.isComplex)
    }

    @Test fun `parses path-form executable to basename`() {
        assertEquals("rm", CommandParser.parse("/bin/rm -rf /").executable)
        assertEquals("rm", CommandParser.parse("./rm").executable)
        assertEquals("rm", CommandParser.parse("../bin/rm").executable)
        assertEquals("rm", CommandParser.parse("/usr/bin/rm").executable)
    }

    @Test fun `detects shell operator as complex`() {
        assertTrue(CommandParser.parse("echo hi && rm -rf /").isComplex)
        assertTrue(CommandParser.parse("echo hi ; rm -rf /").isComplex)
        assertTrue(CommandParser.parse("echo hi | rm").isComplex)
        assertTrue(CommandParser.parse("echo hi || rm").isComplex)
    }

    @Test fun `detects shell wrapper as complex`() {
        val p = CommandParser.parse("sh -c rm")
        assertTrue(p.isComplex)
        assertEquals("sh", p.executable)
    }

    @Test fun `empty command is complex`() {
        val p = CommandParser.parse("")
        assertTrue(p.isComplex)
        assertNull(p.executable)
    }

    @Test fun `does not match prefix - git vs git-status-helper`() {
        // "git-status-helper" should parse to executable "git-status-helper", NOT "git"
        val p = CommandParser.parse("git-status-helper --foo")
        assertEquals("git-status-helper", p.executable)
        assertNotEquals("git", p.executable)
    }
}

class CommandPolicyTest {

    private val policy = CommandPolicy(
        mode = CommandPolicyMode.ALLOW_ALL,
        allowlist = setOf("git", "ls", "pwd", "python", "gradle", "adb"),
        denylist = setOf("shutdown", "reboot", "mkfs", "dd", "rm")
    )

    @Test fun `allows whitelisted command in ALLOW_ALL`() {
        assertEquals(CommandPolicyDecision.ALLOW, policy.check("git status"))
    }

    @Test fun `denies blacklisted command`() {
        assertEquals(CommandPolicyDecision.DENY, policy.check("shutdown"))
        assertEquals(CommandPolicyDecision.DENY, policy.check("reboot"))
    }

    @Test fun `denylist takes precedence over allowlist`() {
        // rm is in denylist; even if somehow in allowlist it should DENY
        val p = CommandPolicy(allowlist = setOf("rm"), denylist = setOf("rm"))
        assertEquals(CommandPolicyDecision.DENY, p.check("rm -rf /"))
    }

    @Test fun `path-form denylist is caught via basename`() {
        assertEquals(CommandPolicyDecision.DENY, policy.check("/bin/rm -rf /"))
        assertEquals(CommandPolicyDecision.DENY, policy.check("./rm"))
        assertEquals(CommandPolicyDecision.DENY, policy.check("/usr/bin/rm"))
    }

    // ── Bypass attempts (Spec §12) ──

    @Test fun `shell wrapper bypass denied`() {
        assertEquals(CommandPolicyDecision.DENY, policy.check("sh -c \"rm -rf /\""))
        assertEquals(CommandPolicyDecision.DENY, policy.check("bash -c \"rm\""))
    }

    @Test fun `chaining bypass denied`() {
        assertEquals(CommandPolicyDecision.DENY, policy.check("echo hi && rm -rf /"))
        assertEquals(CommandPolicyDecision.DENY, policy.check("echo hi ; rm"))
        assertEquals(CommandPolicyDecision.DENY, policy.check("echo hi | rm"))
    }

    @Test fun `env command bypass denied`() {
        assertEquals(CommandPolicyDecision.DENY, policy.check("env rm -rf /"))
    }

    @Test fun `command exec bypass denied`() {
        assertEquals(CommandPolicyDecision.DENY, policy.check("command rm"))
        assertEquals(CommandPolicyDecision.DENY, policy.check("exec rm"))
    }

    @Test fun `ALLOWLIST_ONLY mode denies non-allowlisted`() {
        val strict = CommandPolicy(mode = CommandPolicyMode.ALLOWLIST_ONLY, allowlist = setOf("git"), denylist = setOf())
        assertEquals(CommandPolicyDecision.ALLOW, strict.check("git status"))
        assertEquals(CommandPolicyDecision.DENY, strict.check("ls"))  // not in allowlist
    }

    @Test fun `complex unparseable command denied conservatively`() {
        // Any command with shell operators → DENY (Spec §6)
        assertEquals(CommandPolicyDecision.DENY, policy.check("ls | grep foo"))
        assertEquals(CommandPolicyDecision.DENY, policy.check("a && b"))
    }
}

class ProcessExitStatusTest {

    @Test fun `normal exit is success when 0`() {
        val s = ProcessExitStatus(exitCode = 0, signal = null)
        assertTrue(s.isNormalExit)
        assertTrue(s.isSuccess)
        assertFalse(s.isSignaled)
    }

    @Test fun `non-zero exit is not success`() {
        val s = ProcessExitStatus(exitCode = 1, signal = null)
        assertTrue(s.isNormalExit)
        assertFalse(s.isSuccess)
    }

    @Test fun `signal kill is signaled`() {
        val s = ProcessExitStatus(exitCode = null, signal = UnixSignal.SIGKILL)
        assertTrue(s.isSignaled)
        assertFalse(s.isNormalExit)
        assertFalse(s.isSuccess)
    }

    @Test fun `SIGTERM is signaled`() {
        val s = ProcessExitStatus(exitCode = 143, signal = UnixSignal.SIGTERM)
        assertTrue(s.isSignaled)
    }
}

class UnixSignalTest {

    @Test fun `SIGSTOP and SIGCONT exist`() {
        assertNotNull(UnixSignal.valueOf("SIGSTOP"))
        assertNotNull(UnixSignal.valueOf("SIGCONT"))
        assertEquals(19, UnixSignal.SIGSTOP.number)
        assertEquals(18, UnixSignal.SIGCONT.number)
    }

    @Test fun `all signals have valid numbers`() {
        for (s in UnixSignal.values()) {
            assertTrue("${s.name} should have positive number", s.number > 0)
        }
    }
}
