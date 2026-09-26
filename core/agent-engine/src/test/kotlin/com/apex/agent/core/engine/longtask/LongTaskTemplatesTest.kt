package com.apex.agent.core.engine.longtask

import com.apex.agent.core.engine.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置长任务模板（[LongTaskTemplates]）的结构与实例化契约测试。
 *
 * 模板是「起点菜单」：8 个内置模板（顺序即 UI 菜单顺序）、每个模板的
 * 推荐档位必须是合法 [ThinkingLevel] 名（v1.2 七级思考系统的字符串快照）、
 * goal 模板含百分之 s 占位符、todo 骨架 3-6 条可直接渲染。
 *
 * instantiate 的核心契约：**稳定 id**（template-前缀 + key + workspaceId，
 * 同工作区重复实例化幂等覆盖不堆积）、isTemplate=true、status=RUNNING、
 * goal 的占位符用 String.replace 全部替换为工作区名（而非 String.format，
 * 工作区名含百分号也安全）、todo 骨架随记录入库成为 todoSnapshot。
 *
 * 纯 JVM（JUnit4）——Templates 是无状态 object，无协程无磁盘。
 */
class LongTaskTemplatesTest {

    /** 8 个内置模板的 key 期望表（顺序即 UI 菜单顺序：从最常用到最专业）。 */
    private val expectedKeys = listOf(
        "refactor", "bugfix", "feature", "code-review",
        "test-gen", "docs-gen", "perf-opt", "migrate"
    )

    /** 推荐档位期望表（v1.2 七级思考系统下的字符串快照）。 */
    private val expectedLevels = mapOf(
        "refactor" to "DEEP",
        "bugfix" to "STANDARD",
        "feature" to "DEEP",
        "code-review" to "ULTRACODE",
        "test-gen" to "STANDARD",
        "docs-gen" to "LIGHT",
        "perf-opt" to "ULTRACODE",
        "migrate" to "MAXIMUM"
    )

    // ═════════════════ 菜单结构 ═════════════════

    /**
     * ALL 恰好 8 个模板、key 唯一且顺序稳定（顺序即 UI 菜单顺序——
     * 顺序变化是用户可见的行为变化，必须显式改期望表）。
     */
    @Test
    fun `模板总量恰好八个_key唯一且顺序稳定`() {
        assertEquals(8, LongTaskTemplates.ALL.size)
        assertEquals(expectedKeys, LongTaskTemplates.ALL.map { it.key })
        // key 唯一性（大小敏感）
        assertEquals(expectedKeys.size, LongTaskTemplates.ALL.map { it.key }.toSet().size)
    }

    /**
     * 每个模板的推荐档位都是合法 [ThinkingLevel] 枚举名（UI 切档时按名字
     * 反查枚举），且 APEXCODE 不出现在任何推荐位——巅峰档只允许用户显式
     * 指定，模板推荐也不能替用户拉满成本（与 AdaptiveThinkingSelector 的
     * 成本防线同一哲学）。
     */
    @Test
    fun `每个模板推荐档位都是合法思考档名_且不含APEXCODE`() {
        val legalNames = ThinkingLevel.entries.map { it.name }.toSet()
        LongTaskTemplates.ALL.forEach { template ->
            assertTrue(
                "模板 ${template.key} 的推荐档位 ${template.recommendedThinkingLevel} 必须是合法 ThinkingLevel 名",
                template.recommendedThinkingLevel in legalNames
            )
            assertFalse(
                "模板 ${template.key} 不应推荐 APEXCODE（巅峰档仅用户显式指定）",
                template.recommendedThinkingLevel == "APEXCODE"
            )
        }
    }

    /** 特定模板的推荐档位与 v1.2 设计对照（8 个全表逐一锁定）。 */
    @Test
    fun `特定模板推荐档位与设计全表一致`() {
        LongTaskTemplates.ALL.forEach { template ->
            assertEquals(
                "模板 ${template.key} 推荐档位漂移",
                expectedLevels[template.key],
                template.recommendedThinkingLevel
            )
        }
        // 期望表自身覆盖全部 key（防止 ALL 加了模板而期望表漏更新）
        assertEquals(expectedKeys.toSet(), expectedLevels.keys)
    }

    /**
     * 每个模板的 todo 骨架 3-6 条、每条非空行——VM 直接把它渲染成
     * todo 面板初稿，超量或空行都会破坏首屏观感。
     */
    @Test
    fun `每个模板todo骨架三到六条且非空行`() {
        LongTaskTemplates.ALL.forEach { template ->
            val skeleton = template.todoSkeleton
            assertTrue(
                "模板 ${template.key} 的 todo 骨架应在 3-6 条，实际 ${skeleton.size}",
                skeleton.size in 3..6
            )
            skeleton.forEach { line ->
                assertTrue(
                    "模板 ${template.key} 的 todo 骨架行不应为空白",
                    line.isNotBlank()
                )
            }
        }
    }

