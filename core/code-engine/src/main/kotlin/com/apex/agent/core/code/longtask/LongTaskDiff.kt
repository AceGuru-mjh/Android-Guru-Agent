package com.apex.agent.core.code.longtask

/**
 * # 运行对比——「同一目标的两次尝试，差在哪」
 *
 * ## 使用场景
 *
 * 复制任务的核心价值闭环：跑完一次 → 复制（可换档位）再跑 → **对比**。
 * 典型问题：「ULTRACODE 档比 DEEP 档快了还是慢了？改动集收敛了还是
 * 发散了？上次失败的文件这次过了吗？」——[compare] 给结构化答案，
 * [renderText] 给 UI 直渲的中文可读块。
 *
 * ## 对比口径
 *
 * - **文件集合语义**：filesTouched 视为集合——仅 A（A 独有）/ 仅 B /
 *   交集计数（filesInBoth）。路径为记录原样字符串（相对工作区根），
 *   不做规范化（两侧都来自同构的 path 提取，口径天然一致）；
 * - **数值直传**：迭代 / 工具调用 / 时长原样进结构（差值由
 *   [renderText] 呈现，不进结构——diff 是数据，渲染才带箭头）；
 * - **不做任何评判**：哪次「更好」留给用户判断（改动集小不一定是好——
 *   可能是没做完）。
 *
 * 纯 object、纯函数：无状态无 IO，天然可测。
 */
object LongTaskDiff {

    /**
     * 两次运行的结构化差异。
     *
     * @property filesOnlyInA 仅 A 触碰的文件（字典序）。
     * @property filesOnlyInB 仅 B 触碰的文件（字典序）。
     * @property filesInBoth 两侧共同触碰的文件数（只计数——交集清单
     *   对 UI 是长列表噪音，需要时由调用方自行算）。
     * @property iterationsA / iterationsB 迭代数。
     * @property toolCallsA / toolCallsB 工具调用数。
     * @property durationMsA / durationMsB 运行时长（毫秒）。
     * @property statusA / statusB 最终状态。
     * @property thinkingA / thinkingB 思考档位名。
     */
    data class RunDiff(
        val filesOnlyInA: List<String>,
        val filesOnlyInB: List<String>,
        val filesInBoth: Int,
        val iterationsA: Int,
        val iterationsB: Int,
        val toolCallsA: Int,
        val toolCallsB: Int,
        val durationMsA: Long,
        val durationMsB: Long,
        val statusA: LongTaskStatus,
        val statusB: LongTaskStatus,
        val thinkingA: String,
        val thinkingB: String
    )

    /**
     * 对比两次运行（纯函数；A/B 顺序只影响方向性字段的归属，不影响语义）。
     */
    fun compare(a: LongTaskRecord, b: LongTaskRecord): RunDiff {
        val filesA = a.filesTouched.toSet()
        val filesB = b.filesTouched.toSet()
        return RunDiff(
            filesOnlyInA = (filesA - filesB).sorted(),
            filesOnlyInB = (filesB - filesA).sorted(),
            filesInBoth = (filesA intersect filesB).size,
            iterationsA = a.iterations,
            iterationsB = b.iterations,
            toolCallsA = a.toolCalls,
            toolCallsB = b.toolCalls,
            durationMsA = a.durationMs,
            durationMsB = b.durationMs,
            statusA = a.status,
            statusB = b.status,
            thinkingA = a.thinkingLevel,
            thinkingB = b.thinkingLevel
        )
    }

    /**
     * 渲染中文可读对比块（UI 直显文本——复制运行对比的呈现终点）。
     *
     * 形态：
     * ```
     * 【运行对比】<titleA> vs <titleB>
     * 思考档位：  DEEP → ULTRACODE
     * 状态：      FAILED → COMPLETED
     * 迭代次数：  25 → 18（-7）
     * 工具调用：  40 → 31（-9）
     * 耗时：      6分12秒 → 4分3秒（-2分9秒）
     * 共同改动：12 个文件
     * 仅 A 改动（3 个）：
     *   - path/a.kt
     * 仅 B 改动（5 个）：
     *   - path/b.kt
     * ```
     *
     * 文件清单各侧至多列 [RENDER_FILES_MAX]（20）条，超出聚合计数（防超长
     * 改动集刷屏）。数值差值为 0 时渲染「持平」。
     */
    fun renderText(d: RunDiff, titleA: String, titleB: String): String {
        return buildString {
            append("【运行对比】")
            append(titleA)
            append(" vs ")
            append(titleB)
            append('\n')
            appendLabeled("思考档位", d.thinkingA, d.thinkingB, delta = null)
            appendLabeled("状态", d.statusA.name, d.statusB.name, delta = null)
            appendLabeled("迭代次数", d.iterationsA.toString(), d.iterationsB.toString(),
                delta = signedDelta(d.iterationsB - d.iterationsA))
            appendLabeled("工具调用", d.toolCallsA.toString(), d.toolCallsB.toString(),
                delta = signedDelta(d.toolCallsB - d.toolCallsA))
            appendLabeled("耗时", formatDuration(d.durationMsA), formatDuration(d.durationMsB),
                delta = signedDurationDelta(d.durationMsB - d.durationMsA))
            append("共同改动：")
            append(d.filesInBoth)
            append(" 个文件\n")
            appendFileSection("仅 A 改动", d.filesOnlyInA)
            appendFileSection("仅 B 改动", d.filesOnlyInB)
        }.trimEnd('\n')
    }

    /**
     * 中文时长格式化（[TaskCopyEngine.buildContextDigest] 与本渲染共用）：
     * <1min 秒级；<1h 分秒；≥1h 时分。
     */
    internal fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return when {
            totalSeconds < 60 -> "${totalSeconds}秒"
            totalSeconds < 3600 -> {
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                "${minutes}分${seconds}秒"
            }
            else -> {
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                "${hours}小时${minutes}分"
            }
        }
    }

    // ═══════════════════════ 渲染内部 ═══════════════════════

    /** 「标签：A → B（差）」行；delta 为 null 表示非数值维度不比差。 */
    private fun StringBuilder.appendLabeled(
        label: String,
        valueA: String,
        valueB: String,
        delta: String?
    ) {
        append(label)
        append("：")
        append(valueA)
        append(" → ")
        append(valueB)
        if (delta != null) {
            append("（")
            append(delta)
            append("）")
        }
        append('\n')
    }

    /** 单侧文件清单段：空清单省略整段；超上限聚合计数。 */
    private fun StringBuilder.appendFileSection(label: String, files: List<String>) {
        if (files.isEmpty()) return
        append(label)
        append("（")
        append(files.size)
        append(" 个）：\n")
        for (file in files.take(RENDER_FILES_MAX)) {
            append("  - ")
            append(file)
            append('\n')
        }
        if (files.size > RENDER_FILES_MAX) {
            append("  - …等共 ")
            append(files.size)
            append(" 个\n")
        }
    }

    /** 整数差值：0 → 持平；正 → +n；负 → −n（真减号，视觉对齐）。 */
    private fun signedDelta(delta: Int): String = when {
        delta == 0 -> "持平"
        delta > 0 -> "+$delta"
        else -> "−${-delta}"
    }

    /** 时长差值：0 → 持平；其余交给 [formatDuration]（带符号）。 */
    private fun signedDurationDelta(deltaMs: Long): String = when {
        deltaMs == 0L -> "持平"
        deltaMs > 0 -> "+${formatDuration(deltaMs)}"
        else -> "−${formatDuration(-deltaMs)}"
    }

    private const val RENDER_FILES_MAX = 20
}
