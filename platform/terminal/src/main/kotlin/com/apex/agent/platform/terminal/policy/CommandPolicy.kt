package com.apex.agent.platform.terminal.policy

/**
 * Parsed shell command (Spec PR #51 §4/§5).
 *
 * Extracts the executable basename so policy can match "rm" regardless of path form
 * (/bin/rm, ./rm, ../bin/rm all → "rm"). Does NOT fully parse shell syntax — for complex
 * commands (pipes, &&, sh -c) the policy is CONSERVATIVE: returns executable=null and
 * the policy layer DENIES by default (Spec §6: "无法可靠解析的复杂 shell command, 默认 DENY").
 *
 * This is NOT a shell parser. It's a security-focused extractor that errs on the side of
 * denial for anything it can't safely classify.
 *
 * T85 硬化（审计 S-1/S-2）：
 *  - 嵌入换行（剥离单个尾随换行后仍含 \n/\r）→ complex。旧实现 `\s+` 分词把
 *    换行当空白，`echo hi\nrm -rf /` 解析头 = echo → ALLOW，第二行照样被 shell
 *    执行 —— 换行注入绕过。
 *  - 首 token 引号/反斜杠感知：`"rm"` / `'rm'` / `r\m` 不再因带引号而躲开
 *    黑名单匹配（parse 路径保守判 complex；checkSegments 路径去引号后精确匹配）。
 */
data class ParsedCommand(
    val executable: String?,      // basename of the first token, null if unparseable/complex
    val arguments: List<String>,
    val raw: String,
    val isComplex: Boolean         // true if command contains shell operators (| ; && || sh -c etc.)
) {
    /** True if this command invokes a shell wrapper that could bypass policy (Spec §6). */
    val isShellWrapper: Boolean get() = executable in CommandParser.shellWrappers
}

object CommandParser {

    // T85：`\u0024\u0028` = "$("（命令替换）—— 与其余操作符共同构成分段边界。
    internal val shellOperators = listOf("&&", "||", ";", "|", ">", ">>", "<", "&", "`", "\u0024\u0028", "(")
    // T85：扩展 shell 包装器集合 —— deep-scan（全 token 扫描）候选。
    internal val shellWrappers = setOf(
        "sh", "bash", "zsh", "dash", "ash", "ksh", "mksh",
        "env", "command", "exec", "source", ".", "eval",
        "xargs", "nohup", "sudo", "su", "time", "strace", "ltrace", "watch"
    )

    /**
     * Parse a command string into ParsedCommand.
     *
     * Conservative: if shell operators OR shell wrappers are detected, isComplex=true and
     * executable=null (policy will DENY by default per Spec §6).
     */
    fun parse(raw: String): ParsedCommand {
        // T85（S-1）：剥离单个尾随换行（sendLine 追加的 \n 不算嵌入），其余换行
        // 一律视为多行注入 → complex。
        val trimmed = raw.trim().trimEnd('\n', '\r').trim()
        if (trimmed.isEmpty()) return ParsedCommand(null, emptyList(), raw, isComplex = true)
        if (trimmed.any { it == '\n' || it == '\r' }) {
            return ParsedCommand(null, emptyList(), raw, isComplex = true)
        }

        // Detect shell operators → complex
        val hasOperator = shellOperators.any { op -> trimmed.contains(op) }
        if (hasOperator) {
            return ParsedCommand(null, emptyList(), raw, isComplex = true)
        }

        // Tokenize by whitespace (quote-aware for the FIRST token; complex quoting → complex)
        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.isEmpty()) return ParsedCommand(null, emptyList(), raw, isComplex = true)

        val first = extractFirstToken(trimmed)
        // T85（S-2）：首 token 带引号/反斜杠 —— 保守判 complex（parse 执行路径不猜；
        // checkSegments 交互路径会去引号精确匹配黑名单）。
        if (first.hadQuoting) {
            return ParsedCommand(null, emptyList(), raw, isComplex = true)
        }
        val executable = basename(first.text)

        // Shell wrapper detection (sh -c "..." etc.)
        if (executable in shellWrappers) {
            return ParsedCommand(executable, tokens.drop(1), raw, isComplex = true)
        }

