package com.apex.agent.platform.terminal.process

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.policy.CommandParser
import com.apex.agent.platform.terminal.policy.CommandPolicy
import com.apex.agent.platform.terminal.policy.CommandPolicyDecision
import com.apex.agent.platform.terminal.policy.CommandPolicyMode
import com.apex.agent.platform.terminal.policy.Decision
import com.apex.agent.platform.terminal.policy.DefaultCommandPolicy
import com.apex.agent.platform.terminal.policy.InputRequest
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
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

    @Test fun `v1 check never emits REQUIRE_CONFIRMATION`() {
        // v1 contract: CommandPolicy.check() only produces ALLOW/DENY. REQUIRE_CONFIRMATION is
        // reserved for a future Confirmation UI and must never be auto-allowed downstream.
        val candidates = listOf(
            "ls", "git status", "pwd", "/bin/rm -rf /", "sh -c ls", "bash -c echo",
            "echo a && echo b", "reboot", "shutdown", "", "env rm", "rm;ls"
        )
        for (c in candidates) {
            val d = CommandPolicy().check(c)
            assertNotEquals(
                "v1 check must not emit REQUIRE_CONFIRMATION for '$c'",
                CommandPolicyDecision.REQUIRE_CONFIRMATION, d
            )
        }
    }
}

class CommandPolicyDefaultConfigTest {

    @Test fun `default policy denies destructive commands`() {
        // CommandPolicy() with no configuration inherits DefaultCommandPolicy.DEFAULT_DENYLIST.
        val p = CommandPolicy()
        for (cmd in listOf("shutdown", "reboot", "mkfs", "dd", "halt", "poweroff")) {
            assertEquals("default policy must deny $cmd", CommandPolicyDecision.DENY, p.check(cmd))
        }
    }

    @Test fun `default policy allows benign command`() {
        assertEquals(CommandPolicyDecision.ALLOW, CommandPolicy().check("ls"))
    }

    @Test fun `explicitly empty denylist clears defaults`() {
        // configured policy may opt OUT of the default denylist.
        val p = CommandPolicy(denylist = setOf())
        assertEquals(
            "explicit empty denylist must clear defaults",
            CommandPolicyDecision.ALLOW, p.check("reboot")
        )
    }

    @Test fun `explicit denylist replaces defaults`() {
        // configured policy may REPLACE the default denylist with its own.
        val p = CommandPolicy(denylist = setOf("rm"))
        assertEquals(CommandPolicyDecision.ALLOW, p.check("reboot"))  // default no longer applies
        assertEquals(CommandPolicyDecision.DENY, p.check("rm"))
    }

    @Test fun `effectiveDenylist resolves default vs configured boundary`() {
        assertEquals(DefaultCommandPolicy.DEFAULT_DENYLIST, CommandPolicy().effectiveDenylist)
        assertEquals(setOf<String>(), CommandPolicy(denylist = setOf()).effectiveDenylist)
        assertEquals(setOf("rm"), CommandPolicy(denylist = setOf("rm")).effectiveDenylist)
    }

    @Test fun `denylist still overrides allowlist under default policy`() {
        // "reboot" is in DEFAULT_DENYLIST; even if allowlisted it must DENY (priority DENY > ALLOWLIST).
        val p = CommandPolicy(mode = CommandPolicyMode.ALLOWLIST_ONLY, allowlist = setOf("reboot"))
        assertEquals(CommandPolicyDecision.DENY, p.check("reboot"))
    }
}

class TerminalPolicyDecisionTest {

    @Test fun `ALLOW maps to Decision Allow`() {
        val d = TerminalPolicyImpl.mapDecision(CommandPolicyDecision.ALLOW, CommandParser.parse("ls"))
        assertTrue(d is Decision.Allow)
    }

    @Test fun `DENY maps to Decision Deny`() {
        val d = TerminalPolicyImpl.mapDecision(CommandPolicyDecision.DENY, CommandParser.parse("rm -rf /"))
        assertTrue(d is Decision.Deny)
    }

    @Test fun `REQUIRE_CONFIRMATION maps to DENY not silent allow`() {
        // Fail-safe: no Confirmation UI exists in v1, so REQUIRE_CONFIRMATION must be DENIED.
        val d = TerminalPolicyImpl.mapDecision(
            CommandPolicyDecision.REQUIRE_CONFIRMATION, CommandParser.parse("reboot")
        )
        assertFalse(
            "REQUIRE_CONFIRMATION must never be silently auto-allowed in v1",
            d is Decision.Allow
        )
        assertTrue("REQUIRE_CONFIRMATION must map to Deny in v1", d is Decision.Deny)
        val deny = d as Decision.Deny
        assertTrue("deny reason must mention confirmation", deny.reason.contains("confirmation", ignoreCase = true))
    }

    @Test fun `check never produces Allow for REQUIRE_CONFIRMATION-capable paths`() {
        // End-to-end through TerminalPolicyImpl: complex/destructive inputs never result in Allow.
        val policy = TerminalPolicyImpl()
        for (cmd in listOf("reboot", "shutdown", "sh -c rm", "echo a && reboot")) {
            val d = policy.check(InputRequest(sessionId = 1L, command = cmd, bytes = null, owner = InputOwner.AGENT))
            assertTrue("'$cmd' must be denied, got $d", d is Decision.Deny)
        }
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
