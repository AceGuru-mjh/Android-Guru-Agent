package com.apex.agent.core.engine.longtask

/**
 * # 内置长任务模板——高质量任务的「起点菜单」
 *
 * ## 为什么需要模板
 *
 * 长任务最大的隐形成本是**开场质量**：同一个重构需求，一句「帮我重构下」
 * 和一段「先通读、再计划、逐步改、改完验证」的结构化指令，跑出来的结果
 * 差距巨大。模板把社区验证过的任务编排（步骤骨架 + 推荐档位 + todo 草案）
 * 固化下来，用户选定模板 → 补充具体需求 → 开跑——把「会用」的门槛降到
 * 一次点击。
 *
 * ## 与复制的关系
 *
 * 模板记录（[instantiate] 产出，isTemplate=true）与真实运行记录同构——
 * 复制引擎 / 对比 / 检查点等全部能力对模板天然可用。区别只有两点：
 * 模板用**稳定 id**（同工作区重复实例化幂等覆盖，不堆积），且
 * [LongTaskStore.prune] 豁免模板（菜单项被裁掉等于功能消失）。
 *
 * ## 档位约定（重要——主控契约）
 *
 * [TaskTemplate.recommendedThinkingLevel] 是 ThinkingLevel 枚举名的字符串。
 * v1.2 主线（7 级思考系统）正在为枚举扩展 ULTRACODE / APEXCODE 两档——
 * 本文件按**最终约定**写 "ULTRACODE"（code-review / perf-opt 两个模板），
 * 不依赖也不引用枚举本身：即使接线时新档位尚未落地，这里也只是个字符串
 * （UI 层展示为文本；切档时由主控保证枚举追上）。
 *
 * ## goalTemplate 的占位符
 *
 * 模板文本中的 `%s` 在 [instantiate] 时全部替换为工作区显示名（用
 * [String.replace] 而非 String.format——format 遇到正文里意外的百分号
 * 会炸，replace 只认字面 `%s` 更稳）。模板正文刻意写成「需求以用户本轮
 * 输入为准」的开放式结构：模板负责编排方法论，具体需求留给用户补充。
 */
object LongTaskTemplates {

    /**
     * 一个内置任务模板。
     *
     * @property key 稳定标识（模板 id 的一部分：`template-<key>-<workspaceId>`）。
     * @property titleZh 中文标题（列表直显）。
     * @property titleEn 英文标题（预留双语 UI）。
     * @property goalTemplate goal 模板正文（中文，3-6 行方法论编排，`%s`
     *   = 工作区名）。
     * @property recommendedThinkingLevel 推荐思考档位（ThinkingLevel.name；
     *   ULTRACODE 依赖 v1.2 枚举扩展，见类 KDoc 档位约定）。
     * @property todoSkeleton 建议步骤骨架（3-6 条，作为新运行的 todo 草案）。
     * @property tags 标签（UI 分组/筛选预留）。
     */
    data class TaskTemplate(
        val key: String,
        val titleZh: String,
        val titleEn: String,
        val goalTemplate: String,
        val recommendedThinkingLevel: String,
        val todoSkeleton: List<String>,
        val tags: List<String>
    )