    /**
     * 每个模板的 goal 模板非空、含恰好可替换的百分之 s 占位符、
     * 中英标题非空、标签非空（UI 分组与筛选的最小素材）。
     */
    @Test
    fun `每个模板goal模板含占位符_标题标签齐备`() {
        LongTaskTemplates.ALL.forEach { template ->
            assertTrue("模板 ${template.key} 的 goal 模板不应为空", template.goalTemplate.isNotBlank())
            assertTrue(
                "模板 ${template.key} 的 goal 模板必须含百分之 s 占位符",
                template.goalTemplate.contains("%s")
            )
            assertTrue("模板 ${template.key} 的中文标题不应为空", template.titleZh.isNotBlank())
            assertTrue("模板 ${template.key} 的英文标题不应为空", template.titleEn.isNotBlank())
            assertTrue("模板 ${template.key} 的标签不应为空", template.tags.isNotEmpty())
        }
    }

    // ═════════════════ byKey 查询 ═════════════════

    /** byKey 命中全部 8 个 key；未知 key（外部输入）返回 null 不抛。 */
    @Test
    fun `byKey命中全部key_未知key返回null`() {
        expectedKeys.forEach { key ->
            val found = LongTaskTemplates.byKey(key)
            assertNotNull("byKey($key) 必须命中", found)
            assertEquals(key, found!!.key)
        }
        assertNull(LongTaskTemplates.byKey("no-such-template"))
        assertNull(LongTaskTemplates.byKey(""))
        assertNull(LongTaskTemplates.byKey("REFACTOR")) // 大小敏感
    }

    // ═════════════════ instantiate 实例化 ═════════════════

    /**
     * instantiate 全字段契约：稳定 id（template-refactor-工作区id）、
     * isTemplate=true、status=RUNNING、goal 占位符已替换、todo 骨架成为
     * todoSnapshot、推荐档位与 BUILD 模式、运行统计归零、createdAt 与
     * updatedAt 同刻。
     */
    @Test
    fun `instantiate稳定id与全字段语义`() {
        val template = LongTaskTemplates.byKey("refactor")!!
        val wsId = "ws-tpl-abc123"
        val wsName = "契约工作区"
        val record = LongTaskTemplates.instantiate(template, wsId, wsName)

        // 稳定 id：前缀 + key + 分隔 + 工作区 id
        assertEquals("template-refactor-$wsId", record.id)
        // 模板身份与起点形态
        assertTrue(record.isTemplate)
        assertEquals(LongTaskStatus.RUNNING, record.status)
        assertEquals("DEEP", record.thinkingLevel) // 推荐档位（用户可改）
        assertEquals("BUILD", record.agentMode) // 编码任务默认模式
        assertEquals(template.titleZh, record.title)
        assertEquals(wsId, record.workspaceId)
        assertEquals(wsName, record.workspaceName)
        // goal 已实例化：占位符替换为工作区名（replace 全量替换语义）
        assertEquals(template.goalTemplate.replace("%s", wsName), record.goal)
        assertTrue(record.goal.contains(wsName))
        assertFalse("替换后不应残留占位符", record.goal.contains("%s"))
        // todo 骨架随记录入库（VM 直接渲染成 todo 面板初稿）
        assertEquals(template.todoSkeleton, record.todoSnapshot)
        assertEquals(template.tags, record.tags)
        // 运行统计归零
        assertNull(record.endedAt)
        assertEquals(0, record.iterations)
        assertEquals(0, record.toolCalls)
        assertEquals(0L, record.durationMs)
        assertTrue(record.filesTouched.isEmpty())
        assertTrue(record.toolsUsed.isEmpty())
        assertTrue(record.checkpoints.isEmpty())
        assertNull(record.errorMessage)
        assertNull(record.summary)
        assertNull(record.parentTaskId)
        assertEquals(0, record.copyCount)
        // 同一 now 采样：创建与更新同刻
        assertEquals(record.createdAt, record.updatedAt)
    }

