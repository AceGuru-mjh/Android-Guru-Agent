package com.apex.agent.mcp.builtin.fs

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.tools.mcp.McpException
import com.apex.agent.core.tools.mcp.McpTransportHandle
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 内置工作区文件系统 MCP 服务器（fs，进程内 transport）。
 *
 * 架构与 [com.apex.agent.search.mcp.BuiltinSearchMcpTransport] 完全同构
 * （Server 元数据 object + 本 Transport + Bootstrap 的三件套），差别仅在
 * tools/call 委托的对象从网络搜索栈换成 java.io 本地文件操作。
 *
 * 工具（命名对齐官方 server-filesystem 的常用子集，便于模型迁移习惯）：
 * - `list_directory` → 列目录条目（名称 + 文件/目录 + 大小，目录在前）
 * - `read_file`      → 读文本文件（默认 800 行 + 总字符双上限，防大文件
 *   打爆上下文；前 8KB 含 NUL 字节判定为二进制直接拒绝）
 * - `write_file`     → 写文本文件（tmp+rename 原子写，父目录自动创建）
 * - `get_file_info`  → 元信息（大小 / 最后修改时间 / 读写执行权限）
 *
 * 作用域 = **当前激活工作区根**：每次调用经 [roots] 现取（[CodeWorkspaceRoots]
 * 是会话态接口，切换工作区即时生效，与 code_* 六工具同一契约），因此本类
 * 不在构造时固化根路径。根未就绪（activeRoot 为 null 或目录不存在）时所有
 * 工具返回 isError 的引导文本而非硬错误，与 code_* 工具的降级语义一致。
 *
 * 路径安全三级防线（参考 SkillRegistry.safeSetupPath 的写法并加强）：
 * 1. 只受理相对路径 —— 绝对路径、Windows 盘符一律拒绝；
 * 2. 含 `..` 段直接拒绝（不做"规范化后再检查"的宽松版）；
 * 3. canonicalPath 前缀校验 —— 工作区内符号链接指向根外时会被解析后拦下。
 *
 * Android 本地运行：零子进程、零网络握手；阻塞式文件 IO 发生在 McpClient
 * 的 Dispatchers.IO 上下文里（与 github 内置服务器相同的线程契约），无需再切线程。
 */
