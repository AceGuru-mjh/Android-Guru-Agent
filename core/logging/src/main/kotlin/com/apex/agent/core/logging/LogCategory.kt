package com.apex.agent.core.logging

/**
 * 业务日志分类。
 *
 * 与 [LogLevel]（描述"多严重"）正交，本枚举描述"来自哪一类业务子系统"，
 * 是日志查看器进行分段、聚合统计、一键筛选的主维度。
 *
 * 覆盖应用内全部日志来源：引擎、模型适配、工具执行、UI 交互、网络请求、
 * 附件文件、系统生命周期、插件/技能。
 */
enum class LogCategory(
    val displayName: String,
    val shortCode: String
) {
    /** Agent 引擎：规划、迭代、上下文压缩、权限门控。 */
    ENGINE("引擎", "ENG"),

    /** 大模型调用：适配层、流式输出、token 统计。 */
    LLM("模型", "LLM"),

    /** 工具执行：builtin 工具、MCP、Skill、SafeAgentTool 包装层。 */
    TOOL("工具", "TOL"),

    /** 界面交互：屏幕导航、气泡渲染、用户操作。 */
    UI("界面", "UI"),

    /** 网络请求：GithubApi、Token 管理、外部 HTTP。 */
    NETWORK("网络", "NET"),

    /** 附件与文件：选择、上传、读取。 */
    ATTACHMENT("附件", "ATT"),

    /** 系统/生命周期：Application、后台服务、崩溃、全局异常。 */
    SYSTEM("系统", "SYS"),

    /** 插件与技能：plugin-sdk、插件加载、Skill 注册。 */
    PLUGIN("插件", "PLG"),

    /** CS-Mem 认知空间记忆：感知、差分、蒸馏、免疫隔离、梦境遗忘。 */
    CS_MEM("记忆", "MEM");

    companion object {
        /** 由短码反查（用于反序列化/外部接入）。找不到返回 [SYSTEM]。 */
        fun fromShortCode(code: String): LogCategory =
            entries.firstOrNull { it.shortCode == code } ?: SYSTEM
    }
}
