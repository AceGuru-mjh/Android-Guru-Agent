package com.apex.agent.core.code.thinking

/**
 * # Code Thinking Prompts — Coding 模式专属思考档位指令（七档思考系统）
 *
 * ## 与既有思考系统的关系（三层分工，不重复注入）
 *
 * 1. **通用思考画像**（agent-engine 的 ThinkingProfile）：
 *    引擎每轮经 ThinkingModeController 注入 "## Thinking Instructions" 段
 *    （推理框架 + 迭代/压缩/输出预算等执行策略）—— 任何模式共用。
 *    coding 侧档位经 [CodeThinkingLevel.toAgentLevel] 映射打底，深水两档
 *    的增量由 [CodeThinkingProfile] 旋钮补偿反补；
 * 2. **本类（coding 特化指令）**：按当前档位注入编码纪律的**补充**指令 ——
 *    通用画像讲「怎么想」，本类讲「编码时具体怎么想」：读码纪律、验证
 *    回路、子代理委派、架构分解、对抗性自审等编码特有方法论；
 * 3. **模型原生 reasoning 参数**（reasoning_effort / thinking_budget）：
 *    由 app 层 CodeThinkingLevel→ReasoningEffort 映射通道下发，与本类无关。
 *
 * 注入通道：CodeAgentEngine.refreshContext 把 [thinkingDirective] 的产物
 * 拼进 additionalSystemContext（与 Rules/Workspace 段并列）—— 引擎零改动，
 * agent 模式零影响。
 *
 * ## 七档编码思考阶梯（NONE 档不注入，其余递进）
 *
 * | 档位 | 编码特化要点 |
 * |------|--------------|
 * | NONE | 不注入（快速直改，配合通用画像的 ×0.8 迭代预算） |
 * | LIGHT | 一读一改：改前扫一眼目标处，改完扫一眼结果 |
 * | STANDARD | 标准编码循环：读→改→验→回读，diff 最小化 |
 * | DEEP | 改动集思维：先建改动清单再动手，每处改动关联验证方式 |
 * | MAXIMUM | 不变量守护：识别代码不变量与连锁影响，改后全链路核对 |
 * | ULTRACODE | 编码深推理闭环：依赖地图→候选改法→风险排序→最小修改→即时验证→回归扫描 |
 * | APEXCODE | 架构级穷举：影响半径测绘→多方案对比矩阵→对抗性自审自己的 diff→全量验证矩阵→证据链汇报（内嵌 [CodeThinkingProfile.APEX_SELF_CHECK_CHECKLIST] 五问清单） |
 *
 * AUTO 档：VM 发送前经 [CodeAdaptiveThinkingSelector] 预检解析出具体
 * 深度档（引擎接收的是解析后的档位），本类注入一行「预检自适应」说明 +
 * [AUTO_FALLBACK_DIRECTIVE_LEVEL]（DEEP）的编码纪律兜底。
 *
 * 纯静态、无副作用；同档位多次调用返回等值字符串（幂等，JIT 刷新安全）。
 */
object CodeThinkingPrompts {

    /** AUTO 档的编码纪律兜底档（见类 KDoc：自适应实际档位 ≥STANDARD 全适用）。 */
    val AUTO_FALLBACK_DIRECTIVE_LEVEL: CodeThinkingLevel = CodeThinkingLevel.DEEP

    /**
     * 按档位返回编码特化思考指令（拼进 additionalSystemContext）。
     *
     * NONE → 空串（不注入段落，与通用画像的「不思考」语义对齐）。
     */
    fun thinkingDirective(level: CodeThinkingLevel): String = when (level) {
        CodeThinkingLevel.NONE -> ""
        CodeThinkingLevel.LIGHT -> LIGHT_DIRECTIVE
        CodeThinkingLevel.STANDARD -> STANDARD_DIRECTIVE
        CodeThinkingLevel.DEEP -> DEEP_DIRECTIVE
        CodeThinkingLevel.MAXIMUM -> MAXIMUM_DIRECTIVE
        CodeThinkingLevel.ULTRACODE -> ULTRACODE_DIRECTIVE
        CodeThinkingLevel.APEXCODE -> APEXCODE_DIRECTIVE
        CodeThinkingLevel.AUTO -> autoDirective()
    }

    /**
     * AUTO 档指令：说明发送前预检机制 + DEEP 级编码纪律兜底。
     * （独立方法而非常量，便于测试断言组合语义。）
     *
     * 注意拼接顺序：先对字面量 trimIndent 再拼接兜底体——若把兜底体直接
     * 插值进原始字符串，其 2 空格缩进会拉低全串的公共缩进基准，
     * trimIndent 后正文行会残留 6 空格前缀（Kotlin 字符串模板先求值、
     * trimIndent 后运行的求值顺序陷阱）。
     */
    private fun autoDirective(): String =
        """
        ### 编码思考模式：自适应（AUTO）
        思考深度已在发送前按任务复杂度预检选档（长任务深水区运行中可自动加深）。编码纪律基线：
        """.trimIndent() + "\n" + DEEP_DIRECTIVE_BODY_INDENTED

    // ═══════════════════ 各档指令正文 ═══════════════════

    private val LIGHT_DIRECTIVE = """
### 编码思考（LIGHT · 一读一改）
- 动手前 code_read 目标位置一眼（确认行号与上下文），改完扫一眼 diff 输出。
- 不确定就退回 STANDARD，不要在猜的状态下提交编辑。
""".trimIndent()

