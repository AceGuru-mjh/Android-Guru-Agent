package com.apex.agent.plugin.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.plugin.api.IApexPlugin
import com.apex.agent.plugin.api.IApexPluginHost
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件管理器
 * 负责发现、加载、管理插件APK
 *
 * ## v2 修复
 * - **bindService 返回值**：旧实现忽略返回值——绑定失败（插件被禁用/服务名错误）时
 *   无感知、无日志，ServiceConnection 对象从此泄漏。现在失败即记录并回收。
 * - **ServiceConnection 泄漏**：旧实现 `onServiceDisconnected`（插件进程死亡时回调）
 *   只从 loaded map 移除，之后 unloadPlugin 因 map 无条目提前 return，真正的
 *   unbind 永远不会发生——绑定泄漏到进程结束。现在 connection 独立登记在
 *   [connections]，卸载时按包名反查解绑，无论插件进程是否死亡。
 * - **registerPluginTools**：v2 曾记"未实现"日志（彼时 AIDL 未冻结、仅返回
 *   TODO 标记）；**v3 起真实注册**：attachHost 注入宿主桥 → 解析 getToolsJson →
 *   以 [PluginAgentTool] 桥接进 [ToolRegistry]（REPLACE 覆盖宿主同 id 工具）。
 *   卸载/插件进程死亡时降级为 [HostFallbackTool]（宿主直调），不挖空注册表。
 * - **API 33+ 弃用**：queryIntentServices 改用 ResolveInfoFlags 变体（旧行为保留）。
 */
