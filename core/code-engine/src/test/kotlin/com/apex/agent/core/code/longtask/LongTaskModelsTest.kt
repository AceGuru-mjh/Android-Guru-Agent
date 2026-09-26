package com.apex.agent.core.code.longtask

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长任务数据模型（[LongTaskRecord] / [LongTaskCheckpoint] /
 * [LongTaskCopyOptions] / [LongTaskStatus] / [LongTaskMagnitude]）的
 * 契约快照测试。
 *
 * 锁定三层不变量，任何一处漂移都应在这里红：
 *  1. **默认值语义**——零参可选字段的新记录是「运行起点」形态（统计归零、
 *     无血源、非模板），模型加字段时默认值不允许悄悄变更语义；
 *  2. **截断与容量常量**——title 60 / goal 4000 / files 200 / checkpoints 10 /
 *     summary 500 / error 500 / 检查点对话摘要 6 行，这些是生产者
 *     （Tracker）与消费者（Store / CopyEngine / Diff）共同依赖的防线；
 *  3. **序列化保真**——kotlinx Json roundtrip 全字段等值（含嵌套检查点、
 *     Map 工具分布、可空字段），以及 ignoreUnknownKeys 前向兼容与
 *     encodeDefaults 显式落盘两条 Store 依赖的 Json 配置语义。
 *
 * 纯 JVM（JUnit4，无协程无磁盘）——模型层零副作用，不需要 runTest。
 */
class LongTaskModelsTest {

    /** 全字段满配样本（roundtrip 的最大信息量形态，含嵌套检查点与 Map）。 */
    private fun fullRecord() = LongTaskRecord(
        id = "task-models-full",
        title = "全字段保真样本",
        goal = "多行目标：第一行\n第二行补充约束",
        workspaceId = "ws-models-1",
        workspaceName = "模型工作区",
        thinkingLevel = "ULTRACODE",
        agentMode = "PLAN",
        status = LongTaskStatus.FAILED,
        createdAt = 111L,
        updatedAt = 222L,
        endedAt = 222L,
        iterations = 33,
        toolCalls = 66,
        durationMs = 456_789L,
        filesTouched = listOf("a.kt", "b.kt", "c/d.kt"),
        toolsUsed = linkedMapOf(
            "code_edit" to 30,
            "code_read" to 25,
            "shell_execute" to 11
        ),
        errorMessage = "错误原因：两处断言失败",
        summary = "收尾摘要：完成 90%。",
        checkpoints = listOf(
            LongTaskCheckpoint(
                id = "cp-early",
                atIteration = 5,
                timestamp = 150L,
                toolCallCount = 9,
                filesTouchedCount = 1,
                recentExchange = listOf("用户: 开始", "工具 code_edit ✓"),
                todoDigest = listOf("☐ 第一步")
            ),
            LongTaskCheckpoint(
                id = "cp-late",
                atIteration = 30,
                timestamp = 200L,
                toolCallCount = 60,
                filesTouchedCount = 3,
                recentExchange = listOf("助手: 收尾"),
                todoDigest = listOf("☑ 第一步", "☐ 第二步")
            )
        ),
        todoSnapshot = listOf("☑ 已完成", "☐ 未完成"),
        parentTaskId = "task-parent-root",
        copyCount = 7,
        isTemplate = false,
        tags = listOf("refactor", "quality")
    )

    /** 只给必填字段的「新记录」形态（可选字段全走默认值）。 */
    private fun minimalRecord() = LongTaskRecord(
        id = "task-models-min",
        title = "最小记录",
        goal = "目标",
        workspaceId = "ws-models-1",
        workspaceName = "模型工作区",
        thinkingLevel = "LIGHT",
        agentMode = "BUILD",
        status = LongTaskStatus.RUNNING,
        createdAt = 100L,
        updatedAt = 100L
    )

    // ═════════════════ 默认值快照 ═════════════════

    /**
     * 新记录零参可选字段语义：RUNNING 起点——无结束时刻、统计归零、
     * 无改动集、无工具分布、无错误无摘要、无检查点无 todo、无血源、
     * 复制计数为零、非模板、无标签。
     */
    @Test
    fun `记录全字段默认值快照_新记录是运行起点形态`() {
        val record = minimalRecord()
        assertNull(record.endedAt)
        assertEquals(0, record.iterations)
        assertEquals(0, record.toolCalls)
        assertEquals(0L, record.durationMs)
        assertTrue(record.filesTouched.isEmpty())
        assertTrue(record.toolsUsed.isEmpty())
        assertNull(record.errorMessage)
        assertNull(record.summary)
        assertTrue(record.checkpoints.isEmpty())
        assertTrue(record.todoSnapshot.isEmpty())
        assertNull(record.parentTaskId)
        assertEquals(0, record.copyCount)
        assertFalse(record.isTemplate)
        assertTrue(record.tags.isEmpty())
        // 必填字段原样保真
        assertEquals("task-models-min", record.id)
        assertEquals(LongTaskStatus.RUNNING, record.status)
        assertEquals("LIGHT", record.thinkingLevel)
        assertEquals("BUILD", record.agentMode)
    }