    private val STANDARD_DIRECTIVE = """
### 编码思考（STANDARD · 标准编码循环）
- 每处修改走完整闭环：code_read 回看原文 → code_edit 最小 diff → 读工具回执（含诊断块）→ 必要时回读验证。
- 保持改动集最小：只改任务要求的代码，顺手重构留给用户明确要求时再做。
- 诊断块（⚠️）出现时先处理再继续；连续两次改不动时停下来重新读上下文，不要盲目重试。
""".trimIndent()

    private val DEEP_DIRECTIVE = """
### 编码思考（DEEP · 改动集思维）
- 动手前先建「改动集清单」：这个任务需要碰哪些文件、每处改什么、怎么验证——清单想全了再开始第一个编辑。
- 每处改动预先关联验证方式（回读 / 诊断 / 构建），改完立即执行对应验证，不攒到最后一起验。
- 跨文件改动注意一致性：同名符号、重复逻辑、配置与代码的对应关系。
- 探索类工作（找所有调用点 / 摸清模块结构）优先 code_task 委派子代理，结论回主线再决策。
""".trimIndent()

    private val MAXIMUM_DIRECTIVE = """
### 编码思考（MAXIMUM · 不变量守护）
- 改前识别代码的**不变量**：这段代码隐含依赖什么始终成立（参数非空 / 列表有序 / 资源配对 / 状态机合法转移）？改动不能默默破坏它们。
- 连锁影响核对：被改符号的所有调用方、被改数据的所有消费方、被改配置的所有读取方——逐个确认是否受影响。
- 改后全链路核对：编辑成功 ≠ 语义正确。回读改动处 + 抽查一个调用方 + 跑一次可用的验证（构建 / 测试 / 诊断）。
- 验证失败时的纪律：先读完整错误信息再动手，同一改法最多重试一次，之后必须换思路或求助用户。
""".trimIndent()

    private val ULTRACODE_DIRECTIVE = """
### 编码思考（ULTRACODE · 编码深推理闭环）
按以下闭环推进，每一步的结论都要能落到具体文件与行号：
1. **依赖地图**：改动波及哪些模块/文件/符号？用 code_grep / code_task(explore) 摸清调用链与依赖方向，画出（在思考中）本次改动的影响面。
2. **候选改法**：为每个关键决策生成 ≥2 个候选改法（最小侵入 / 更彻底重构），不提前锁定。
3. **风险排序**：按「破坏不变量的风险 × 回滚成本」给候选打分，选风险可控的那个，理由写进执行过程。
4. **最小修改**：按选定改法做最小 diff 编辑；每处编辑后立即消化诊断块与回执。
5. **即时验证**：每个逻辑单元改完立即验证（回读 / 诊断 / 构建 / 测试），不攒批。
6. **回归扫描**：全部改完后，对影响面内的调用点做一次回归检查（grep 调用方逐个确认语义未破坏）。
- 探索与调研全部委派 code_task 子代理并行跑，主对话只留决策与编辑。
- 中途换方向时，先记录「为什么放弃当前路线」再切换，避免来回摇摆。
""".trimIndent()

    /**
     * APEXCODE 指令：巅峰档正文 + 内嵌五问终检清单
     * （[CodeThinkingProfile.APEX_SELF_CHECK_CHECKLIST]——模型在产出
     * 最终回复之前看到，真实影响本轮输出；additionalSystemContext 时机
     * 与引擎侧终检通道等价）。
     */
    private val APEXCODE_DIRECTIVE = """
### 编码思考（APEXCODE · 架构级穷举推理）
这是最高思考档位，按工程评审的强度对待每一次改动：
1. **架构定位**：先在思考中建立本次改动在整体架构中的位置——它属于哪一层、依赖谁、被谁依赖、改动会改变哪些接口契约。
2. **影响半径测绘**：用 code_task(explore) 并行测绘直接与间接影响（调用方 / 配置 / 文档 / 测试 / 生成代码），形成完整影响半径清单。
3. **多方案对比矩阵**：候选方案 ×（侵入度 / 风险 / 可回滚性 / 与项目既有风格一致性 / 长期维护成本）打分对比，选全局最优而非局部省事。
4. **对抗性自审**：把自己的 diff 当成别人的 PR 来攻击——边界条件？错误路径？并发与生命周期？命名与注释一致性？找出至少一处可挑剔处并修复。
5. **全量验证矩阵**：改完后按可用性执行——构建（terminal.exec）/ lint / 测试 / 关键文件回读 / 诊断全绿——每项留下证据（输出摘要），不凭感觉宣称通过。
6. **证据链汇报**：结论里给出「改了什么 / 为什么这样改 / 验证证据 / 遗留风险」四段式汇报，引用一律 path:line。
- 全程维持检查点纪律：每完成一个阶段用 code_todo 勾掉并简注结果，任务中断后可从检查点恢复。
""".trimIndent() + "\n\n" + CodeThinkingProfile.APEX_SELF_CHECK_CHECKLIST

    /** DEEP 指令的缩进体（AUTO 兜底拼接用：整体右移两格保持嵌套可读）。 */
    private val DEEP_DIRECTIVE_BODY_INDENTED: String = DEEP_DIRECTIVE
        .lineSequence()
        .drop(1) // 丢标题行（AUTO 段已有自己的标题）
        .joinToString("\n") { it.trimEnd().ifEmpty { it }.prependIndent("  ") }
}
