package com.apex.agent.plugin.webautomation

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import com.apex.agent.plugin.api.IApexPlugin
import com.apex.agent.plugin.api.IApexPluginHost
import kotlinx.serialization.json.*

/**
 * 网页自动化插件服务（applicationId: com.apex.agent.plugin.webautomation）。
 *
 * 定位：把浏览器自动化"定位成插件"——本插件**声明与分发** browser_* 工具清单
 * （[BrowserToolCatalog]），实际执行经 [IApexPluginHost.executeHostTool] 回到
 * 宿主进程的 BrowserEngine / BrowserAgentTools（插件进程没有浏览器引擎，
 * 这是 [IApexPluginHost] 宿主桥存在的意义）。
 *
 * 生命周期：
 * 1. 宿主 PluginManager bindService → [onBind] 返回 binder；
 * 2. 宿主调 [attachHost] 注入宿主桥（[WebAutomationBinder.hostBridge] 就绪）；
 * 3. 宿主拉取 [getToolsJson] 并把工具注册进 ToolRegistry（同 id REPLACE
 *    覆盖宿主内置 browser_*）；
 * 4. 模型调用 browser_* → [executeTool] → hostBridge.executeHostTool（回宿主执行）。
 *
 * 宿主桥缺失/失效（宿主重启、插件先于 attach 被调用）时返回可行动的引导文案，
 * 而非抛异常——错误字符串会直接进入模型的下一轮上下文。
 */
class WebAutomationPluginService : Service() {

    private val binder = WebAutomationBinder()

    override fun onBind(intent: Intent): IBinder = binder

    /**
     * 宿主桥代理。attachHost 在宿主的 binder 线程回调、executeTool 在插件的
     * binder 线程池执行——两者不保证同线程，@Volatile 保证跨线程可见性。
     */
    @Volatile
    private var hostBridge: IApexPluginHost? = null

    inner class WebAutomationBinder : IApexPlugin.Stub() {

        override fun getMetadataJson(): String = buildJsonObject {
            put("id", "com.apex.agent.plugin.webautomation")
            put("name", "网页自动化")
            put("version", 1)
            put("versionName", "1.0.0")
            put("minHostVersion", 1)
            put("description", "浏览器自动化工具集（browser_*）：导航/快照/点击/输入/滚动/截图/接管等 15 个工具，工具逻辑由宿主 BrowserEngine 执行")
        }.toString()

        override fun getToolsJson(): String = buildJsonArray {
            BrowserToolCatalog.tools.forEach { t ->
                addJsonObject {
                    put("id", t.id)
                    put("name", t.name)
                    put("description", t.description)
                    // 值为 JSON 字符串本身（与宿主 AgentTool.parametersSchema 同构）：
                    // buildJsonObject 负责转义，宿主侧按字符串字段取出后再当 schema 解析。
                    put("parametersSchema", t.parametersSchema)
                }
            }
        }.toString()

        override fun executeTool(toolId: String, argumentsJson: String): String {
            val host = hostBridge
                ?: return "Error: host bridge not attached（宿主未连接，请重新加载插件）"
            if (BrowserToolCatalog.byId[toolId] == null) {
                return "Error: unknown tool '$toolId'. Available: browser_*（见插件工具清单）"
            }
            return try {
                host.executeHostTool(toolId, argumentsJson)
            } catch (e: RemoteException) {
                "Error: host bridge call failed: ${e.message}"
            }
        }

        override fun onActivate() {}
        override fun onDeactivate() {}

        /**
         * 宿主桥注入。asInterface 兼容本地 binder（同进程直接强转）与跨进程
         * binder（生成代理）两种形态；宿主传 null 则清空，下次调用走未连接文案。
         */
        override fun attachHost(host: IBinder?) {
            hostBridge = host?.let { IApexPluginHost.Stub.asInterface(it) }
        }
    }
}