class BuiltinFsMcpTransport(
    private val roots: CodeWorkspaceRoots
) : McpTransportHandle {

    private val json = Json { ignoreUnknownKeys = true }

    // ── McpTransportHandle ─────────────────────────────────────────

    override suspend fun send(id: Int?, payload: String): JsonObject? {
        if (id == null) return null // 通知（notifications 系）无响应

        val request = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            throw McpException("内置 FS MCP 收到非法 JSON-RPC 报文：${payload.take(80)}")
        }
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: throw McpException("内置 FS MCP 报文缺少 method 字段")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "fs-builtin")
                        put("version", "1.0.0")
                    }
                })
                "tools/list" -> put("result", buildJsonObject { put("tools", TOOLS) })
                "tools/call" -> put("result", callTool(params))
                else -> put("error", buildJsonObject {
                    put("code", METHOD_NOT_FOUND)
                    put("message", "Method not found: $method")
                })
            }
        }
    }

    /** 进程内通道随宿主存活，无外部资源可失联。 */
    override fun isHealthy(): Boolean = true

    /** 无子进程 / 无网络连接需要收尾。 */
    override fun close() = Unit

    // ── tools/call 派发 ─────────────────────────────────────────────

    private suspend fun callTool(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        return try {
            when (name) {
                "list_directory" -> textResult(callListDirectory(args))
                "read_file" -> textResult(callReadFile(args))
                "write_file" -> textResult(callWriteFile(args))
                "get_file_info" -> textResult(callGetFileInfo(args))
                else -> textResult(
                    "未知工具: $name（可用: ${TOOL_NAMES.joinToString(", ")}）",
                    isError = true
                )
            }
        } catch (e: FsToolException) {
            // 可预期错误（无工作区/非法路径/文件不存在等）→ isError 引导文本
            textResult("⚠️ ${e.message}", isError = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消必须向上传播（MCP 客户端负责超时语义）
        } catch (e: Exception) {
            textResult("❌ fs 工具执行失败: ${e.message ?: e::class.simpleName}", isError = true)
        }
    }

    /** MCP 标准 text 结果（isError 会被 McpClient → mcp_call 透传为工具错误）。 */
    private fun textResult(text: String, isError: Boolean = false): JsonObject = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
        if (isError) put("isError", true)
    }

    // ── 4 个工具实现 ───────────────────────────────────────────────

    private fun callListDirectory(args: JsonObject): String {
        val root = requireActiveRoot()
        val path = args.stringArg("path") ?: "."
        val dir = resolveInRoot(root, path) ?: throw FsToolException(illegalPathMessage(path))
        if (!dir.exists()) throw FsToolException("目录不存在: $path")
        if (!dir.isDirectory) throw FsToolException("不是目录（read_file 请传文件路径）: $path")

        val entries = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: throw FsToolException("无法读取目录: $path")
        if (entries.isEmpty()) return "目录 $path 为空"

        return buildString {
            appendLine("目录 $path（${entries.size} 项）:")
            entries.take(MAX_DIRECTORY_ENTRIES).forEach { f ->
                if (f.isDirectory) {
                    appendLine("📁 ${f.name}/")
                } else {
                    appendLine("📄 ${f.name} (${formatSize(f.length())})")
                }
            }
            if (entries.size > MAX_DIRECTORY_ENTRIES) {
                appendLine("…[仅显示前 $MAX_DIRECTORY_ENTRIES 项，共 ${entries.size} 项]")
            }
        }
    }

    private fun callReadFile(args: JsonObject): String {
        val root = requireActiveRoot()
        val path = args.stringArg("path") ?: throw FsToolException("需要参数 path")
        val maxLines = (args.intArg("max_lines") ?: DEFAULT_MAX_LINES)
            .coerceIn(1, MAX_LINES_CEILING)
        val file = resolveInRoot(root, path) ?: throw FsToolException(illegalPathMessage(path))
        if (!file.exists()) throw FsToolException("文件不存在: $path")
        if (file.isDirectory) throw FsToolException("目标是目录，请用 list_directory: $path")

        // 二进制探测：前 8KB 出现 NUL 字节即视为二进制，避免把乱码灌进模型上下文
        file.inputStream().use { input ->
            val probe = ByteArray(BINARY_PROBE_BYTES)
            var filled = 0
            while (filled < probe.size) {
                val read = input.read(probe, filled, probe.size - filled)
                if (read < 0) break
                filled += read
            }
            if (probe.copyOf(filled).contains(0.toByte())) {
                throw FsToolException("二进制文件，不支持 read_file 读取: $path")
            }
        }

        // 单遍流式读取：只缓存前 maxLines 行，同时统计总行数 —— 大文件内存有界
        val collected = ArrayList<String>(maxLines.coerceAtMost(1024))
        var totalLines = 0
        file.useLines { lines ->
            for (line in lines) {
                totalLines++
                if (collected.size < maxLines) collected.add(line)
            }
        }
        var text = collected.joinToString("\n")
        if (text.length > MAX_OUTPUT_CHARS) {
            text = text.take(MAX_OUTPUT_CHARS) + "\n[内容过长已截断：原文 ${text.length} 字符]"
        }
        val rangeHint = if (totalLines > maxLines) "，读取前 $maxLines 行" else ""
        return "📄 $path（共 $totalLines 行$rangeHint）:\n$text"
    }

    private fun callWriteFile(args: JsonObject): String {
        val root = requireActiveRoot()
        val path = args.stringArg("path") ?: throw FsToolException("需要参数 path")
        // content 允许显式传空串（清空文件是合法操作），因此用 containsKey 判存在
        if (!args.containsKey("content")) throw FsToolException("需要参数 content")
        val content = args["content"]?.jsonPrimitive?.contentOrNull
            ?: throw FsToolException("参数 content 必须是字符串")

        val target = resolveInRoot(root, path) ?: throw FsToolException(illegalPathMessage(path))
        if (target.exists() && target.isDirectory) {
            throw FsToolException("目标是已存在的目录，无法写入文件: $path")
        }
        val parent = target.parentFile ?: throw FsToolException("无法确定父目录: $path")
        if (!parent.exists() && !parent.mkdirs()) {
            throw FsToolException("父目录创建失败: $path")
        }

        val existed = target.exists()
        // 原子写：先写同目录临时文件再 rename —— 同一文件系统内 rename 对观察者
        // 原子，进程崩溃时不会留下半截内容（崩溃残留的 tmp 会被下次写覆盖）
        val tmp = File(parent, target.name + ".tmp")
        try {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                // 兜底：rename 失败（目标被占用等罕见场景）退化为复制覆盖
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete() // 清理半截临时文件再向上抛
            throw e
        }
        val action = if (existed) "更新" else "创建"
        return "✅ 已$action 文件 $path（${target.length()} 字节）"
    }

    private fun callGetFileInfo(args: JsonObject): String {
        val root = requireActiveRoot()
        val path = args.stringArg("path") ?: throw FsToolException("需要参数 path")
        val file = resolveInRoot(root, path) ?: throw FsToolException(illegalPathMessage(path))
        if (!file.exists()) throw FsToolException("路径不存在: $path")

        val type = when {
            file.isDirectory -> "目录"
            file.isFile -> "文件"
            else -> "其他（符号链接等）"
        }
        val modified = file.lastModified().takeIf { it > 0 }
            ?.let { "${Instant.ofEpochMilli(it)} ($it)" }
            ?: "未知"
        return buildString {
            appendLine("📄 $path")
            appendLine("类型: $type")
            appendLine("大小: ${if (file.isDirectory) "-" else formatSize(file.length())}")
            appendLine("最后修改: $modified")
            append("权限: 读${yn(file.canRead())} 写${yn(file.canWrite())} 执行${yn(file.canExecute())}")
        }
    }

    // ── 工作区根解析 + 路径安全 ────────────────────────────────────

    /** 根未就绪（null / 不存在 / 非目录）统一折叠为可操作的引导文本。 */
    private fun requireActiveRoot(): File {
        val root = roots.activeRoot()
        if (root == null || !root.exists() || !root.isDirectory) {
            throw FsToolException(NO_ACTIVE_WORKSPACE)
        }
        return root
    }

    /**
     * 路径清洗：只受理"相对工作区根"的路径，返回解析后的目标 File；
     * 越界或非法（绝对路径 / 盘符 / `..` 段 / 符号链接逃逸）返回 null。
     *
     * 空段（连续斜杠）与当前目录段（`.`）在拼接前吞掉；仅剩空段时目标即根自身
     * （list_directory 传 `.` 合法，写入类工具随后会被各自的类型检查拦下）。
     */
    private fun resolveInRoot(root: File, path: String): File? {
        val normalized = path.replace('\\', '/').trim()
        if (normalized.isBlank()) return null
        if (normalized.startsWith("/")) return null // 绝对路径拒绝
        val segments = normalized.split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null // 上跳段拒绝
        if (segments.firstOrNull()?.contains(':') == true) return null // Windows 盘符拒绝

        val target = if (segments.isEmpty()) root else File(root, segments.joinToString("/"))
        // canonicalPath 会解析符号链接：链内 symlink 指到根外在这里现形
        val rootCanonical = root.canonicalPath
        val targetCanonical = target.canonicalPath
        return when {
            targetCanonical == rootCanonical -> target // 根自身在作用域内
            targetCanonical.startsWith(rootCanonical + File.separator) -> target
            else -> null
        }
    }

    // ── 小工具 ─────────────────────────────────────────────────────

    /** 提取非空字符串参数（空白视为缺失；write_file 的 content 单独处理）。 */
    private fun JsonObject.stringArg(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.intArg(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    private fun yn(value: Boolean): String = if (value) "是" else "否"

    /** 工具级可预期错误 —— 折叠为 isError 文本而非 JSON-RPC 层异常。 */
    private class FsToolException(message: String) : Exception(message)

    // ── 工具清单（命名对齐官方 server-filesystem 常用子集） ────────

    private companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val METHOD_NOT_FOUND = -32601

        /** read_file 默认行数上限（任务书指定：防大文件打爆上下文）。 */
        const val DEFAULT_MAX_LINES = 800

        /** max_lines 参数天花板：即便模型显式请求更多也封顶。 */
        const val MAX_LINES_CEILING = 2000

        /** 单次输出字符天花板（行长异常时的第二道防线）。 */
        const val MAX_OUTPUT_CHARS = 60_000

        /** list_directory 条目数上限（巨型目录截断展示）。 */
        const val MAX_DIRECTORY_ENTRIES = 500

        /** 二进制探测窗口大小。 */
        const val BINARY_PROBE_BYTES = 8192

        const val NO_ACTIVE_WORKSPACE =
            "当前没有激活的编码工作区：请先在 Code 屏创建或选择一个工作区，再使用 fs 工具"

        fun illegalPathMessage(path: String) =
            "非法路径: $path（只允许工作区内相对路径；绝对路径与 .. 越界均被拒绝）"

        val TOOL_NAMES = listOf("list_directory", "read_file", "write_file", "get_file_info")

        /** tools/list 的 tools 数组（含 JSON Schema inputSchema）。 */
        val TOOLS: JsonArray by lazy { buildTools() }

        fun buildTools(): JsonArray = buildJsonArray {
            fun schema(properties: JsonObject, required: List<String>): JsonObject =
                buildJsonObject {
                    put("type", "object")
                    put("properties", properties)
                    putJsonArray("required") { required.forEach { add(it) } }
                }

            fun prop(type: String, description: String): JsonObject = buildJsonObject {
                put("type", type)
                put("description", description)
            }

            add(buildJsonObject {
                put("name", "list_directory")
                put("description", "列出编码工作区目录的条目清单（名称 + 文件/目录 + 大小，目录在前）。path 省略或传 . 表示工作区根")
                put("inputSchema", schema(buildJsonObject {
                    put("path", prop("string", "相对工作区根的目录路径（默认 .）"))
                }, emptyList()))
            })
            add(buildJsonObject {
                put("name", "read_file")
                put("description", "读取编码工作区中的文本文件（默认最多 800 行，防大文件撑爆上下文；二进制文件会被拒绝）")
                put("inputSchema", schema(buildJsonObject {
                    put("path", prop("string", "相对工作区根的文件路径"))
                    put("max_lines", prop("integer", "返回行数上限（1-$MAX_LINES_CEILING，默认 $DEFAULT_MAX_LINES）"))
                }, listOf("path")))
            })
            add(buildJsonObject {
                put("name", "write_file")
                put("description", "在编码工作区中创建或覆盖文本文件（原子写，父目录自动创建；content 为文件完整新内容）")
                put("inputSchema", schema(buildJsonObject {
                    put("path", prop("string", "相对工作区根的文件路径"))
                    put("content", prop("string", "文件完整新内容（整体覆盖，允许空串）"))
                }, listOf("path", "content")))
            })
            add(buildJsonObject {
                put("name", "get_file_info")
                put("description", "获取编码工作区中文件/目录的元信息（大小 / 最后修改时间 / 读写执行权限）")
                put("inputSchema", schema(buildJsonObject {
                    put("path", prop("string", "相对工作区根的路径"))
                }, listOf("path")))
            })
        }
    }
}
