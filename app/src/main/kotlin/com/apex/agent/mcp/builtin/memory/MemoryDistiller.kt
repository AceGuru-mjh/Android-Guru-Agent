package com.apex.agent.mcp.builtin.memory

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemoryTurn
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ═══════════════════════════════════════════════════════════════
 *  批量记忆蒸馏器 —— 写入门禁 + 结构化提取协议
 * ═══════════════════════════════════════════════════════════════
 *
 * v1 的蒸馏是「单轮即时提取」：每 4 轮把当前一轮（用户 1200 字 + 助手
 * 800 字）发给便宜模型抽 ≤5 条事实。三个结构性缺陷：
 *
 *  1. **样本太薄**：单轮对话里的自我披露信号常被拆在两轮里（先说在学
 *     什么、再说为什么学），单轮提取只见半张图；
 *  2. **没有写入门禁**：提示词只说「提取稳定信息」，常识问答、一次性
 *     任务细节、未来计划都会被记进去，画像库被噪音稀释；
 *  3. **只有追加没有更新**：事实演进（「在学 React」→「React 学完了，
 *     在学 Rust」）只能靠内容去重硬扛，同主题事实反复新增直到把重要的
 *     挤出召回窗口。
 *
 * v2 的蒸馏协议：
 *
 *  - **批量窗口**：吃整个待蒸馏队列（多轮上下文），一次调用提取整窗；
 *  - **写入门禁**：显式负面清单（常识 / 定义 / 一次性任务 / 未来计划 /
 *     助手发言），无长期价值信号时输出 skip 而非硬凑；
 *  - **更新优先**：把账本当前 Top 事实注入提示词作参照，同主题且有演进
 *     的事实要求输出 update（原文精确匹配 + 完整替换内容）而非 new；
 *     new 限额 3 条，从源头抑制碎片化；
 *  - **防御式解析**：截取首个 { 到最后一个 }，逐字段形状校验，任何
 *     异常折叠为 null（蒸馏失败只是少记一次，绝无副作用）。
 *
 * 提示词与解析是纯函数（[buildPrompt] / [parseOutcome]），JVM 直测覆盖。
 */
