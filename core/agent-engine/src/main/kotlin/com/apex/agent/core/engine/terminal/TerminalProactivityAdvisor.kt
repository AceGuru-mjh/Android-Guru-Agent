package com.apex.agent.core.engine.terminal

/**
 * #170 终端主动性顾问 —— 会话编排与 Ubuntu 预备的轮次级建议注入。
 *
 * 用户反馈：Agent 不会主动用终端 —— 倾向一次性 shell（shell_execute /
 * terminal.exec），不建 PTY 会话、不预备 Ubuntu 环境。静态策略文本
 * （EnginePrompts 的 Terminal-Use Policy）解决"知道该怎么做"；本顾问解决
 * "在正确的时机被提醒"：
 *
 *  - **SESSION_FLOW**：连续 ≥3 次一次性 shell 完成（滑窗计数；会话流工具
 *    terminal.create/run/write/observe/wait 任一出现即清零）→ 注入 system
 *    消息提示模型切换会话流。每命中一次阈值提醒一次（3、6、9…），
 *    streak 停滞不重复唠叨；
 *  - **ENSURE_UBUNTU**：用户 prompt 或近期一次性命令参数含工具链关键词
 *    （apt-get / apt install / git clone / npm / yarn / pip / cargo / make /
 *    gcc / python3 / node / cmake）且本会话未出现过任何 Ubuntu 探测
 *    （terminal.ubuntu.ensure/install/status、terminal.backends）→ 建议先
 *    terminal.ubuntu.ensure。**每会话最多 1 次**；
 *  - **FAILURE_RECOVERY**：一次性命令连续失败 ≥2 次 → 建议检查输出 / 换会话 /
 *    问用户，不再盲目重试。失败连击同时会作为附注拼进 SESSION_FLOW 建议。
 *
 * 引擎接线（零逻辑）：[com.apex.agent.core.engine.ApexAgentEngine] 在
 * executeToolCallStreaming 完成分支调 [onToolCallCompleted]；executeBuildLoop
 * 迭代开始处调 [onIterationStart]，非空建议以 System 消息写入历史（下一轮
 * LLM 请求可见）。纯 Kotlin、可单测（无引擎/Android 依赖）。
 *
 * 线程模型：仅引擎 flow 协程按序调用（工具完成 → 迭代开始严格串行），
 * 无跨线程读写，普通字段即可。
 */
class TerminalProactivityAdvisor {

    /** 建议类别：NONE 仅为语义占位（[onIterationStart] 无建议时返回 null）。 */
    enum class Kind { SESSION_FLOW, ENSURE_UBUNTU, FAILURE_RECOVERY, NONE }

    /** 一条可注入的轮次级建议：system 消息文本 + 类别（日志/测试断言用）。 */
    data class Advice(val systemNote: String, val kind: Kind)

    // ═══════════════════════ 状态（会话级，跨 execute() 存续）═══════════════════════

    /** 连续一次性 shell 完成数（滑窗；会话流工具出现即清零）。 */
    private var oneShotStreak = 0

    /** 上次发出 SESSION_FLOW 建议时的 streak 值（再涨 3 才提醒下一次）。 */
    private var lastSessionFlowAdviceStreak = 0

    /** 一次性命令连续失败数（任一次成功即清零）。 */
    private var consecutiveOneShotFailures = 0

    /** 近期一次性命令参数（尾部窗口，工具链关键词的第二信号源）。 */
    private val recentOneShotArgs = ArrayDeque<String>(RECENT_ARGS_WINDOW)

    /** 本会话是否已给过 ENSURE_UBUNTU 建议（每会话最多 1 次）。 */
    private var ubuntuAdviceGiven = false

    /** 本会话是否出现过 Ubuntu 探测（ensure/install/status/backends）。 */
    private var ubuntuProbeSeen = false

    // ═══════════════════════ 工具完成钩子（引擎：executeToolCallStreaming）═══════════════════════

    /**
     * 引擎工具完成钩子。
     *
     * @param toolId 注册表 id（引擎已把 provider 名映射回 registry id）
     * @param succeeded 工具成败（流式 Error 信号或文本前缀判定，与
     *   ToolCallComplete.success 同源）
     * @param args 原始参数 JSON（默认空 —— 关键词检测的次要信号）
     */
    fun onToolCallCompleted(toolId: String, succeeded: Boolean, args: String = "") {
        when {
            toolId in ONE_SHOT_TOOLS -> {
                oneShotStreak++
                if (args.isNotBlank()) {
                    if (recentOneShotArgs.size >= RECENT_ARGS_WINDOW) recentOneShotArgs.removeFirst()
                    recentOneShotArgs.addLast(args)
                }
                if (succeeded) {
                    consecutiveOneShotFailures = 0
                } else {
                    consecutiveOneShotFailures++
                }
            }
            toolId in SESSION_FLOW_TOOLS -> {
                // 模型已在用会话流 —— 一次性 streak 失去意义，清零。
                oneShotStreak = 0
            }
            toolId in UBUNTU_PROBE_TOOLS -> {
                ubuntuProbeSeen = true
            }
        }
    }

