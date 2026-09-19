package com.apex.agent.platform.terminal.policy

import com.apex.agent.platform.terminal.io.InputOwner
import org.junit.Assert.*
import org.junit.Test

/**
 * T85 策略硬化回归测试（审计 S-1/S-2/S-3/S-4 + 环境中心误杀修复）。
 *
 * 攻击面与语义对照：
 *  - S-1 换行注入：`echo hi\nrm -rf /` 旧 parse 把 \n 当空白 → 头 echo → ALLOW；
 *  - S-2 引号/反斜杠绕过：`"rm" -rf /` 旧首 token 带引号 ≠ rm → ALLOW；
 *  - 分段检查（交互/USER/SYSTEM）：黑名单逐段拦截 + 链式 apt 命令不再误杀；
 *  - 环境中心回归：`apt-get update && apt-get install -y git` 旧 LINE 路径
 *    complex→DENY —— 依赖安装全量失败（预存缺陷）。
 */
class T85PolicyHardeningTest {

    private val policy = CommandPolicy(
        allowlist = setOf("git", "ls", "apt-get"),
        denylist = setOf("rm", "shutdown", "dd")
    )

    // ═══ S-1：换行注入 ═══

    @Test
    fun `newline injection is complex and denied on agent path`() {
        // LINE 路径（Agent 命令执行）：嵌入换行 → complex → DENY
        val parsed = CommandParser.parse("echo hi\nrm -rf /sdcard")
        assertTrue("嵌入换行必须判 complex", parsed.isComplex)
        assertNull(parsed.executable)
        assertEquals(CommandPolicyDecision.DENY, policy.check("echo hi\nrm -rf /sdcard"))
    }

    @Test
    fun `trailing newline from sendLine is not treated as injection`() {
        // sendLine 追加的单个尾随 \n 不算嵌入（否则所有 LINE 全拒）
        val parsed = CommandParser.parse("git status\n")
        assertEquals("git", parsed.executable)
        assertFalse(parsed.isComplex)
        assertEquals(CommandPolicyDecision.ALLOW, policy.check("git status\n"))
    }

    // ═══ S-2：引号 / 反斜杠绕过 ═══

    @Test
    fun `quoted executable is complex on conservative path`() {
        assertTrue(CommandParser.parse("\"rm\" -rf /").isComplex)
        assertTrue(CommandParser.parse("'rm' -rf /").isComplex)
        assertTrue(CommandParser.parse("r\\m -rf /").isComplex)
    }