        return ParsedCommand(executable, tokens.drop(1), raw, isComplex = false)
    }

    /** Extract basename: "/bin/rm" → "rm", "./rm" → "rm", "rm" → "rm". */
    fun basename(path: String): String {
        val cleaned = path.removePrefix("./").removePrefix("../")
        return cleaned.substringAfterLast('/').ifEmpty { cleaned }
    }

    /**
     * T85：引号/转义感知的首 token 提取。
     *
     * `"rm"` → text="rm" hadQuoting=true；`r\m` → text="rm" hadQuoting=true；
     * `ls "my file.txt"` → text="ls" hadQuoting=false（引号在后段不影响执行名）。
     * 未闭合引号按词尾处理（hadQuoting=true，保守路径判 complex）。
     */
    internal fun extractFirstToken(s: String): FirstToken {
        val sb = StringBuilder()
        var i = 0
        var quoting = false
        var inQuote: Char? = null
        while (i < s.length) {
            val c = s[i]
            when {
                inQuote != null -> {
                    if (c == '\\' && inQuote == '"') {
                        s.getOrNull(i + 1)?.let(sb::append); i += 2; quoting = true; continue
                    }
                    if (c == inQuote) inQuote = null else sb.append(c)
                    quoting = true
                }
                c == '"' || c == '\'' -> { inQuote = c; quoting = true }
                c == '\\' -> {
                    s.getOrNull(i + 1)?.let(sb::append); i += 2; quoting = true; continue
                }
                c.isWhitespace() -> break
                else -> sb.append(c)
            }
            i++
        }
        return FirstToken(sb.toString(), quoting)
    }

    /** [extractFirstToken] 的结果：去引号后的文本 + 是否出现过引号/转义。 */
    internal data class FirstToken(val text: String, val hadQuoting: Boolean)

    /**
     * T85：把一行命令按 shell 操作符切段（引号内不切）。
     *
     * `echo a && rm -rf /` → ["echo a ", " rm -rf /"]。
     * 引号内的 `|`/`;` 不是操作符：`grep "a|b" file` 保持单段。
     */
    internal fun splitSegments(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuote != null -> {
                    sb.append(c)
                    if (c == inQuote) inQuote = null
                }
                c == '"' || c == '\'' -> { inQuote = c; sb.append(c) }
                c == '\\' -> {
                    sb.append(c); s2(line, i + 1)?.let { sb.append(it) }; i += 2; continue
                }
                else -> {
                    val two = if (i + 2 <= line.length) line.substring(i, i + 2) else ""
                    if (two == "&&" || two == "||" || two == ">>" || two == "$(" || two == "<<") {
                        out.add(sb.toString()); sb.clear(); i += 2; continue
                    }
                    if (c == ';' || c == '|' || c == '&' || c == '`' || c == '>' || c == '<' || c == '(') {
                        out.add(sb.toString()); sb.clear(); i++; continue
                    }
                    sb.append(c)
                }
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun s2(s: String, i: Int): Char? = if (i < s.length) s[i] else null
}

/**
 * Command Policy decision (Spec PR #51 §1).
 *
 * v1 contract: [CommandPolicy.check] only ever produces ALLOW / DENY.
 * [REQUIRE_CONFIRMATION] is reserved for a future Confirmation UI — it is NEVER emitted in
 * v1. Consumers (e.g. [TerminalPolicyImpl]) MUST treat it as DENY, never as a silent ALLOW:
 * a command that requires confirmation must not pass until a UI exists to obtain it.
 */
enum class CommandPolicyDecision { ALLOW, DENY, REQUIRE_CONFIRMATION }

/**
 * Policy mode (Spec §9).
 *   ALLOW_ALL       — denylist only; everything not denied is allowed
 *   ALLOWLIST_ONLY  — only allowlist commands allowed (denylist still takes precedence)
 */
enum class CommandPolicyMode { ALLOW_ALL, ALLOWLIST_ONLY }