    // ═══════════════════════ 迭代开始钩子（引擎：executeBuildLoop）═══════════════════════

    /**
     * 迭代开始：判定本轮是否注入建议。返回 null = 无建议。
     *
     * 优先级 ENSURE_UBUNTU > SESSION_FLOW > FAILURE_RECOVERY —— 环境预备是
     * 前置条件（没装好 Ubuntu 谈何会话流）；失败连击作为附注拼进前两者。
     */
    fun onIterationStart(userPrompt: String): Advice? {
        // ── ENSURE_UBUNTU：工具链信号 + 从未探测 + 本会话没提醒过 ──
        if (!ubuntuAdviceGiven && !ubuntuProbeSeen && hasToolchainSignal(userPrompt)) {
            ubuntuAdviceGiven = true
            return Advice(
                systemNote = "Terminal guidance: this task involves a toolchain " +
                    "(apt / git / npm / pip / cargo / make / gcc / python3 / node / cmake). " +
                    "Before running any toolchain command, check terminal.backends; if the " +
                    "Ubuntu backend is not ready, call terminal.ubuntu.ensure FIRST and " +
                    "report its progress to the user.",
                kind = Kind.ENSURE_UBUNTU
            )
        }

        val failureNote = if (consecutiveOneShotFailures >= FAILURE_STREAK_THRESHOLD) {
            " Note: the last $consecutiveOneShotFailures one-shot command(s) also failed — " +
                "inspect their output before continuing."
        } else ""

        // ── SESSION_FLOW：连续一次性 shell 且距上次提醒又攒满一个阈值 ──
        if (oneShotStreak >= SESSION_FLOW_THRESHOLD &&
            oneShotStreak >= lastSessionFlowAdviceStreak + SESSION_FLOW_THRESHOLD
        ) {
            lastSessionFlowAdviceStreak = oneShotStreak
            return Advice(
                systemNote = "Terminal guidance: you have now run $oneShotStreak consecutive " +
                    "one-shot shell commands (shell_execute / terminal.exec). STOP issuing " +
                    "further one-shot commands for this task — switch to a session flow: " +
                    "terminal.create → terminal.run (run long commands in the background) → " +
                    "terminal.observe / terminal.wait. Sessions preserve cwd, environment " +
                    "and state across commands.$failureNote",
                kind = Kind.SESSION_FLOW
            )
        }

        // ── FAILURE_RECOVERY：一次性命令连击失败（无更高优先级建议时独立成条）──
        if (consecutiveOneShotFailures >= FAILURE_STREAK_THRESHOLD) {
            return Advice(
                systemNote = "Terminal guidance: the last $consecutiveOneShotFailures one-shot " +
                    "shell commands failed. Do NOT re-run the identical command again — " +
                    "inspect the output above, open a terminal session (terminal.create + " +
                    "terminal.run), or ask the user how to proceed.",
                kind = Kind.FAILURE_RECOVERY
            )
        }

        return null
    }

    /** 工具链信号：用户 prompt 或近期一次性命令参数命中关键词。 */
    private fun hasToolchainSignal(userPrompt: String): Boolean {
        val haystack = buildString {
            append(userPrompt.lowercase())
            recentOneShotArgs.forEach { append(' '); append(it.lowercase()) }
        }
        return TOOLCHAIN_KEYWORDS.any { haystack.contains(it) }
    }

    companion object {
        /** 一次性（无会话）shell 工具：streak 计数与失败追踪的对象。 */
        val ONE_SHOT_TOOLS = setOf("shell_execute", "terminal.exec")

        /** 会话流工具：任一出现即证明模型已切换会话编排，streak 清零。 */
        val SESSION_FLOW_TOOLS = setOf(
            "terminal.create", "terminal.run", "terminal.write",
            "terminal.observe", "terminal.wait"
        )

        /** Ubuntu 探测工具：出现即视为"模型已知环境状态"，不再给预备建议。 */
        val UBUNTU_PROBE_TOOLS = setOf(
            "terminal.ubuntu.ensure", "terminal.ubuntu.install",
            "terminal.ubuntu.status", "terminal.backends"
        )

        /**
         * 工具链关键词（小写子串匹配）。与 EnginePrompts Terminal-Use
         * Policy 第 4 条的工具链清单同源。
         */
        val TOOLCHAIN_KEYWORDS = listOf(
            "apt-get", "apt install", "git clone", "npm", "yarn", "pip",
            "cargo", "make", "gcc", "python3", "node", "cmake"
        )

        /** SESSION_FLOW 触发阈值：连续一次性 shell 完成数。 */
        const val SESSION_FLOW_THRESHOLD = 3

        /** 失败连击提示阈值。 */
        const val FAILURE_STREAK_THRESHOLD = 2

        /** 近期一次性命令参数的保留窗口（条）。 */
        private const val RECENT_ARGS_WINDOW = 6
    }
}