@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toolRegistry: ToolRegistry,
    /**
     * 宿主桥（app 的 PluginHostBridge 经 Hilt @Binds 提供）：插件经
     * [IApexPluginHost.executeHostTool] 回调宿主能力——browser_* 的实际逻辑
     * 在宿主进程 BrowserEngine 上，插件只声明与分发工具清单。
     */
    private val hostBridge: IApexPluginHost
) {

    private val _loadedPlugins = MutableStateFlow<Map<String, LoadedPlugin>>(emptyMap())
    val loadedPlugins: StateFlow<Map<String, LoadedPlugin>> = _loadedPlugins.asStateFlow()

    /** 包名 → 活跃 ServiceConnection（含插件进程已死亡但绑定仍在的，卸载时统一解绑）。 */
    private val connections = ConcurrentHashMap<String, ServiceConnection>()

    /**
     * 包名 → 该插件注册进 [ToolRegistry] 的工具描述符快照。
     * 卸载/插件死亡时据此降级为 [HostFallbackTool]（描述符原样保留，execute
     * 改走宿主直调）——插件工具与宿主内置工具同 id（REPLACE 覆盖），直接
     * unregister 会把工具位挖空，这是恢复安全网的数据来源。
     */
    private val pluginTools = ConcurrentHashMap<String, List<PluginToolDescriptorData>>()

    /** 插件工具清单解析（getToolsJson → 描述符）。宽松配置：字段缺失回退占位值。 */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class LoadedPlugin(
        val packageName: String,
        val name: String,
        val connection: ServiceConnection,
        val binder: IBinder
    )

    /**
     * 发现已安装的Apex插件
     */
    fun discoverPlugins(): List<PluginInfo> {
        val intent = Intent("com.apex.agent.plugin.PLUGIN")
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentServices(
                intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
        }

        return resolveInfos.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            runCatching {
                PluginInfo(
                    packageName = si.packageName,
                    serviceName = si.name,
                    label = si.loadLabel(context.packageManager).toString()
                )
            }.getOrNull()
        }
    }

    /**
     * 加载插件
     */
    fun loadPlugin(info: PluginInfo) {
        if (connections.containsKey(info.packageName)) return  // 已在加载/已加载

        val intent = Intent("com.apex.agent.plugin.PLUGIN").apply {
            setClassName(info.packageName, info.serviceName)
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                _loadedPlugins.value = _loadedPlugins.value + (info.packageName to LoadedPlugin(
                    packageName = info.packageName,
                    name = info.label,
                    connection = this,
                    binder = binder
                ))

                // 将插件的工具注册到全局ToolRegistry
                registerPluginTools(info.packageName, binder)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // 插件进程死亡：从已加载表移除，但绑定仍在——保留在 connections，
                // unloadPlugin 时仍可正确解绑（旧实现此处直接丢失 unbind 机会 → 泄漏）
                _loadedPlugins.value = _loadedPlugins.value - info.packageName
                // binder 已失效：插件工具立刻降级为宿主直调（同 id 不挖空）。
                // BIND_AUTO_CREATE 会重启插件进程，onServiceConnected 再次回调时
                // registerPluginTools 以 REPLACE 语义恢复插件直连版本。
                demotePluginToolsToHostFallback(info.packageName)
            }
        }

        connections[info.packageName] = connection
        val bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)

        if (!bound) {
            // 绑定失败（插件被禁用/服务组件名变更）：回收 connection，避免泄漏
            Log.w("PluginManager", "bindService failed for ${info.packageName} — 插件被禁用或服务不可用?")
            connections.remove(info.packageName)
        }
    }

    /**
     * 卸载插件
     */
    fun unloadPlugin(packageName: String) {
        // 先降级工具再解绑：unbindService 不会回调 onServiceDisconnected；demote 幂等
        // （pluginTools 已移除则空操作），此处顺序仅防御插件进程恰在此刻死亡的竞态。
        demotePluginToolsToHostFallback(packageName)
        connections.remove(packageName)?.let { conn ->
            runCatching { context.unbindService(conn) }
                .onFailure { Log.w("PluginManager", "unbindService $packageName: ${it.message}") }
        }
        _loadedPlugins.value = _loadedPlugins.value - packageName
    }

    /**
     * 把插件声明的工具真实注册进 [ToolRegistry]（v3：原 TODO 的落地实现）。
     *
     * 流程：binder 还原 [IApexPlugin] → attachHost 注入宿主桥（插件经
     * [IApexPluginHost] 回调宿主能力，如 browser_* 的 BrowserEngine 执行逻辑）→
     * 解析 getToolsJson() → 逐个注册 [PluginAgentTool]（同 id 以 REPLACE 覆盖
     * 宿主内置工具，正是"网页自动化定位成插件"的载体）。attachHost 统一在
     * 此处调用且只调一次——插件进程重启重连时随本方法重新注入。
     */
    private fun registerPluginTools(pkg: String, binder: IBinder) {
        val plugin = IApexPlugin.Stub.asInterface(binder)
        runCatching {
            // 宿主桥 binder 传递：PluginHostBridge 即 IApexPluginHost.Stub（Binder 子类），
            // 插件侧 IApexPluginHost.Stub.asInterface() 自行还原为本地实现或代理。
            plugin.attachHost(hostBridge as IBinder)
            val toolsJson = plugin.getToolsJson()
            val descriptors = parseTools(toolsJson)
            descriptors.forEach { d ->
                toolRegistry.register(PluginAgentTool(pkg, plugin, d))
            }
            pluginTools[pkg] = descriptors
            Log.i(
                "PluginManager",
                "plugin $pkg connected: registered ${descriptors.size} tools into ToolRegistry " +
                    "(${descriptors.joinToString { it.id }})"
            )
        }.onFailure {
            Log.w("PluginManager", "register plugin tools failed for $pkg: ${it.message}")
        }
    }

    /**
     * 插件不可用（卸载 / 进程死亡）时，把它的工具降级为宿主直调 fallback：
     * 描述符（id/name/description/parametersSchema）保留插件版原样，execute
     * 不再跨进程，直接经 [IApexPluginHost.executeHostTool] 调宿主实现。
     *
     * 这是"插件工具与宿主内置 browser_* 同 id（REPLACE 覆盖）"设计的回收安全网：
     * 只 unregister 会把工具位挖空；降级后工具依旧可用——BrowserAgentTools
     * 本就在宿主进程执行，插件只是声明/分发壳，两条路径殊途同归。
     */
    private fun demotePluginToolsToHostFallback(pkg: String) {
        val descriptors = pluginTools.remove(pkg) ?: return
        descriptors.forEach { d ->
            runCatching { toolRegistry.register(HostFallbackTool(d, hostBridge)) }
                .onFailure { Log.w("PluginManager", "demote tool ${d.id} to host fallback for $pkg: ${it.message}") }
        }
        Log.i(
            "PluginManager",
            "plugin $pkg unavailable: ${descriptors.size} tools demoted to host fallback"
        )
    }

    /**
     * 解析插件 getToolsJson()：`[{"id":..,"name":..,"description":..,"parametersSchema":..}]`。
     * parametersSchema 是字符串字段（值为 JSON 文本本身）。字段缺失跳过该条目；
     * 整串非法回退空列表——绝不让插件的数据形态炸掉宿主注册流程。
     */
    private fun parseTools(toolsJson: String): List<PluginToolDescriptorData> = runCatching {
        val root = json.parseToJsonElement(toolsJson)
        (root as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            PluginToolDescriptorData(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                description = obj["description"]?.jsonPrimitive?.contentOrNull ?: id,
                parametersSchema = obj["parametersSchema"]?.jsonPrimitive?.contentOrNull
                    ?: EMPTY_TOOL_SCHEMA
            )
        } ?: emptyList()
    }.getOrDefault(emptyList())
}

