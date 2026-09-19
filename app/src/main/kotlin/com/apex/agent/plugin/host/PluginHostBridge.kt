package com.apex.agent.plugin.host

import com.apex.agent.browser.BrowserAgentTools
import com.apex.agent.plugin.api.IApexPluginHost
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件宿主桥（[IApexPluginHost] 的宿主侧实现，AIDL 见 plugin-api）。
 *
 * 插件进程经 [IApexPluginHost] 回调宿主能力——当前暴露宿主的浏览器自动化
 * 工具集 [BrowserAgentTools]（browser_* 的实际逻辑跑在宿主进程的
 * BrowserEngine 上，插件只声明与分发工具清单）。[PluginManager] 在插件
 * 连接时把本桥以 binder 形式注入插件侧（本类继承 [IApexPluginHost.Stub]，
 * 本身就是 Binder 子类，可直接作为 attachHost 的参数传递）。
 *
 * 线程模型：executeHostTool 在宿主 binder 线程池的 IPC 工作线程上回调，
 * [runBlocking] 阻塞的是该专用线程（不冻结主线程，binder 池会扩容）；
 * [withTimeout] 120s 兜底防 browser_* 工具（如 wait_for 长等待）永久挂起。
 */
@Singleton
class PluginHostBridge @Inject constructor(
    private val browserAgentTools: BrowserAgentTools
) : IApexPluginHost.Stub() {

    /**
     * 宿主持有的工具（id → 工具）。BrowserAgentTools.all() 每次调用都会重新
     * 包装 TracedTool，故惰性求值一次固化快照（工具集静态，进程内不变）。
     */
    private val hostedTools by lazy { browserAgentTools.all().associateBy { it.id } }

    override fun executeHostTool(toolId: String?, argumentsJson: String?): String {
        val tool = toolId?.let { hostedTools[it] }
            ?: return "Error: host tool '<$toolId>' not found. Hosted: ${hostedTools.keys.sorted()}"
        return runCatching {
            runBlocking {
                withTimeout(120_000L) { tool.execute(argumentsJson ?: "{}") }
            }
        }.getOrElse { "Error: host tool execution failed: ${it.message}" }
    }

    override fun getHostCapabilitiesJson(): String = buildJsonObject {
        put("browserTools", JsonArray(hostedTools.keys.sorted().map { JsonPrimitive(it) }))
    }.toString()
}