/**
 * Built-in DEFAULT policy (Spec §9).
 *
 * Kept separate from [CommandPolicy] on purpose: the default policy is what applies when a
 * layer (user / global / agent-session) does NOT configure anything. A future Global/Session
 * policy layer composes as `effective = default ∘ configured`:
 *   - configured denylist == null          → inherit [DEFAULT_DENYLIST]
 *   - configured denylist == emptySet()    → defaults explicitly cleared (opt-out)
 *   - configured denylist == non-empty     → defaults replaced by the configured set
 */
object DefaultCommandPolicy {

    /** Destructive commands denied by default (Spec §9 example). */
    val DEFAULT_DENYLIST: Set<String> = setOf(
        "shutdown", "reboot", "mkfs", "dd", "halt", "poweroff"
    )
}

/**
 * Command Policy configuration (Spec §9).
 *
 * Priority: DENYLIST > ALLOWLIST > DEFAULT.
 *   - If command in denylist → DENY (even if also in allowlist)
 *   - If mode=ALLOWLIST_ONLY and command not in allowlist → DENY
 *   - Otherwise → ALLOW
 *
 * Policy is controlled by App/System/User, NOT by Agent (Spec §11). Agent only receives
 * ALLOW/DENY; it cannot modify the policy.
 */
data class CommandPolicy(
    val mode: CommandPolicyMode = CommandPolicyMode.ALLOW_ALL,
    val allowlist: Set<String> = emptySet(),
    /** null → inherit [DefaultCommandPolicy.DEFAULT_DENYLIST]; empty set clears defaults. */
    val denylist: Set<String>? = null
) {
    /**
     * Effective denylist after resolving defaults (default policy ∘ configured policy).
     *
     * This is the seam where a future Global/Session policy layer plugs in: it can build
     * a [CommandPolicy] with an explicitly resolved denylist instead of relying on the default.
     */
    val effectiveDenylist: Set<String>
        get() = denylist ?: DefaultCommandPolicy.DEFAULT_DENYLIST

    /**
     * Check a parsed command against the policy.
     *
     * Conservative (Spec §6): complex/unparseable commands → DENY.
     * v1 contract: returns only [CommandPolicyDecision.ALLOW] or [CommandPolicyDecision.DENY].
     */
    fun check(parsed: ParsedCommand): CommandPolicyDecision {
        // Complex commands (shell operators, wrappers) → DENY (conservative)
        if (parsed.isComplex || parsed.executable == null) {
            return CommandPolicyDecision.DENY
        }

        val exe = parsed.executable

        // 1. Denylist takes highest precedence (over allowlist and defaults)
        if (exe in effectiveDenylist) return CommandPolicyDecision.DENY

        // 2. Allowlist mode: must be in allowlist
        if (mode == CommandPolicyMode.ALLOWLIST_ONLY) {
            return if (exe in allowlist) CommandPolicyDecision.ALLOW else CommandPolicyDecision.DENY
        }

        // 3. ALLOW_ALL mode: allow if not denied
        return CommandPolicyDecision.ALLOW
    }

    /** Convenience: check a raw command string. */
    fun check(rawCommand: String): CommandPolicyDecision = check(CommandParser.parse(rawCommand))

    /**
     * T85：分段检查 —— 交互输入 / 用户 / 系统命令的务实策略。
     *
     * 与 [check]（Agent LINE 执行路径，复杂即拒）不同，本方法把命令按行、按 shell
     * 操作符切段，逐段取**去引号后的执行名**匹配：
     *
     *  - `echo hi && rm -rf /` → 段 `echo hi` ✓ + 段 `rm -rf /` ✗ → DENY（黑名单仍拦截）
     *  - `apt-get update && apt-get install -y git` → 两段头都是 apt-get → ALLOW
     *    （旧 parse 路径 complex→DENY：环境中心的 `&&` 链式安装命令全部被误杀 ——
     *    这正是本方法存在的理由）
     *  - `"rm" -rf /` / `r\m` → 去引号/转义后 = rm → DENY
     *  - `FOO=1 rm -rf /` → 剥离前导赋值后头 = rm → DENY
     *  - `bash -c "shutdown"` → 包装器段 deep-scan 全 token → shutdown → DENY
     *  - `grep "a|b" file` → 引号内 | 不切段 → 头 grep → ALLOW（REPL/文本输入不误伤）
     *
     * 已知极限（防御纵深而非唯一边界，KDoc 存档）：变量间接（`x=rm; $x`）与运行时
     * 拼接无法静态解析 —— 交互路径的价值在于拦「误操作级」命令，蓄意混淆由
     * 沙箱（PRoot）与确认门兜底。
     */
    fun checkSegments(rawCommand: String): CommandPolicyDecision {
        val deny = effectiveDenylist
        val allowOnly = mode == CommandPolicyMode.ALLOWLIST_ONLY
        for (line in rawCommand.split('\n', '\r')) {
            for (segment in CommandParser.splitSegments(line)) {
                if (checkSegment(segment, deny, allowOnly) == CommandPolicyDecision.DENY) {
                    return CommandPolicyDecision.DENY
                }
            }
        }
        return CommandPolicyDecision.ALLOW
    }

    private fun checkSegment(segment: String, deny: Set<String>, allowOnly: Boolean): CommandPolicyDecision {
        var rest = segment.trim()
        // 剥离前导环境变量赋值：FOO=bar cmd → cmd（裸赋值 `FOO=shutdown` 无执行语义，跳过）
        while (true) {
            val m = envAssignmentPrefix(rest)
            if (m == null) break
            rest = m
        }
        if (rest.isEmpty()) return CommandPolicyDecision.ALLOW
        val first = CommandParser.extractFirstToken(rest)
        val exe = CommandParser.basename(first.text).lowercase()
        if (exe.isEmpty()) return CommandPolicyDecision.ALLOW

        if (matchesDenylist(exe, deny)) return CommandPolicyDecision.DENY

        // shell 包装器段：deep-scan 全部词（引号透明分词）
        // `bash -c "rm -rf /"` / `env rm` / `xargs rm` / `nohup dd …`
        if (exe in CommandParser.shellWrappers) {
            for (word in tokenizeRest(rest)) {
                val t = CommandParser.basename(word).lowercase()
                if (t.isNotEmpty() && matchesDenylist(t, deny)) {
                    return CommandPolicyDecision.DENY
                }
            }
        }

        if (allowOnly && exe !in allowlist) return CommandPolicyDecision.DENY
        return CommandPolicyDecision.ALLOW
    }

    /**
     * 黑名单匹配（含家族变体）：`mkfs.ext4` / `mkfs.vfat` 命中黑名单 `mkfs`。
     * 默认危险命令名单存的是工具族名，真实调用几乎总带变体后缀。
     */
    private fun matchesDenylist(exe: String, deny: Set<String>): Boolean {
        if (exe in deny) return true
        val dot = exe.indexOf('.')
        return dot > 0 && exe.substring(0, dot) in deny
    }

    /** 剥离一个前导 `VAR=value ` 前缀；无则返回 null。 */
    private fun envAssignmentPrefix(s: String): String? {
        val m = Regex("^[A-Za-z_][A-Za-z0-9_]*=\\S*\\s+").find(s) ?: return null
        return s.substring(m.value.length)
    }

    /**
     * 段内全部词（引号透明分词）—— 包装器 deep-scan 用。
     *
     * `bash -c "rm -rf /"` → [bash, -c, rm, -rf, /]：引号内的载荷被逐词扫描
     *（旧实现整段引号串算一个 token，`rm -rf /` 的 basename 取不到 rm —— 绕过）。
     */
    private fun tokenizeRest(s: String): List<String> {
        val cleaned = StringBuilder()
        var inQuote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                inQuote != null -> {
                    if (c == '\\' && inQuote == '"') { s.getOrNull(i + 1)?.let(cleaned::append); i += 2; continue }
                    if (c == inQuote) inQuote = null else cleaned.append(c)
                }
                c == '"' || c == '\'' -> inQuote = c
                c == '\\' -> { s.getOrNull(i + 1)?.let(cleaned::append); i += 2; continue }
                else -> cleaned.append(c)
            }
            i++
        }
        return cleaned.toString().split(Regex("\\s+")).filter { it.isNotBlank() }
    }
}