/** getToolsJson 条目（与 plugin-api 的 PluginToolDescriptor 字段对齐）。 */
private data class PluginToolDescriptorData(
    val id: String,
    val name: String,
    val description: String,
    val parametersSchema: String
)

/** 插件未提供 parametersSchema 时的占位（宽松 schema 导入下等价于"不校验"）。 */
private const val EMPTY_TOOL_SCHEMA = """{"type":"object","properties":{}}"""

/**
 * 插件工具的 [AgentTool] 适配器：元数据来自插件描述符，execute 经 binder IPC
 * 转发进插件进程（[Dispatchers.IO] 上执行，RemoteException/插件侧死亡转成
 * 模型可读的错误字符串而非异常上抛）。
 */
private class PluginAgentTool(
    private val pluginPackage: String,
    private val plugin: IApexPlugin,
    private val descriptor: PluginToolDescriptorData
) : AgentTool {
    override val id get() = descriptor.id
    override val name get() = descriptor.name
    override val description get() = descriptor.description
    override val parametersSchema get() = descriptor.parametersSchema

    override suspend fun execute(arguments: String): String = withContext(Dispatchers.IO) {
        runCatching { plugin.executeTool(descriptor.id, arguments) }
            .getOrElse { "Error: plugin '$pluginPackage' tool execution failed: ${it.message}" }
    }
}

/**
 * 宿主直调 fallback：插件卸载/死亡后顶替其工具位（[PluginManager] 注册），
 * 描述符保留插件版原样（id/name/description/parametersSchema 不变，模型
 * 感知不到切换），execute 不再跨进程，直接经 [IApexPluginHost.executeHostTool]
 * 调宿主实现——BrowserAgentTools 就在宿主进程，功能与插件模式等价。
 */
private class HostFallbackTool(
    private val descriptor: PluginToolDescriptorData,
    private val host: IApexPluginHost
) : AgentTool {
    override val id get() = descriptor.id
    override val name get() = descriptor.name
    override val description get() = descriptor.description
    override val parametersSchema get() = descriptor.parametersSchema

    override suspend fun execute(arguments: String): String = withContext(Dispatchers.IO) {
        runCatching { host.executeHostTool(descriptor.id, arguments) }
            .getOrElse { "Error: host fallback tool '${descriptor.id}' execution failed: ${it.message}" }
    }
}

data class PluginInfo(
    val packageName: String,
    val serviceName: String,
    val label: String
)