    /** 检查点默认值：对话摘要行与 todo 摘要都为空列表。 */
    @Test
    fun `检查点默认值快照_摘要行为空`() {
        val cp = LongTaskCheckpoint(
            id = "cp-min",
            atIteration = 3,
            timestamp = 42L,
            toolCallCount = 5,
            filesTouchedCount = 2
        )
        assertTrue(cp.recentExchange.isEmpty())
        assertTrue(cp.todoDigest.isEmpty())
        assertEquals("cp-min", cp.id)
        assertEquals(3, cp.atIteration)
        assertEquals(42L, cp.timestamp)
        assertEquals(5, cp.toolCallCount)
        assertEquals(2, cp.filesTouchedCount)
    }

    // ═════════════════ 常量上限 ═════════════════

    /**
     * 记录级截断与容量上限（生产者 Tracker 组记录、消费者 CopyEngine 与
     * Diff 依赖的契约值）：title 60 / goal 4000 / files 200 / checkpoints
     * 10 / summary 500 / error 500。
     */
    @Test
    fun `常量上限与源码一致_六项截断防线`() {
        assertEquals(60, LongTaskRecord.TITLE_MAX_CHARS)
        assertEquals(4000, LongTaskRecord.GOAL_MAX_CHARS)
        assertEquals(200, LongTaskRecord.FILES_MAX)
        assertEquals(10, LongTaskRecord.CHECKPOINTS_MAX)
        assertEquals(500, LongTaskRecord.SUMMARY_MAX_CHARS)
        assertEquals(500, LongTaskRecord.ERROR_MAX_CHARS)
    }

    /** 检查点对话摘要行上限：6 行足够呈现「一个段落」的走向。 */
    @Test
    fun `检查点对话摘要行上限为六`() {
        assertEquals(6, LongTaskCheckpoint.RECENT_EXCHANGE_MAX)
    }

    // ═════════════════ 复制选项默认值 ═════════════════

    /**
     * CopyOptions 默认值：对话摘要与 todo 与文件清单默认携带（重跑最需要
     * 「上次的结论与未竟事项」），检查点默认不搬（审计材料）；标题、目标
     * 工作区、档位覆盖默认 null（沿用源）。
     */
    @Test
    fun `复制选项默认值_对话开检查点关_三个覆盖位为空`() {
        val options = LongTaskCopyOptions()
        assertTrue(options.includeConversation)
        assertTrue(options.includeTodos)
        assertTrue(options.includeFilesList)
        assertFalse(options.includeCheckpoints)
        assertNull(options.newTitle)
        assertNull(options.targetWorkspaceId)
        assertNull(options.thinkingLevelOverride)
    }

    // ═════════════════ 序列化保真 ═════════════════

    /**
     * 全字段 roundtrip：默认配置的 kotlinx Json 编码再解码，必填与可选、
     * 嵌套检查点（recentExchange / todoDigest）、Map 工具分布、可空字段
     * （endedAt / errorMessage / summary / parentTaskId）全部等值。
     */
    @Test
    fun `序列化roundtrip_全字段保真含嵌套检查点与工具分布`() {
        val record = fullRecord()
        val json = Json
        val encoded = json.encodeToString(LongTaskRecord.serializer(), record)
        val decoded = json.decodeFromString(LongTaskRecord.serializer(), encoded)
        assertEquals(record, decoded)

        // 抽查嵌套结构与 Map 的逐项保真（不依赖整体 equals 的兜底）
        assertEquals(2, decoded.checkpoints.size)
        assertEquals(record.checkpoints[0], decoded.checkpoints[0])
        assertEquals(record.checkpoints[1], decoded.checkpoints[1])
        assertEquals(listOf("用户: 开始", "工具 code_edit ✓"), decoded.checkpoints[0].recentExchange)
        assertEquals(listOf("☑ 第一步", "☐ 第二步"), decoded.checkpoints[1].todoDigest)
        assertEquals(mapOf("code_edit" to 30, "code_read" to 25, "shell_execute" to 11), decoded.toolsUsed)
        assertEquals("task-parent-root", decoded.parentTaskId)
        assertEquals(7, decoded.copyCount)
        assertEquals(456_789L, decoded.durationMs)
    }