    @Test
    fun `segments path unquotes executable and matches denylist`() {
        // 交互/分段路径：去引号后精确匹配 —— `"rm"` 不再逃过黑名单
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("\"rm\" -rf /"))
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("'rm' -rf /"))
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("r\\m -rf /"))
    }

    @Test
    fun `quotes later in the command do not make it complex`() {
        // 引号在参数位置（git commit -m "x"）不影响执行名解析
        val parsed = CommandParser.parse("git commit -m \"fix\"")
        assertEquals("git", parsed.executable)
        assertFalse(parsed.isComplex)
    }

    // ═══ 分段检查：拦截面 ═══

    @Test
    fun `chained denied segment still denied in segments mode`() {
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("echo hi && rm -rf /"))
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("echo hi ; rm -rf /"))
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("echo hi | rm -rf /"))
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("ls || rm -rf /"))
    }

    @Test
    fun `newline hidden denied line caught in segments mode`() {
        assertEquals(
            CommandPolicyDecision.DENY,
            policy.checkSegments("echo hi\nrm -rf /sdcard")
        )
    }

    @Test
    fun `shell wrapper deep scan catches wrapped payload`() {
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("bash -c \"rm -rf /\""))
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("env rm -rf /"))
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("sh -c shutdown"))
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("xargs rm"))
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("nohup dd if=/dev/zero of=/dev/sda"))
    }

    @Test
    fun `env assignment prefix is stripped before head matching`() {
        // FOO=1 rm … 旧头匹配取到 FOO=1 → 绕过；剥离赋值后头 = rm → 拒
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("FOO=1 rm -rf /"))
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("A=1 B=2 rm -rf /"))
    }

    @Test
    fun `path form executable matched via basename in segments mode`() {
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("/bin/rm -rf /"))
        assertEquals(CommandPolicyDecision.DENY, policy.checkSegments("./rm -rf /"))
    }

    // ═══ 分段检查：不误杀面（环境中心 / REPL） ═══

    @Test
    fun `chained apt commands allowed for system dependency install`() {
        // 预存缺陷回归：环境中心 `apt-get update && apt-get install -y git`
        // 旧 LINE 保守路径 complex→DENY —— 一键安装全量失败。
        assertEquals(CommandPolicyDecision.ALLOW, policy.checkSegments("apt-get update && apt-get install -y git"))
        assertEquals(CommandPolicyDecision.ALLOW, policy.checkSegments("apt-get update && apt-get install -y openjdk-17-jdk-headless"))
    }

    @Test
    fun `quoted operator inside arguments does not split segments`() {
        // sdkmanager "cmdline-tools;latest" —— 引号内 ; 不是操作符
        assertEquals(CommandPolicyDecision.ALLOW, policy.checkSegments("sdkmanager \"cmdline-tools;latest\""))
        // REPL 场景：print("a|b") 不因管道符误杀
        assertEquals(CommandPolicyDecision.ALLOW, policy.checkSegments("print(\"a|b\")"))
    }

    @Test
    fun `pipe to benign commands allowed in segments mode`() {
        assertEquals(CommandPolicyDecision.ALLOW, policy.checkSegments("ls | grep foo"))
        assertEquals(CommandPolicyDecision.ALLOW, policy.checkSegments("cat x && cat y"))
    }

    // ═══ owner 分级路由（TerminalPolicyImpl） ═══

    private val impl = TerminalPolicyImpl(
        commandPolicy = CommandPolicy(denylist = setOf("rm", "shutdown"))
    )

    @Test
    fun `agent line execution stays conservative`() {
        // Agent LINE：复杂即拒（Spec §6 语义保持，不因分段检查放宽）
        val d = impl.check(InputRequest(1, "ls | grep foo", null, InputOwner.AGENT))
        assertTrue("Agent 执行路径保持保守", d is Decision.Deny)
    }

    @Test
    fun `system chained commands allowed via segments routing`() {
        // SYSTEM（环境中心 runtime.run）→ 分段：链式 apt 放行
        val d = impl.check(InputRequest(1, "apt-get update && apt-get install -y git", null, InputOwner.SYSTEM))
        assertTrue("SYSTEM 链式命令不得误杀", d is Decision.Allow)
    }

    @Test
    fun `system denied segments still blocked`() {
        val d = impl.check(InputRequest(1, "apt-get update && rm -rf /", null, InputOwner.SYSTEM))
        assertTrue(d is Decision.Deny)
    }

    @Test
    fun `agent interactive request uses segments routing`() {
        // interactive=true（Agent RAW/回车累积行）：链式放行、黑名单段拦截
        assertTrue(impl.check(InputRequest(1, "print(\"a|b\")", null, InputOwner.AGENT, interactive = true)) is Decision.Allow)
        assertTrue(impl.check(InputRequest(1, "echo hi && rm -rf /", null, InputOwner.AGENT, interactive = true)) is Decision.Deny)
    }

    @Test
    fun `user paste with operators not blanket-denied by platform`() {
        // USER：平台不再整段误拦多行/带操作符文本（UI 层名单把关）
        assertTrue(impl.check(InputRequest(1, "echo one\necho two | tee log", null, InputOwner.USER)) is Decision.Allow)
        // 但默认危险命令与用户黑名单仍拦
        assertTrue(impl.check(InputRequest(1, "shutdown now", null, InputOwner.USER)) is Decision.Deny)
    }

    @Test
    fun `default denylist applies to segments path`() {
        val p = CommandPolicy()  // 默认 denylist: shutdown/reboot/mkfs/dd/halt/poweroff
        assertEquals(CommandPolicyDecision.DENY, p.checkSegments("echo hi && dd if=/dev/zero of=/dev/sda"))
        assertEquals(CommandPolicyDecision.DENY, p.checkSegments("mkfs.ext4 /dev/sda"))
        assertEquals(CommandPolicyDecision.ALLOW, p.checkSegments("echo hi && ls"))
    }

    @Test
    fun `allowlist mode requires every segment head to be allowlisted`() {
        val strict = CommandPolicy(
            mode = CommandPolicyMode.ALLOWLIST_ONLY,
            allowlist = setOf("git"),
            denylist = setOf()
        )
        assertEquals(CommandPolicyDecision.ALLOW, strict.checkSegments("git status && git log"))
        assertEquals(CommandPolicyDecision.DENY, strict.checkSegments("git status && ls"))
    }
}