@Singleton
class MemoryDistiller @Inject constructor(
    private val modelRuntime: ModelRuntime
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 结构化提取结果：新增事实 / 既有事实更新 / 本窗无长期价值。 */
    data class DistillOutcome(
        val newFacts: List<String>,
        val updates: List<MemoryUpdate>,
        val skipped: Boolean
    ) {
        companion object {
            internal val SKIP = DistillOutcome(emptyList(), emptyList(), skipped = true)
        }
    }

    /** 一条事实更新：old = 账本中的原文（精确），content = 替换后的完整内容。 */
    data class MemoryUpdate(val oldContent: String, val newContent: String)

    /**
     * 对整个待蒸馏窗口执行一次批量提取（SUMMARY 角色路由到便宜模型）。
     * 任何失败（网络 / 形状异常）返回 null —— 调用方保留队列下次重试。
     */
    suspend fun distill(
        window: List<MemoryTurn>,
        existingFacts: List<String>
    ): DistillOutcome? {
        if (window.isEmpty()) return DistillOutcome(emptyList(), emptyList(), skipped = true)
        return runCatching {
            val prompt = buildPrompt(window, existingFacts)
            val response: LlmResponse = modelRuntime.chat(
                context = LlmRequestContext.summary("chat_memory_distill"),
                messages = listOf(
                    LlmMessage.System("你是对话事实提取器，只输出合法 JSON。"),
                    LlmMessage.User(prompt)
                ),
                temperature = 0.1f,
                maxTokens = 700
            )
            parseOutcome(response.content.orEmpty())
        }.getOrNull()
    }

    /** 组装批量提取提示词（写入门禁 + 参照事实 + 对话窗口）。 */
    internal fun buildPrompt(
        window: List<MemoryTurn>,
        existingFacts: List<String>
    ): String = buildString {
        appendLine("从下面整个对话窗口中提取「值得长期记住的用户事实」。")
        appendLine()
        appendLine("【写入门槛 —— 先过筛，再提取】")
        appendLine("- 只记录用户特异且可复用的稳定信息：身份、职业、偏好、长期目标、约束、常用工具、重要的人际关系；")
        appendLine("- 不记录：常识或公开定义（比如某技术是什么）、一次性任务细节、未来计划或 TODO、助手说过的话；")
        appendLine("- 窗口里没有长期价值信号时，输出 {\"skip\": true}，不要硬凑。")
        appendLine()
        appendLine("【更新优先于新增】")
        appendLine("- 「已有记忆」列表是当前账本：新事实若与某条同主题且信息有演进，输出 update（old 给出该条原文，content 给出替换后的完整内容），不要 new；")
        appendLine("- 只有账本里完全没有的全新概念才输出 new，最多 3 条，每条 ≤ 40 字，第三人称（以「用户」开头）；")
        appendLine("- old 必须逐字复制「已有记忆」里的某一条，不允许改写。")
        appendLine()
        appendLine("【输出格式 —— 严格 JSON，不要任何其他文字或代码块标记】")
        appendLine("{\"skip\": true}")
        appendLine("或")
        appendLine("{\"new\": [\"…\", \"…\"], \"update\": [{\"old\": \"已有记忆原文\", \"content\": \"替换后的完整内容\"}]}")
        if (existingFacts.isNotEmpty()) {
            appendLine()
            appendLine("【已有记忆】")
            existingFacts.take(MAX_EXISTING_FACTS).forEach { fact ->
                appendLine("- ${fact.take(MAX_FACT_DISPLAY_LENGTH)}")
            }
        }
        appendLine()
        appendLine("【对话窗口（共 ${window.size} 轮）】")
        window.takeLast(MAX_WINDOW_TURNS).forEachIndexed { index, turn ->
            appendLine("#${index + 1} 用户：${turn.userText.take(MAX_TURN_DISPLAY_LENGTH)}")
            appendLine("   助手：${turn.assistantText.take(MAX_ASSISTANT_DISPLAY_LENGTH)}")
        }
    }

    /**
     * 防御式解析：截取方括号包围区段内的 JSON 对象，逐字段形状校验。
     * skip=true 或无有效内容 → SKIP；解析失败 → null。
     */
    internal fun parseOutcome(raw: String): DistillOutcome? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val root = json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
            if (root["skip"]?.jsonPrimitive?.content == "true") return DistillOutcome.SKIP

            val newFacts = root["new"]?.jsonArray
                ?.mapNotNull { el ->
                    runCatching { el.jsonPrimitive.content }.getOrNull()
                        ?.trim()
                        ?.takeIf { it.length in 2..60 }
                }
                ?.distinct()
                ?.take(MAX_NEW_FACTS)
                .orEmpty()

            val updates = root["update"]?.jsonArray
                ?.mapNotNull { el ->
                    runCatching {
                        val obj = el.jsonObject
                        val old = obj["old"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val content = obj["content"]?.jsonPrimitive?.content?.trim().orEmpty()
                        if (old.length in 2..MAX_CONTENT_LENGTH &&
                            content.length in 2..MAX_CONTENT_LENGTH
                        ) MemoryUpdate(old, content) else null
                    }.getOrNull()
                }
                ?.take(MAX_UPDATES)
                .orEmpty()

            if (newFacts.isEmpty() && updates.isEmpty()) {
                // 空输出按 skip 处理（门禁放行但无长期信号）
                DistillOutcome.SKIP
            } else {
                DistillOutcome(newFacts, updates, skipped = false)
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_NEW_FACTS = 3
        const val MAX_UPDATES = 4
        const val MAX_EXISTING_FACTS = 12
        const val MAX_FACT_DISPLAY_LENGTH = 60
        const val MAX_WINDOW_TURNS = 12
        const val MAX_TURN_DISPLAY_LENGTH = 600
        const val MAX_ASSISTANT_DISPLAY_LENGTH = 400
        const val MAX_CONTENT_LENGTH = 120
    }
}
