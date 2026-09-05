package com.apex.agent.plugin.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.apex.agent.core.tools.ToolRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * - **registerPluginTools**：明确返回"未实现"标记并记日志（旧 TODO 静默空转）。
 * - **API 33+ 弃用**：queryIntentServices 改用 ResolveInfoFlags 变体（旧行为保留）。
 */
@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @Suppress("unused") // 预留给 AIDL 工具注册（见 registerPluginTools）
    private val toolRegistry: ToolRegistry
) {

    private val _loadedPlugins = MutableStateFlow<Map<String, LoadedPlugin>>(emptyMap())
    val loadedPlugins: StateFlow<Map<String, LoadedPlugin>> = _loadedPlugins.asStateFlow()

    /** 包名 → 活跃 ServiceConnection（含插件进程已死亡但绑定仍在的，卸载时统一解绑）。 */
    private val connections = ConcurrentHashMap<String, ServiceConnection>()

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
        connections.remove(packageName)?.let { conn ->
            runCatching { context.unbindService(conn) }
                .onFailure { Log.w("PluginManager", "unbindService $packageName: ${it.message}") }
        }
        _loadedPlugins.value = _loadedPlugins.value - packageName
    }

    private fun registerPluginTools(pluginPackage: String, binder: IBinder) {
        // TODO: AIDL 接口定型后把插件工具注册进 ToolRegistry（plugin-sdk 的
        //  IPluginAgent 接口尚未冻结）。当前记日志避免静默空转误导调试。
        Log.i("PluginManager", "plugin $pluginPackage connected; tool registration pending AIDL freeze")
    }
}

data class PluginInfo(
    val packageName: String,
    val serviceName: String,
    val label: String
)
