package com.apex.agent.core.engine.promptvars

/**
 * ═══ 提示词变量引擎 · 数据模型（4-b）═══
 *
 * 学习自 RikkaHub 的 PlaceholderTransformer（内置 12 个变量 + 扩展点
 * PlaceholderProvider）与 Operit 的 prompt 全阶段钩子体系，落地为
 * apex-agent 的纯 JVM 提示词变量层：
 *
 * ```
 * PromptVariableContext   一次求值的不可变上下文（设备/会话/模型快照）
 * PromptVariableDef       变量元数据（名称/描述/作用域/示例）→ 设置页可渲染
 * PromptVariableRegistry  内置 19 变量 + 用户自定义变量（会话级注册）
 * PromptVariableExpander  {{name}} 与 {{name|default}} 语法展开器
 * ```
 *
 * **纯 JVM 纪律**：本包零 Android 依赖——设备型号、电量、网络等运行时
 * 数据由 app 层组装成 [PromptVariableContext] 字符串快照后注入（防腐：
 * core 不感知 Build.BRAND 或 BatteryManager，镜像 RikkaHub 的
 * PlaceholderCtx 但剥离 Android Context 依赖）。
 *
 * 挂点（见 worklog 2-c）：AgentChatViewModel.sendMessage 发送前展开
 * 提示词变量；AgentConfig.additionalSystemContext 组装点注入会话级变量。
 */

/**
 * 变量作用域——决定设置页分组与生命周期：
 *
 * - [SYSTEM]：环境快照（时间/时区/平台/设备/网络/电量），随环境变化；
 * - [SESSION]：当前会话数据（session_id/模式/思考档位/模型/工具数）；
 * - [USER]：用户配置的人设字段（agent 称呼、用户称呼）。
 */
enum class VariableScope {
    SYSTEM,
    SESSION,
    USER
}

/**
 * 变量元数据（镜像 RikkaHub 的 PlaceholderInfo——其 displayName 是
 * @Composable 以便设置页渲染本地化变量名；core 层剥离 Compose，改为
 * 携带 description 与 example 字符串，UI 层自行本地化）。
 *
 * @param name 规范名（小写，匹配 [a-z][a-z0-9_] 语法）
 * @param description 用途描述（设置页变量清单展示）
 * @param scope 作用域（SYSTEM / SESSION / USER）
 * @param isBuiltIn true = 内置变量（不可注销）；false = 用户自定义
 * @param example 示例值（帮助用户理解变量含义）
 */
data class PromptVariableDef(
    val name: String,
    val description: String,
    val scope: VariableScope,
    val isBuiltIn: Boolean = false,
    val example: String = ""
)

/**
 * 一次变量求值的不可变上下文。
 *
 * 所有字段都是纯 JVM 数据（字符串/整数/时钟函数）——设备与系统信息由
 * app 层在组装时拍平注入，core 层不做任何 Android 调用。时间类变量
 * （time / date / datetime / weekday）从 [nowMs] 取值：注入假钟即可
 * 确定性测试，注入 System.currentTimeMillis 即真实时钟。
 *
 * **变量优先级链**（由 [PromptVariableRegistry.resolveOrNull] 实现）：
 *
 * ```
 * context.custom  >  registry 注册的自定义变量  >  内置变量
 * ```
 *
 * [custom] 是「本次求值」的临时覆盖（模板引擎用它注入 overrides 与
 * 模板默认值，见 templates 包），因此优先级必须高于注册表内持久变量。
 *
 * @param nowMs 时钟函数（假钟可注入；默认占位返回 0）
 * @param modelId 当前模型 ID（如 gemini-2.0-flash）
 * @param modelName 当前模型展示名
 * @param agentName agent 自称（空串回退 "Apex Agent"，对齐 AgentConfig 默认）
 * @param userTitle agent 对用户的称呼（空串回退 "user"，对齐 RikkaHub nickname）
 * @param modeName 当前执行模式名（BUILD / PLAN / ...）
 * @param thinkingLevelName 当前思考档位名（STANDARD / DEEP / ...）
 * @param toolCount 本轮暴露的工具数量
 * @param sessionId 会话 ID
 * @param locale 语言区域标签（如 zh-CN）
 * @param timeZoneId 时区 ID（如 Asia/Shanghai；时间类变量按此格式化）
 * @param platform 平台名（如 Android）
 * @param deviceSummary 设备摘要（app 层注入，如 "Pixel 9 Pro · Android 15"）
 * @param networkSummary 网络摘要（如 "Wi-Fi"）
 * @param batterySummary 电量摘要（如 "85%"）
 * @param custom 本次求值的临时变量覆盖（最高优先级）
 */
data class PromptVariableContext(
    val nowMs: () -> Long = { 0L },
    val modelId: String = "",
    val modelName: String = "",
    val agentName: String = "",
    val userTitle: String = "",
    val modeName: String = "",
    val thinkingLevelName: String = "",
    val toolCount: Int = 0,
    val sessionId: String = "",
    val locale: String = "",
    val timeZoneId: String = "",
    val platform: String = "",
    val deviceSummary: String = "",
    val networkSummary: String = "",
    val batterySummary: String = "",
    val custom: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * 测试与预览用的占位上下文：全字段非空的确定性快照。
         * 时间类变量按 epoch 0（1970-01-01 周四，UTC）解析。
         */
        val DEFAULT = PromptVariableContext(
            nowMs = { 0L },
            modelId = "gemini-2.0-flash",
            modelName = "Gemini 2.0 Flash",
            agentName = "Apex Agent",
            userTitle = "Boss",
            modeName = "BUILD",
            thinkingLevelName = "STANDARD",
            toolCount = 12,
            sessionId = "session-test",
            locale = "zh-CN",
            timeZoneId = "UTC",
            platform = "Android",
            deviceSummary = "Pixel 9 Pro",
            networkSummary = "Wi-Fi",
            batterySummary = "85%"
        )
    }
}