    /** 全部 8 个内置模板（顺序即 UI 菜单顺序：从最常用到最专业）。 */
    val ALL: List<TaskTemplate> = listOf(
        TaskTemplate(
            key = "refactor",
            titleZh = "系统性重构",
            titleEn = "Systematic Refactor",
            goalTemplate = """
                对工作区 %s 进行一次系统性重构：
                1. 通读项目结构与核心模块，识别命名不清、职责混杂、重复逻辑与过长函数；
                2. 制定分步重构计划（保持行为不变），每步只做一类改动并验证；
                3. 优先处理高风险区域（核心数据流与公共接口），每处改动说明动机与影响面；
                4. 重构完成后运行可用的构建或测试验证行为未变，并输出改动清单。
                具体重构范围以用户本轮补充说明为准。
            """.trimIndent(),
            recommendedThinkingLevel = "DEEP",
            todoSkeleton = listOf(
                "☐ 通读项目结构，标记问题清单",
                "☐ 制定分步重构计划",
                "☐ 逐项重构高风险区域",
                "☐ 运行构建/测试验证",
                "☐ 输出改动清单"
            ),
            tags = listOf("refactor", "quality")
        ),
        TaskTemplate(
            key = "bugfix",
            titleZh = "定位并修复 Bug",
            titleEn = "Bug Hunt & Fix",
            goalTemplate = """
                在工作区 %s 中定位并修复问题：
                1. 梳理 Bug 的现象与触发路径，收集相关日志与代码线索；
                2. 定位根因——区分表象与本质，不满足于打补丁式修复；
                3. 实施最小且稳健的修复，避免引入新的行为变化；
                4. 验证修复有效（构造触发场景或运行相关测试），并说明如何防止回归。
                Bug 现象与复现步骤以用户本轮输入为准。
            """.trimIndent(),
            recommendedThinkingLevel = "STANDARD",
            todoSkeleton = listOf(
                "☐ 梳理现象与触发路径",
                "☐ 定位根因",
                "☐ 实施最小修复",
                "☐ 验证并防回归"
            ),
            tags = listOf("bugfix")
        ),
        TaskTemplate(
            key = "feature",
            titleZh = "实现新功能",
            titleEn = "New Feature",
            goalTemplate = """
                在工作区 %s 中实现新功能：
                1. 理解现有代码风格与架构约定，找到最自然的接入点；
                2. 设计实现方案（数据结构、接口、模块边界），与项目现有模式保持一致；
                3. 分步实现：核心逻辑 → 边界处理 → 错误路径 → 必要的配置与文档；
                4. 自测主要路径与边界情况，输出功能说明与后续建议。
                功能需求以用户本轮输入为准。
            """.trimIndent(),
            recommendedThinkingLevel = "DEEP",
            todoSkeleton = listOf(
                "☐ 理解架构与接入点",
                "☐ 设计实现方案",
                "☐ 分步实现与自测",
                "☐ 输出功能说明"
            ),
            tags = listOf("feature")
        ),
        TaskTemplate(
            key = "code-review",
            titleZh = "深度代码评审",
            titleEn = "Deep Code Review",
            goalTemplate = """
                对工作区 %s 做一次深度代码评审：
                1. 建立项目全景：结构、依赖关系、核心数据流与关键不变量；
                2. 逐模块审查：正确性（边界、并发、资源泄漏）、可读性、可测性、安全隐患；
                3. 按严重程度分级输出问题清单（阻断 / 重要 / 建议），每条附定位与修复建议；
                4. 总结架构层面的系统性风险与改进方向，明确区分「必须改」与「可以改」。
                评审重点范围以用户本轮补充说明为准；未指定则全项目扫描。
            """.trimIndent(),
            recommendedThinkingLevel = "ULTRACODE",
            todoSkeleton = listOf(
                "☐ 建立项目全景",
                "☐ 逐模块审查",
                "☐ 分级问题清单",
                "☐ 架构级风险总结"
            ),
            tags = listOf("review", "quality")
        ),
        TaskTemplate(
            key = "test-gen",
            titleZh = "补充单元测试",
            titleEn = "Test Generation",
            goalTemplate = """
                为工作区 %s 补充单元测试：
                1. 梳理现有测试布局与惯例（框架、命名、目录结构），保持风格一致；
                2. 优先覆盖核心业务逻辑与边界条件（空值、极值、异常路径），其次覆盖工具类；
                3. 每个测试聚焦一个行为，命名表达意图，避免脆弱断言；
                4. 运行测试套件确认全绿，输出覆盖提升点与仍存在的测试盲区。
                重点覆盖目标以用户本轮输入为准。
            """.trimIndent(),
            recommendedThinkingLevel = "STANDARD",
            todoSkeleton = listOf(
                "☐ 梳理测试惯例",
                "☐ 覆盖核心逻辑与边界",
                "☐ 运行套件确认全绿",
                "☐ 输出盲区清单"
            ),
            tags = listOf("test", "quality")
        ),
        TaskTemplate(
            key = "docs-gen",
            titleZh = "生成项目文档",
            titleEn = "Documentation",
            goalTemplate = """
                为工作区 %s 生成或完善文档：
                1. 概览项目结构、模块职责与相互关系，给出清晰的目录说明；
                2. 为公开接口与核心流程补充说明（用途、参数、示例），语言简洁准确；
                3. 补充快速上手指南：环境要求、构建步骤、常见问题；
                4. 输出文档清单，标注每份文档的受众与维护建议。
                文档语言与侧重以用户本轮输入为准。
            """.trimIndent(),
            recommendedThinkingLevel = "LIGHT",
            todoSkeleton = listOf(
                "☐ 概览结构与职责",
                "☐ 补充接口与流程说明",
                "☐ 快速上手指南",
                "☐ 输出文档清单"
            ),
            tags = listOf("docs")
        ),
        TaskTemplate(
            key = "perf-opt",
            titleZh = "性能优化",
            titleEn = "Performance Optimization",
            goalTemplate = """
                对工作区 %s 进行性能优化：
                1. 建立性能基线：识别热点路径（启动、主循环、IO、渲染），明确可量化指标；
                2. 逐项分析热点根因（算法复杂度、冗余计算、IO 模式、内存分配），拒绝猜测式优化；
                3. 实施优化时保持接口不变，每项改动说明预期收益与验证方式；
                4. 复测对比基线数据，输出优化报告（改动、收益、风险、后续空间）。
                性能目标与关注指标以用户本轮输入为准。
            """.trimIndent(),
            recommendedThinkingLevel = "ULTRACODE",
            todoSkeleton = listOf(
                "☐ 建立性能基线",
                "☐ 分析热点根因",
                "☐ 逐项实施优化",
                "☐ 复测并输出报告"
            ),
            tags = listOf("performance")
        ),
        TaskTemplate(
            key = "migrate",
            titleZh = "依赖/框架迁移",
            titleEn = "Dependency Migration",
            goalTemplate = """
                对工作区 %s 执行依赖或框架迁移：
                1. 盘点当前依赖清单（直接与传递依赖），确认目标版本与破坏性变更清单；
                2. 制定迁移顺序（先孤立模块后核心链路），每步保持可构建、可回退；
                3. 逐项迁移并修复编译错误，优先语义等价改写，谨慎使用批量自动替换；
                4. 全量验证（构建、测试、关键路径手工检查），输出迁移报告与遗留风险。
                迁移目标版本以用户本轮输入为准。
            """.trimIndent(),
            recommendedThinkingLevel = "MAXIMUM",
            todoSkeleton = listOf(
                "☐ 盘点依赖与破坏性变更",
                "☐ 制定迁移顺序",
                "☐ 逐项迁移修复",
                "☐ 全量验证与报告"
            ),
            tags = listOf("migration")
        )
    )