    /** 检查点单独 roundtrip：字段逐一保真（不挂靠记录级编码）。 */
    @Test
    fun `检查点序列化roundtrip逐字段保真`() {
        val cp = LongTaskCheckpoint(
            id = "cp-solo",
            atIteration = 12,
            timestamp = 3456L,
            toolCallCount = 24,
            filesTouchedCount = 6,
            recentExchange = listOf("用户: 单测", "助手: 好的", "工具 code_write ✓"),
            todoDigest = listOf("☐ 独立编码", "☑ 独立解码")
        )
        val encoded = Json.encodeToString(LongTaskCheckpoint.serializer(), cp)
        assertEquals(cp, Json.decodeFromString(LongTaskCheckpoint.serializer(), encoded))
    }

    /**
     * 前向兼容：JSON 里混入未来版本的新字段，ignoreUnknownKeys 的 Json
     * （Store 同款配置）解码不炸且其余字段保真——「加字段优先给默认值」
     * 演进路线的读侧保证。
     */
    @Test
    fun `序列化前向兼容_未知字段容忍且其余保真`() {
        val record = fullRecord()
        val encoded = Json.encodeToString(LongTaskRecord.serializer(), record)
        // 注入一个未来字段（模拟旧代码读新版本落盘的文件）。
        // toMap + Pair 走标准库 Map 拼接，再包回 JsonObject，避免运算符解析歧义
        val tampered = JsonObject(
            Json.parseToJsonElement(encoded).jsonObject.toMap() +
                ("futureField" to JsonPrimitive(42))
        )
        val tolerant = Json { ignoreUnknownKeys = true }
        val decoded = tolerant.decodeFromString(
            LongTaskRecord.serializer(), tampered.toString()
        )
        assertEquals(record, decoded)
    }

    /**
     * 默认值显式落盘：encodeDefaults = true（Store 同款配置）下，最小记录
     * 的可选字段全部写进 JSON 文本——字段语义自文档、diff 友好；解码回来
     * 与原记录等值。
     */
    @Test
    fun `默认值显式落盘_encodeDefaults语义`() {
        val record = minimalRecord()
        val storeLikeJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val encoded = storeLikeJson.encodeToString(LongTaskRecord.serializer(), record)
        assertTrue("copyCount 默认值应显式落盘", encoded.contains("\"copyCount\""))
        assertTrue("isTemplate 默认值应显式落盘", encoded.contains("\"isTemplate\""))
        assertTrue("filesTouched 默认值应显式落盘", encoded.contains("\"filesTouched\""))
        assertTrue("toolsUsed 默认值应显式落盘", encoded.contains("\"toolsUsed\""))
        assertTrue("parentTaskId 默认值应显式落盘", encoded.contains("\"parentTaskId\""))
        assertEquals(
            record,
            storeLikeJson.decodeFromString(LongTaskRecord.serializer(), encoded)
        )
    }

    /**
     * 状态枚举在序列化里按名字落盘（roundtrip 途经 status 字段已覆盖），
     * valueOf 反查四态全部可用。
     */
    @Test
    fun `生命周期状态枚举完整_四态valueOf可用`() {
        assertEquals(
            listOf(
                LongTaskStatus.RUNNING,
                LongTaskStatus.COMPLETED,
                LongTaskStatus.ABORTED,
                LongTaskStatus.FAILED
            ),
            LongTaskStatus.entries.toList()
        )
        assertEquals(LongTaskStatus.RUNNING, LongTaskStatus.valueOf("RUNNING"))
        assertEquals(LongTaskStatus.COMPLETED, LongTaskStatus.valueOf("COMPLETED"))
        assertEquals(LongTaskStatus.ABORTED, LongTaskStatus.valueOf("ABORTED"))
        assertEquals(LongTaskStatus.FAILED, LongTaskStatus.valueOf("FAILED"))
    }

    // ═════════════════ 规模档位 ═════════════════

