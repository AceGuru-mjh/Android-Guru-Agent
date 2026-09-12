package com.apex.agent.di

import com.apex.agent.core.engine.EnvironmentInfoProvider
import com.apex.agent.core.tools.ToolEnvironmentState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool System v3 — [EnvironmentInfoProvider] 的 app 层实现。
 *
 * 与 [AndroidPrivilegeInfoProvider] 同构：包装 core 层的
 * [ToolEnvironmentState] 单例（EnvironmentStateUpdater 灌入遥测），
 * 把同一份状态交给引擎的 system prompt —— prompt 侧的 Live Environment
 * 段和执行侧的 ToolEnvironmentGate 永远读同一个对象，不存在"模型看到的
 * 与门控执行的不一致"这类漂移。
 */
@Singleton
class AndroidEnvironmentInfoProvider @Inject constructor(
    private val environmentState: ToolEnvironmentState
) : EnvironmentInfoProvider {

    override fun environmentSummary(): String? {
        val snapshot = environmentState.snapshot()
        // 无任何遥测时返回 null —— prompt 省略该段（与门控 fail-open 对齐）。
        if (snapshot.isEmpty()) return null
        return environmentState.summary()
    }
}
