package com.apex.agent.core.code.subagent

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema

/**
 * # code_task — 子代理任务委派工具（Issue #147，opencode 风格 task 工具）
 *
 * 主代理把探索 / 调研类工作委派给**隔离上下文**的子代理：
 * - 子代理由 [SubAgentRunner] 驱动，在全新引擎实例里跑完整循环，结论作为
 *   本工具结果返回——中间过程不进入主对话历史，长探索不再撑爆主上下文；
 * - 三种类型（subagent_type）对应三套提示词与工具集：
 *   explore（默认，只读代码探索，返回带 path:line 引用的结论）、
 *   research（联网调研，返回带 URL 来源的结论）、general（默认 CORE 工具集）；
 * - 输出格式：统计行（迭代 / 工具调用 / 耗时）+ 空行 + 结论文本，让主代理
 *   一眼看到成本与结论。
 *
 * 与 Code 模式六工具（code_read 等）同属 coding 能力面，但注册与入
 * ToolTierPolicy CORE 集的接线由主控统一处理（本文件只交付工具本体）。
 */
class CodeTaskTool(
    private val runner: SubAgentRunner
) : BaseTool(
    id = "code_task",
    name = "code_task",
    description = """
        把探索 / 调研类任务委派给一个隔离上下文的子代理执行：子代理独立运行
        （有自己的工具集与迭代预算），跑完把结论作为本工具结果返回，不占用
        主对话历史。适合"找出所有用到 X 的地方"、"梳理某模块的调用链"、
        "调研某个库的最新用法"等可以先攒结论再动手的工作。

        子代理类型（subagent_type）：
        - explore（默认）：只读代码探索，返回带 path:line 引用的结构化结论；
        - research：联网调研，返回带 URL 来源的结论；
        - general：通用执行（默认 CORE 工具集）。

        description 给一句话任务描述；prompt 给完整任务指令——子代理看不到
        主对话历史，指令必须自包含（目标、范围、期望的结论格式）。
        子代理预算：最多 15 轮迭代 / 5 分钟，并发上限 3 个。
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "description",
            required = true,
            description = "一句话任务描述（用于日志与结果标题），例如：找出所有调用 loginUser() 的位置"
        )
        string(
            "prompt",
            required = true,
            description = "给子代理的完整任务指令：目标、范围、期望的结论格式。子代理看不到主对话历史，指令必须自包含"
        )
        string(
            "subagent_type",
            description = "子代理类型：explore=只读代码探索（默认）/ research=联网调研 / general=通用执行",
            enumValues = listOf("explore", "research", "general"),
            defaultValue = "explore"
        )
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.AGENT)
        risk(ToolRisk.MEDIUM)
        tag("subagent")
        tag("task")
        tag("delegate")
        tag("code")
        // 子代理可能触网（research）或经 general 类型落盘 → 非只读、开放世界
        annotations(
            ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = true
            )
        )
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val description = args.requireString("description")
        val prompt = args.requireString("prompt")
        val typeKey = args.stringWithDefault(
            "subagent_type",
            SubAgentRunner.SubAgentType.EXPLORE.key
        )
        val type = SubAgentRunner.SubAgentType.fromKey(typeKey)
            ?: return ToolResult.invalid(
                field = "subagent_type",
                message = "未知的子代理类型 '$typeKey'",
                suggestion = "可选值：explore / research / general"
            )

        return runner.run(type, description, prompt).fold(
            onSuccess = { ToolResult.ok(formatSuccess(type, it)) },
            onFailure = {
                ToolResult.fail(
                    ToolErrorCode.EXECUTION_FAILED,
                    "子代理执行失败：${it.message ?: "未知错误"}"
                )
            }
        )
    }

    // ── 输出格式化 ─────────────────────────────────────────────────

    /**
     * 成功输出：统计行 + 空行 + 结论。
     * 统计行固定形态「子代理 explore 完成：N 轮迭代 / M 次工具调用 / 耗时 Xs」，
     * 让主代理（与用户）一眼看到这次委派的成本。
     */
    private fun formatSuccess(
        type: SubAgentRunner.SubAgentType,
        result: SubAgentRunner.SubAgentResult
    ): String = buildString {
        append("子代理 ").append(type.key).append(" 完成：")
        append(result.iterations).append(" 轮迭代 / ")
        append(result.toolCalls).append(" 次工具调用 / 耗时 ")
        append("%.1f".format(result.durationMs / 1000.0)).append("s")
        if (result.truncated) {
            append("（结果被截断：超时或超长）")
        }
        appendLine()
        appendLine()
        append(result.output)
    }
}
