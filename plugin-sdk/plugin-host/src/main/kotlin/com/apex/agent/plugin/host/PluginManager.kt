package com.apex.agent.plugin.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件管理器
 * 负责发现、加载、管理插件APK
 */
@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toolRegistry: ToolRegistry
) {
    
    private val _loadedPlugins = MutableStateFlow<Map<String, LoadedPlugin>>(emptyMap())
    val loadedPlugins: StateFlow<Map<String, LoadedPlugin>> = _loadedPlugins.asStateFlow()

    data class LoadedPlugin(
        val packageName: String,
        val name: String,
        val connection: ServiceConnection,
        val binder: IBinder
    )

    /**
     * 发现所有已安装的Apex插件
     */
    fun discoverPlugins(): List<PluginInfo> {
        val intent = Intent("com.apex.agent.plugin.PLUGIN")
        val resolveInfos = context.packageManager.queryIntentServices(
            intent, PackageManager.MATCH_ALL
        )
        
        return resolveInfos.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            PluginInfo(
                packageName = si.packageName,
                serviceName = si.name,
                label = si.loadLabel(context.packageManager).toString()
            )
        }
    }

    /**
     * 加载插件
     */
    fun loadPlugin(info: PluginInfo) {
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
                _loadedPlugins.value = _loadedPlugins.value - info.packageName
            }
        }
        
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /**
     * 卸载插件
     */
    fun unloadPlugin(packageName: String) {
        val plugin = _loadedPlugins.value[packageName] ?: return
        context.unbindService(plugin.connection)
        _loadedPlugins.value = _loadedPlugins.value - packageName
    }

    private fun registerPluginTools(pluginPackage: String, binder: IBinder) {
        // 通过AIDL获取插件工具列表并注册
        // TODO: 实际AIDL调用
    }
}

data class PluginInfo(
    val packageName: String,
    val serviceName: String,
    val label: String
)
