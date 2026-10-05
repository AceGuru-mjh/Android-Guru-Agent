package com.apex.agent.plugin.workflow

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.apex.agent.plugin.api.IApexPlugin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * 工作流插件（applicationId: com.apex.agent.plugin.workflow）。
 *
 * 三工具的实现（运行在本插件自己的 APK 进程里，文件逻辑全部委托
 * [WorkflowStore] —— 纯 JVM 可单测，本类只剩 binder 胶水）：
 * - `workflow/save`：校验 args（name + steps 数组）后原子落盘到本插件私有的
 *   `filesDir/workflows/<id>.json`，返回带路径的成功消息；
 * - `workflow/list`：列目录读全部 JSON，返回 `[{name, steps, savedAt}]` 摘要数组；
 * - `workflow/execute`：读取保存的工作流并解析步骤；实际执行需要宿主桥
 *   （步骤语义是宿主侧工具调用，本插件进程没有执行通道），当前版本返回
 *   **明确的不支持错误**——绝不谎报执行成功污染 agent 循环（#256）。
 *
 * AIDL 协议（getMetadataJson / getToolsJson / executeTool / attachHost）形态不变。
 */
class WorkflowPluginService : Service() {

    private val binder = WorkflowPluginBinder()

    private fun newStore(): WorkflowStore =
        WorkflowStore(File(filesDir, WorkflowStore.WORKFLOWS_DIR_NAME))

    inner class WorkflowPluginBinder : IApexPlugin.Stub() {

        override fun getMetadataJson(): String {
            return buildJsonObject {
                put("id", "com.apex.agent.plugin.workflow")
                put("name", "工作流引擎")
                put("version", 1)
                put("versionName", "1.0.0")
                put("minHostVersion", 1)
                put("description", "工作流持久化：保存/列出可复用的动作序列（执行需宿主桥，当前版本不支持）")
            }.toString()
        }

        override fun getToolsJson(): String {
            return buildJsonArray {
                addJsonObject {
                    put("id", "workflow/save")
                    put("name", "Save Workflow")
                    put("description", "Save a sequence of actions as a reusable workflow")
                    put("parametersSchema", buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("name") { put("type", "string") }
                            putJsonObject("steps") { put("type", "array") }
                        }
                        putJsonArray("required") { add("name"); add("steps") }
                    }.toString())
                }
                addJsonObject {
                    put("id", "workflow/execute")
                    put("name", "Execute Workflow")
                    put("description", "Execute a saved workflow by name (NOT SUPPORTED in this " +
                        "plugin version: execution requires a host bridge which is not available)")
                    put("parametersSchema", buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("name") { put("type", "string") }
                            putJsonObject("params") { put("type", "object") }
                        }
                        putJsonArray("required") { add("name") }
                    }.toString())
                }
                addJsonObject {
                    put("id", "workflow/list")
                    put("name", "List Workflows")
                    put("description", "List all saved workflows")
                    put("parametersSchema", """{"type":"object","properties":{}}""")
                }
            }.toString()
        }

        override fun executeTool(toolId: String, argumentsJson: String): String {
            // binder 线程池里每请求新建 store（filesDir 只读解析，开销可忽略），
            // 避免跨请求共享可变状态。
            val store = newStore()
            return when (toolId) {
                "workflow/save" -> store.saveWorkflow(argumentsJson)
                "workflow/list" -> store.listWorkflows()
                "workflow/execute" -> handleExecuteWorkflow(store, argumentsJson)
                else -> "Error: Unknown tool $toolId"
            }
        }

        override fun onActivate() {}
        override fun onDeactivate() {}

        /**
         * 宿主桥注入（IApexPlugin v3 新增方法）。本插件的工具语义不依赖宿主
         * 能力（save/list 是本地文件操作，execute 如上所述不支持），空实现
         * 即可——注意 AIDL 生成的 Kotlin 签名参数为可空类型。
         */
        override fun attachHost(host: IBinder?) {}

        /** `workflow/execute`：能读能解析（[WorkflowStore]），执行通道不存在 → 诚实失败。 */
        private fun handleExecuteWorkflow(store: WorkflowStore, args: String): String {
            val obj = runCatching { Json.parseToJsonElement(args) }.getOrNull() as? JsonObject
                ?: return "Error: workflow/execute arguments must be a JSON object: " +
                    WorkflowStore.MALFORMED_ARGS_HINT
            val name = (obj["name"] as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }
                ?.contentOrNull?.trim()
            if (name.isNullOrEmpty()) {
                return "Error: workflow/execute requires a non-empty 'name' string"
            }
            return when (val loaded = store.loadWorkflow(name)) {
                is WorkflowStore.LoadResult.NotFound -> loaded.message
                is WorkflowStore.LoadResult.Corrupted -> loaded.message
                is WorkflowStore.LoadResult.Loaded ->
                    // 步骤语义是「声明给宿主的工具调用」，而本插件进程没有执行通道
                    // （attachHost 未注入可用桥）——诚实失败，绝不返回假成功。
                    "Error: workflow execution requires host bridge which is not available " +
                        "in this plugin version (workflow '$name' loaded fine: ${loaded.steps.size} steps). " +
                        "Execute the steps yourself with the equivalent host tools instead."
            }
        }
    }
}