    /** 规模枚举三档齐全 + 四维 × 三档共 12 个阈值常量与源码一致。 */
    @Test
    fun `规模枚举三档齐全_四维十二个阈值常量与源码一致`() {
        assertEquals(
            listOf(LongTaskMagnitude.MEDIUM, LongTaskMagnitude.LONG, LongTaskMagnitude.EPIC),
            LongTaskMagnitude.entries.toList()
        )
        // MEDIUM 门槛
        assertEquals(8, LongTaskMagnitude.MEDIUM_ITERATIONS)
        assertEquals(12, LongTaskMagnitude.MEDIUM_TOOL_CALLS)
        assertEquals(120_000L, LongTaskMagnitude.MEDIUM_DURATION_MS)
        assertEquals(3, LongTaskMagnitude.MEDIUM_FILES)
        // LONG 门槛
        assertEquals(15, LongTaskMagnitude.LONG_ITERATIONS)
        assertEquals(25, LongTaskMagnitude.LONG_TOOL_CALLS)
        assertEquals(300_000L, LongTaskMagnitude.LONG_DURATION_MS)
        assertEquals(8, LongTaskMagnitude.LONG_FILES)
        // EPIC 门槛
        assertEquals(25, LongTaskMagnitude.EPIC_ITERATIONS)
        assertEquals(40, LongTaskMagnitude.EPIC_TOOL_CALLS)
        assertEquals(600_000L, LongTaskMagnitude.EPIC_DURATION_MS)
        assertEquals(15, LongTaskMagnitude.EPIC_FILES)
    }

    /**
     * fromSignals 判定语义：四维任一达标即算（OR）、取满足的最高档
     * （EPIC 信号不被低维稀释）、四维全低返回 null（不是长任务）。
     */
    @Test
    fun `fromSignals边界_四维任一达标取最高档`() {
        // 全维低于 MEDIUM → null
        assertNull(LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(7, 11, 119_999L, 2)))
        // 单维达标 MEDIUM（四维各自独立成立）
        assertEquals(
            LongTaskMagnitude.MEDIUM,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(8, 0, 0L, 0))
        )
        assertEquals(
            LongTaskMagnitude.MEDIUM,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(0, 12, 0L, 0))
        )
        assertEquals(
            LongTaskMagnitude.MEDIUM,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(0, 0, 120_000L, 0))
        )
        assertEquals(
            LongTaskMagnitude.MEDIUM,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(0, 0, 0L, 3))
        )
        // 单维达标 LONG
        assertEquals(
            LongTaskMagnitude.LONG,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(15, 0, 0L, 0))
        )
        assertEquals(
            LongTaskMagnitude.LONG,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(0, 25, 0L, 0))
        )
        assertEquals(
            LongTaskMagnitude.LONG,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(0, 0, 300_000L, 0))
        )
        assertEquals(
            LongTaskMagnitude.LONG,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(0, 0, 0L, 8))
        )
        // 单维达标 EPIC
        assertEquals(
            LongTaskMagnitude.EPIC,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(25, 0, 0L, 0))
        )
        assertEquals(
            LongTaskMagnitude.EPIC,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(0, 40, 0L, 0))
        )
        assertEquals(
            LongTaskMagnitude.EPIC,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(0, 0, 600_000L, 0))
        )
        assertEquals(
            LongTaskMagnitude.EPIC,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(0, 0, 0L, 15))
        )
        // 最高档不被低维信号稀释：EPIC 迭代数 + 全零其余维
        assertEquals(
            LongTaskMagnitude.EPIC,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(25, 0, 0L, 0))
        )
        // 批量快改型：2 迭代但 15 个文件 → EPIC
        assertEquals(
            LongTaskMagnitude.EPIC,
            LongTaskMagnitude.fromSignals(LongTaskDetector.Signals(2, 0, 0L, 15))
        )
    }

    /** Detector 门面与阶梯本体永远一致：isLongTask 当且仅当 magnitude 非空。 */
    @Test
    fun `isLongTask与magnitude永远一致`() {
        val samples = listOf(
            LongTaskDetector.Signals(0, 0, 0L, 0),
            LongTaskDetector.Signals(7, 11, 119_999L, 2),
            LongTaskDetector.Signals(8, 12, 120_000L, 3),
            LongTaskDetector.Signals(15, 25, 300_000L, 8),
            LongTaskDetector.Signals(25, 40, 600_000L, 15),
            LongTaskDetector.Signals(70, 140, 700_000L, 12),
            LongTaskDetector.Signals(2, 3, 4_000L, 1)
        )
        samples.forEach { signals ->
            assertEquals(
                "isLongTask 必须与 magnitude 判定一致: $signals",
                LongTaskDetector.magnitude(signals) != null,
                LongTaskDetector.isLongTask(signals)
            )
        }
    }
}
