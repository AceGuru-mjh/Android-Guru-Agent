package com.apex.agent.core.engine.assist

/**
 * # Promise-without-action detector（「承诺未执行」检测，BUILD 模式专属）
 *
 * ## 问题（用户反馈"Agent 不会主动调用命令"的核心机制根因之一）
 *
 * BUILD 模式的 ReAct 循环以「纯文本轮」为终止条件：模型某一轮只输出文字
 * 不带 tool_calls → ResponseComplete → 任务结束。弱模型经常第一轮叙述计划
 * （"我现在去执行 XX 命令…" / "I will now run the tests…"）—— 文字里承诺了
 * 动作但没有实际调用任何工具，循环直接终止，用户看到的是"只说不做"。
 *
 * 提示词层的 Tool-Use Policy 第 3 条（"NEVER end your turn with 'I will
 * now…'"）对弱模型约束力不足；本检测器是**引擎层的执行差异兜底**（与
 * [DecisionPointDetector] 之于 ask_user_choice 的关系同构）：
 *
 * - 检出「承诺动作」语迹 且 本任务尚未催促过 → 引擎注入一条 System 催促并
 *   `continue` 下一轮（模型获得一次"真做"的机会）；
 * - 未检出 / 已催促过 / 到达迭代上限 → 照常 ResponseComplete（安全降级：
 *   绝不因检测失败而丢掉已生成的回复，也绝不无限催促）。
 *
 * ## 检测规则（刻意保守——宁可漏检一次，不可把正常总结误判成承诺）
 *
 * 命中**强承诺语迹**（明确宣告即将执行的具体动作）：
 * - EN: "I will now/first/proceed/run/execute/check/create/read/search…"、
 *       "I'll …"、"I am going to / I'm going to"、"let me …"
 * - CN: "我现在去/现在开始执行"、"接下来我会/我将"、"我会先/让我先/我先来"、
 *       "马上去/这就去"、"下面我将"
 *
 * 排除（视为合法收尾）：
 * - 疑问句结尾（向用户提问 = 合法停轮，对应 ask_user 语义）；
 * - 过短回复（≤ 24 字符 —— "好的""收到"级确认语没有动作语义）。
 */
object PromiseDetector {

    /** EN 强承诺语迹（小写匹配）。 */
    private val EN_PATTERNS = listOf(
        "i will now", "i'll now", "i will first", "i'll first",
        "i am going to", "i'm going to", "i will proceed", "i'll proceed",
        "i will execute", "i will run ", "i will check", "i will create",
        "i will read", "i will search", "i will install", "i will write",
        "i will fetch", "i will verify", "let me ", "i will now "
    )

    /** CN 强承诺语迹。 */
    private val CN_PATTERNS = listOf(
        "我现在去", "现在开始执行", "接下来我会", "接下来我将", "下面我将",
        "我会先", "让我先", "我先来", "马上去", "这就去", "我将执行",
        "我将运行", "我将检查", "我先执行", "我去执行", "我来执行"
    )

    /** 过短回复阈值（字符）—— 确认语没有动作语义。 */
    private const val MIN_LENGTH = 24

    /**
     * @param text 本轮纯文本响应（无 tool_calls 的轮次）
     * @return true = 命中承诺语迹，值得注入一次催促
     */
    fun isPromiseWithoutAction(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < MIN_LENGTH) return false
        // 疑问句结尾 = 向用户提问（合法停轮）
        if (trimmed.endsWith("?") || trimmed.endsWith("？")) return false
        val lower = trimmed.lowercase()
        return EN_PATTERNS.any { lower.contains(it) } || CN_PATTERNS.any { trimmed.contains(it) }
    }

    /** 催促注入文案（与 Tool-Use Policy 第 3 条同一语义的引擎层强化）。 */
    const val NUDGE_MESSAGE =
        "Your last message announced actions (\"I will now…\" / \"接下来我会…\") but this turn " +
            "made NO tool calls — descriptions execute nothing. Make the actual tool calls NOW, " +
            "in this turn. If the task is genuinely complete or blocked, state the concrete " +
            "result/-blocker and stop without announcing future work."
}