    /**
     * 同参数重复实例化：稳定 id 幂等（不堆积、upsert 覆盖语义的前提），
     * 语义字段等值（仅时间戳允许漂移——now 各自采样）；不同工作区各得
     * 其所（id 不同）；工作区名含百分号也安全（replace 而非 format）。
     */
    @Test
    fun `同参数重复实例化幂等_不同工作区各得其所_百分号工作区名安全`() {
        val template = LongTaskTemplates.byKey("code-review")!!

        // 同参数两次实例化：id 相同 + 语义字段等值
        val first = LongTaskTemplates.instantiate(template, "ws-x", "评审工作区")
        val second = LongTaskTemplates.instantiate(template, "ws-x", "评审工作区")
        assertEquals("稳定 id 是幂等覆盖语义的前提", first.id, second.id)
        assertEquals(
            first.copy(createdAt = second.createdAt, updatedAt = second.updatedAt),
            second
        )

        // 不同工作区：id 不同（模板菜单在每个工作区各有一份起点）
        val otherWs = LongTaskTemplates.instantiate(template, "ws-y", "评审工作区")
        assertFalse(first.id == otherWs.id)
        assertEquals("ws-y", otherWs.workspaceId)

        // 不同模板同工作区：id 也不同
        val otherTpl = LongTaskTemplates.instantiate(
            LongTaskTemplates.byKey("migrate")!!, "ws-x", "评审工作区"
        )
        assertFalse(first.id == otherTpl.id)

        // 工作区名含百分号：replace 只认字面百分之 s，工作区名里的百分号原样保留
        val pct = LongTaskTemplates.instantiate(template, "ws-pct", "100%覆盖工作区")
        assertEquals(template.goalTemplate.replace("%s", "100%覆盖工作区"), pct.goal)
        assertTrue(pct.goal.contains("100%覆盖工作区"))
    }

    /**
     * 全部 8 个模板都可实例化为合法记录（逐个过一遍 id 格式与
     * isTemplate，防止个别模板字段手误导致实例化路径翻车）。
     */
    @Test
    fun `全部八个模板均可实例化为合法模板记录`() {
        LongTaskTemplates.ALL.forEach { template ->
            val record = LongTaskTemplates.instantiate(
                template, "ws-all-0001", "全量工作区"
            )
            assertEquals("template-${template.key}-ws-all-0001", record.id)
            assertTrue(record.isTemplate)
            assertEquals(LongTaskStatus.RUNNING, record.status)
            assertEquals(template.recommendedThinkingLevel, record.thinkingLevel)
            assertEquals(template.todoSkeleton, record.todoSnapshot)
            assertFalse(record.goal.contains("%s"))
            assertTrue(record.goal.contains("全量工作区"))
        }
    }

    /**
     * goal 模板是方法论编排：首行以「（在/对/为）工作区 百分之 s」起兴，
     * 正文带分步编号（1. 起步、4. 收尾），总行数在 4-8 行——
     * 太短撑不起「高质量开场」，太长挤占用户补充具体需求的空间。
     */
    @Test
    fun `每个模板goal模板是方法论编排_首行含工作区占位且分步编号齐备`() {
        LongTaskTemplates.ALL.forEach { template ->
            val lines = template.goalTemplate.lineSequence()
                .filter { it.isNotBlank() }.toList()
            assertTrue(
                "模板 ${template.key} 的 goal 模板应在 4-8 行，实际 ${lines.size}",
                lines.size in 4..8
            )
            assertTrue(
                "模板 ${template.key} 的 goal 首行应含工作区占位符",
                lines.first().contains("%s")
            )
            assertTrue(
                "模板 ${template.key} 的 goal 应含步骤一编号",
                template.goalTemplate.contains("1.")
            )
            assertTrue(
                "模板 ${template.key} 的 goal 应含步骤四编号",
                template.goalTemplate.contains("4.")
            )
            // 开放式结构：具体需求留给用户本轮输入补充
            assertTrue(
                "模板 ${template.key} 的 goal 应声明以用户输入为准",
                template.goalTemplate.contains("以用户本轮")
            )
        }
    }

    /**
     * 模板记录 id 满足 [LongTaskStore] 的合法 id 约束（字母数字与
     * 中划线下划线）：template-前缀 + key + 分隔符 + workspaceId 三段
     * 天然合法，杜绝路径穿越读任意文件的防线在模板路径上也成立。
     */
    @Test
    fun `模板记录id满足store合法id约束`() {
        LongTaskTemplates.ALL.forEach { template ->
            val record = LongTaskTemplates.instantiate(template, "ws-ok-1", "任意工作区")
            assertTrue(
                "模板 ${template.key} 的 id 应只含字母数字与中划线下划线",
                record.id.matches(Regex("[a-zA-Z0-9_-]+"))
            )
            assertFalse(record.id.contains('.'))
            assertFalse(record.id.contains('/'))
            assertFalse(record.id.contains(' '))
        }
    }
}
