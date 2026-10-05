package com.apex.agent.plugin.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 工作流持久化纯逻辑层（#256 防回退测试的抽取）。
 *
 * 从 [WorkflowPluginService] 抽出全部文件操作与校验逻辑，Service 只保留
 * binder 胶水 —— 本类零 Android 依赖（目录经构造注入），可 JVM 单测直击：
 * save→list 往返、参数校验各分支、目录穿越名折叠、损坏 JSON 跳过、
 * 原子写 rename 失败兜底。
 *
 * 纪律对齐（AGENTS.md）：
 * - 原子写：tmp → flush → fsync → rename，rename 失败直写目标兜底；
 * - 防御式 IO：外部输入（args JSON / 落盘文件）任何形状都吞得下 —— 异常
 *   折叠为 null/跳过，不向上抛；
 * - 错误串以 "Error" 开头 —— 宿主 SafeAgentTool 按前缀判失败，绝不假成功。
 */
class WorkflowStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 工具入口（Service 直接委托）──────────────────────────────────

    /** `workflow/save`：校验 → 原子落盘 → 带路径回执。失败以 Error 开头。 */
    fun saveWorkflow(args: String): String {
        val obj = parseArgsObject(args)
            ?: return "Error: workflow/save arguments must be a JSON object: $MALFORMED_ARGS_HINT"

        val name = obj.stringOf("name")?.trim()
        if (name.isNullOrEmpty()) {
            return "Error: workflow/save requires a non-empty 'name' string"
        }
        val steps = (obj["steps"] as? JsonArray)
            ?: return "Error: workflow/save requires 'steps' to be an array of steps"
        if (steps.isEmpty()) {
            return "Error: workflow/save requires at least one step in 'steps'"
        }
        if (steps.size > MAX_STEPS) {
            return "Error: too many steps (${steps.size}, max $MAX_STEPS)"
        }
        val id = sanitizeWorkflowId(name)
            ?: return "Error: workflow name '$name' has no usable characters for a file id"

        val file = File(dir, "$id$JSON_SUFFIX")
        val payload = buildJsonObject {
            put("name", name)
            put("steps", steps)
            put("stepCount", steps.size)
            put("savedAt", clock())
        }.toString()
        return try {
            writeAtomically(file, payload)
            "OK: workflow '$name' saved to ${file.absolutePath} (${steps.size} steps)"
        } catch (e: IOException) {
            "Error: failed to persist workflow '$name': ${e.message}"
        }
    }

    /** `workflow/list`：列目录 → 摘要数组（损坏文件跳过，不拖垮整个清单）。 */
    fun listWorkflows(): String {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(JSON_SUFFIX) }
            ?: return "[]"
        val summaries = files.mapNotNull { file ->
            runCatching {
                val obj = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
                buildJsonObject {
                    put("name", obj["name"]?.jsonPrimitive?.contentOrNull ?: file.nameWithoutExtension)
                    put("steps", obj["stepCount"]?.jsonPrimitive?.intOrNull
                        ?: (obj["steps"] as? JsonArray)?.size ?: 0)
                    obj["savedAt"]?.jsonPrimitive?.longOrNull?.let { put("savedAt", it) }
                }
            }.getOrNull() // 损坏/半写的 tmp 残留：跳过
        }.sortedBy { it["name"]?.jsonPrimitive?.contentOrNull ?: "" }
        return summaries.joinToString(prefix = "[", separator = ",", postfix = "]") { it.toString() }
    }

    /**
     * `workflow/execute` 的读取半段：读回保存的工作流（校验存在性与 JSON
     * 可解析性）。执行半段需要宿主桥，由 Service 层给出诚实失败 ——
     * 本方法只负责把「文件不存在 / 损坏」与「加载成功」区分开。
     */
    sealed interface LoadResult {
        /** 加载成功（steps 原样返回，执行语义留给调用方）。 */
        data class Loaded(val name: String, val steps: JsonArray) : LoadResult

        /** 未找到（Error 串，含自修复指引）。 */
        data class NotFound(val message: String) : LoadResult

        /** 文件存在但 JSON 损坏（Error 串，含路径）。 */
        data class Corrupted(val message: String) : LoadResult
    }

    fun loadWorkflow(name: String): LoadResult {
        val trimmed = name.trim()
        val id = sanitizeWorkflowId(trimmed)
            ?: return LoadResult.Corrupted(
                "Error: workflow name '$trimmed' has no usable characters for a file id"
            )
        val file = File(dir, "$id$JSON_SUFFIX")
        if (!file.isFile) {
            return LoadResult.NotFound(
                "Error: workflow '$trimmed' not found. Use workflow/list to see saved workflows."
            )
        }
        val workflow = runCatching {
            json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        }.getOrNull()
            ?: return LoadResult.Corrupted(
                "Error: saved workflow '$trimmed' is corrupted (unparseable JSON at ${file.absolutePath})"
            )
        val steps = (workflow["steps"] as? JsonArray) ?: JsonArray(emptyList())
        return LoadResult.Loaded(trimmed, steps)
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    private fun parseArgsObject(args: String): JsonObject? = runCatching {
        json.parseToJsonElement(args) as? JsonObject
    }.getOrNull()

    /** 安全取字符串字段：缺失/非原始类型/JSON null 返回 null（不抛——binder 线程不能崩）。 */
    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    /**
     * 工作流名 → 文件 id：仅保留字母/数字/`-`/`_`/`.`（其余折叠为 `-`），
     * 首尾符号剥离、长度截断。名字来自模型输出——目录穿越（`../` 中的
     * 分隔符会被折叠）与空名在此被消除。
     */
    internal fun sanitizeWorkflowId(raw: String): String? {
        val cleaned = raw.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '-'
        }.joinToString("").trim('-', '.').take(MAX_ID_CHARS)
        return cleaned.ifEmpty { null }
    }

    /**
     * 原子写（对齐宿主 AGENTS.md 纪律）：tmp → flush → fsync → rename，
     * rename 失败直写兜底。
     */
    @Throws(IOException::class)
    internal fun writeAtomically(file: File, content: String) {
        val parent = file.parentFile
        parent?.let { if (!it.isDirectory) it.mkdirs() }
        val tmp = File(parent, file.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    companion object {
        const val WORKFLOWS_DIR_NAME = "workflows"
        internal const val JSON_SUFFIX = ".json"
        internal const val TMP_SUFFIX = ".tmp"
        internal const val MAX_STEPS = 200
        internal const val MAX_ID_CHARS = 64
        internal const val MALFORMED_ARGS_HINT = "expected {\"name\": \"...\", \"steps\": [...]}"
    }
}
