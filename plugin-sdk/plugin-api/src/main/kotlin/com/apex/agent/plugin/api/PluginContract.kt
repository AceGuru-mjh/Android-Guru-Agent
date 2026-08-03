package com.apex.agent.plugin.api

/**
 * 插件契约：所有插件APK必须实现的接口
 * 通过AIDL暴露给主APK
 */
interface ApexPluginService {
    fun getMetadata(): PluginMetadata
    fun getTools(): List<PluginToolDescriptor>
    suspend fun executeTool(toolId: String, arguments: String): String
    fun onActivate()
    fun onDeactivate()
}

data class PluginMetadata(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val minHostVersion: Int,
    val description: String
)

data class PluginToolDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val parametersSchema: String
)