    /**
     * 按 key 查模板（UI 点击菜单项 → 找到模板 → 实例化）。
     * @return 找不到（key 来自外部输入）→ null。
     */
    fun byKey(key: String): TaskTemplate? = ALL.firstOrNull { it.key == key }

    /**
     * 实例化模板为一条可入库的 [LongTaskRecord]（isTemplate = true）。
     *
     * - **稳定 id**（`template-<key>-<workspaceId>`）：同工作区重复实例化
     *   幂等覆盖（upsert 语义），不堆积；workspaceId 由 CodeWorkspaceManager
     *   生成（slug + 十六进制时间戳，天然满足 store 的 id 字符集约束）；
     * - goal = goalTemplate 的所有 `%s` 替换为 [workspaceName]；
     * - status = RUNNING（模板即「一次即将开始的运行」的起点形态，VM
     *   拿到后切换工作区、以 goal 发起运行，Tracker 会另立真实记录）；
     * - thinkingLevel = 推荐档位名（用户可改——这只是建议不是强制）；
     * - agentMode = "BUILD"（BUILD 是编码任务的默认模式）；
     * - todoSnapshot = todoSkeleton（VM 直接渲染成 todo 面板初稿）。
     */
    fun instantiate(t: TaskTemplate, workspaceId: String, workspaceName: String): LongTaskRecord {
        val now = System.currentTimeMillis()
        return LongTaskRecord(
            id = "${TEMPLATE_ID_PREFIX}${t.key}$TEMPLATE_ID_SEPARATOR$workspaceId",
            title = t.titleZh,
            goal = t.goalTemplate.replace(PLACEHOLDER, workspaceName),
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            thinkingLevel = t.recommendedThinkingLevel,
            agentMode = DEFAULT_AGENT_MODE,
            status = LongTaskStatus.RUNNING,
            createdAt = now,
            updatedAt = now,
            endedAt = null,
            iterations = 0,
            toolCalls = 0,
            durationMs = 0,
            filesTouched = emptyList(),
            toolsUsed = emptyMap(),
            errorMessage = null,
            summary = null,
            checkpoints = emptyList(),
            todoSnapshot = t.todoSkeleton,
            parentTaskId = null,
            copyCount = 0,
            isTemplate = true,
            tags = t.tags
        )
    }

    private const val TEMPLATE_ID_PREFIX = "template-"
    private const val TEMPLATE_ID_SEPARATOR = "-"
    private const val PLACEHOLDER = "%s"

    /** 编码任务的默认执行模式名（AgentMode.BUILD.name）。 */
    private const val DEFAULT_AGENT_MODE = "BUILD"
}
