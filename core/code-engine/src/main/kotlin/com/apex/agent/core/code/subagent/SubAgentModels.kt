package com.apex.agent.core.code.subagent

import kotlinx.serialization.Serializable

/**
 * # 子代理设置模型（v3 全面完善）
 *
 * 此前子代理全部预算硬编码（3 并发 / 300s / 15 轮 / 8K 输出，封闭三类型），
 * 用户零可配置。v3 起预算走设置层（AgentSettings.subagent 快照经 DI 注入
 * [SubAgentRunner] / StandardSubAgentDispatcher），并开放自定义子代理类型。
 */
@Serializable
data class SubAgentSettings(
    /** 总开关：关闭后 code_task 返回引导文案（主代理改为亲自执行探索）。 */
    val enabled: Boolean = true,
    /** 并发上限：同时在跑的子代理数（1..8）。 */
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    /** 整体超时毫秒（含排队；30s..15min）。 */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** 迭代上限（5..30 轮）。 */
    val maxTurns: Int = DEFAULT_MAX_TURNS,
    /** 单次工具输出上限字符（1000..16000）。 */
    val toolOutputLimit: Int = DEFAULT_TOOL_OUTPUT_LIMIT,
    /** 返回主代理的结论长度上限字符（1000..32000）。 */
    val outputLimit: Int = DEFAULT_OUTPUT_LIMIT,
    /** 上下文窗口预算 tokens。 */
    val contextBudget: Int = DEFAULT_CONTEXT_BUDGET,
    /** 采样温度（探索/调研求稳定收敛，默认低温）。 */
    val temperature: Float = DEFAULT_TEMPERATURE,
    /** 用户自定义子代理类型（按名解析，内置四类型之外）。 */
    val customTypes: List<CustomSubAgent> = emptyList(),
) {
    companion object {
        const val DEFAULT_MAX_CONCURRENT = 3
        const val DEFAULT_TIMEOUT_MS = 300_000L
        const val DEFAULT_MAX_TURNS = 15
        const val DEFAULT_TOOL_OUTPUT_LIMIT = 4000
        const val DEFAULT_OUTPUT_LIMIT = 8000
        const val DEFAULT_CONTEXT_BUDGET = 64_000
        const val DEFAULT_TEMPERATURE = 0.3f

        /** 设置层写入前的钳制（防御式：任何形状的输入都收敛到合法区间）。 */
        fun sanitized(
            maxConcurrent: Int,
            timeoutMs: Long,
            maxTurns: Int,
            toolOutputLimit: Int,
            outputLimit: Int,
            contextBudget: Int,
            temperature: Float,
        ) = SubAgentSettings(
            maxConcurrent = maxConcurrent.coerceIn(1, 8),
            timeoutMs = timeoutMs.coerceIn(30_000L, 900_000L),
            maxTurns = maxTurns.coerceIn(5, 30),
            toolOutputLimit = toolOutputLimit.coerceIn(1_000, 16_000),
            outputLimit = outputLimit.coerceIn(1_000, 32_000),
            contextBudget = contextBudget.coerceIn(16_000, 256_000),
            temperature = temperature.coerceIn(0f, 2f),
        )
    }

    /** 归一化（反序列化旧值/手改 JSON 后收敛到合法区间）。 */
    fun sanitized(): SubAgentSettings = copy(
        maxConcurrent = maxConcurrent.coerceIn(1, 8),
        timeoutMs = timeoutMs.coerceIn(30_000L, 900_000L),
        maxTurns = maxTurns.coerceIn(5, 30),
        toolOutputLimit = toolOutputLimit.coerceIn(1_000, 16_000),
        outputLimit = outputLimit.coerceIn(1_000, 32_000),
        contextBudget = contextBudget.coerceIn(16_000, 256_000),
        temperature = temperature.coerceIn(0f, 2f),
    )
}

/**
 * 用户自定义子代理类型：角色提示词 + 工具白名单 + 预算覆盖。
 *
 * @param key 调用标识（code_task 的 custom_name 参数按它解析；小写字母
 *   数字连字符，内置 key 保留：explore/research/general/reviewer）
 * @param displayName 中文展示名
 * @param description 一句话职责（code_task 描述与选择引导）
 * @param systemPrompt 角色系统提示词（拼在公共纪律之前）
 * @param toolIds 工具白名单（空集 = 默认 CORE 计划）
 * @param maxTurns 覆盖迭代上限（0 = 用全局默认）
 */
@Serializable
data class CustomSubAgent(
    val key: String,
    val displayName: String,
    val description: String = "",
    val systemPrompt: String = "",
    val toolIds: List<String> = emptyList(),
    val maxTurns: Int = 0,
) {
    companion object {
        /** key 归一：小写、空格→连字符、剔除非法字符、保留字拒绝。 */
        val RESERVED_KEYS = setOf("explore", "research", "general", "reviewer")

        fun normalizeKey(raw: String): String =
            raw.trim().lowercase().replace(' ', '-').filter { it.isLetterOrDigit() || it == '-' }
    }
}
