package com.apex.agent.core.engine

/**
 * 环境能力信息提供者（Tool System v3）
 *
 * 与 [PrivilegeInfoProvider] 同构的解耦点：agent-engine（纯 JVM）不能
 * 直接持有 app 层的 [com.apex.agent.core.tools.ToolEnvironmentState]
 * 单例生命周期，故由宿主实现本接口注入。
 *
 * 返回值进入 system prompt 的 Live Environment 段（每轮重建 prompt 时
 * 现取快照），与执行侧的 [com.apex.agent.core.tools.ToolEnvironmentGate]
 * 共享同一状态源 —— 模型看到的与门控执行的是同一份真值。
 *
 * 返回 null 表示宿主未接遥测 —— prompt 不渲染该段（与门控的
 * fail-open 语义对齐，零回归）。
 */
interface EnvironmentInfoProvider {

    /**
     * 当前环境能力快照的单行摘要。
     *
     * 形如 `environment: accessibility=on keyboard=on ubuntu=ready`；
     * 无遥测时返回 null。
     */
    fun environmentSummary(): String?
}
