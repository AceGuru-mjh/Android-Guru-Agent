package com.apex.agent.github.mcp

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.mcp.McpException
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.mcp.McpTransportHandle
import com.apex.agent.github.GithubApiException
import com.apex.agent.github.GithubApiService
import com.apex.agent.github.GithubTokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

// ══════════════════════════════════════════════════════════════════════
//  服务器元数据
// ══════════════════════════════════════════════════════════════════════

/**
 * 内置 GitHub MCP 服务器的注册元数据。
 *
 * [BuiltinGithubMcpServer.ID] 同时是三处约定的 key：McpManager 配置里的服务器名、
 * `builtinTransports` 工厂注册表的 key、以及 `mcp_call` 的 `server` 参数值
 * （= 斜杠指令 /mcp:github 的 id）—— 三者必须一致。
 */
object BuiltinGithubMcpServer {
    /** 服务器名（= mcp_call 的 server 参数 = 斜杠指令 /mcp:github 的 id）。 */
    const val ID = "github"

    /** 预置到 mcp_servers.json 的配置（transport=BUILTIN，无 URL/命令）。 */
    fun config(): McpServerConfig = McpServerConfig(
        name = ID,
        transport = McpTransport.BUILTIN,
        enabled = true
    )
}

// ══════════════════════════════════════════════════════════════════════
//  进程内 transport（路径 A）
// ══════════════════════════════════════════════════════════════════════

/**
 * 内置 GitHub MCP 服务器（进程内 transport）。
 *
 * 把 App 已有的 [GithubApiService]（GitHub REST 直连）包装成一个**真** MCP 服务器：
 * 实现 [McpTransportHandle]（与 McpHttpTransport 同一契约），对 JSON-RPC
 * 2024-11-05 报文做进程内派发 —— 上层 McpClient/McpManager/mcp_call 元工具
 * 完全不感知对面既不是进程也不是网络。
 *
 * 消息形态：
 * - `initialize` → capabilities.tools + serverInfo（github-builtin / 1.0.0）；
 * - `tools/list` → 7 个工具（命名对齐官方 github-mcp-server：
 *   get_me / list_repositories / get_file_contents / create_or_update_file /
 *   create_issue / list_issues / search_code），各带 JSON Schema inputSchema；
 * - `tools/call` → 按名派发 [GithubApiService]，返回 MCP 标准结果
 *   `{"content":[{"type":"text","text":"..."}]}`；GitHub API 错误 / 未配置 token
 *   → `isError=true` + 友好文本（McpClient 会把 isError 透传给 mcp_call）；
 * - 通知（id=null，如 notifications/initialized）→ 忽略返回 null；
 * - 其他 method → JSON-RPC error -32601 method-not-found。
 *
 * 线程契约：`send` 是 suspend —— McpClient 的 initialize/listTools/callTool 都包了
 * `withContext(Dispatchers.IO)`，GithubApiService 的 HTTP 调用自身也是非阻塞挂起，
 * 因此这里无需再切线程。每次 [McpManager.connect] 由工厂构造一个新实例。
 *
 * 放在 app 模块是因为它依赖 [GithubApiService]；core 侧通过 McpManager 的
 * `builtinTransports` 工厂注入，保持 core ← app 单向依赖。
 */
class BuiltinGithubMcpTransport(
    private val api: GithubApiService,
    private val tokenManager: GithubTokenManager
) : McpTransportHandle {

    private val json = Json { ignoreUnknownKeys = true }

    // ── McpTransportHandle ─────────────────────────────────────────

    override suspend fun send(id: Int?, payload: String): JsonObject? {
        // 通知（notifications/initialized / cancelled 等）无 id，不产生响应
        if (id == null) return null

        val request = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            throw McpException("内置 GitHub MCP 收到非法 JSON-RPC 报文：${payload.take(80)}")
        }
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: throw McpException("内置 GitHub MCP 报文缺少 method 字段")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "github-builtin")
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

        // token 未配置：工具存在（tools/list 照常），调用时给可操作的引导文本。
        if (!tokenManager.isConnected()) {
            return textResult(TOKEN_MISSING_HINT, isError = true)
        }

        return try {
            when (name) {
                "get_me" -> textResult(callGetMe())
                "list_repositories" -> textResult(callListRepositories(args))
                "get_file_contents" -> textResult(callGetFileContents(args))
                "create_or_update_file" -> textResult(callCreateOrUpdateFile(args))
                "create_issue" -> textResult(callCreateIssue(args))
                "list_issues" -> textResult(callListIssues(args))
                "search_code" -> textResult(callSearchCode(args))
                else -> textResult(
                    "未知工具: $name（可用: ${TOOL_NAMES.joinToString(", ")}）",
                    isError = true
                )
            }
        } catch (e: GithubApiException) {
            val code = e.code?.let { " (HTTP $it)" } ?: ""
            textResult("❌ GitHub API 错误$code: ${e.message}", isError = true)
        } catch (e: Exception) {
            // 解析/网络/未知异常折叠成 MCP 工具错误，而不是让 JSON-RPC 层崩掉 ——
            // 模型看到 isError 文本后可以自行调整参数重试或向用户解释。
            textResult("❌ 工具执行失败: ${e.message ?: e::class.simpleName}", isError = true)
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

    // ── 7 个工具实现（输出格式与原生 github_* 工具对齐） ─────────────

    private suspend fun callGetMe(): String {
        val user = api.getCurrentUser()
        return "GitHub 用户: ${user.login}\n名称: ${user.name ?: "N/A"}\n公开仓库: ${user.public_repos}"
    }

    private suspend fun callListRepositories(args: JsonObject): String {
        val username = args["username"]?.jsonPrimitive?.contentOrNull
        val limit = args.intArg("limit")?.coerceIn(1, 100) ?: DEFAULT_REPO_LIMIT
        val repos = api.listRepos(username, perPage = limit)
        if (repos.isEmpty()) return "没有找到仓库"
        return buildString {
            appendLine("找到 ${repos.size} 个仓库:")
            repos.forEach { r ->
                val lock = if (r.private) "🔒" else "📂"
                appendLine("$lock ${r.full_name} | ${r.language ?: "N/A"} | ⭐${r.stargazers_count}")
                r.description?.let { appendLine("   $it") }
            }
        }
    }

    private suspend fun callGetFileContents(args: JsonObject): String {
        val owner = args.stringArg("owner") ?: return "Error: 需要 owner"
        val repo = args.stringArg("repo") ?: return "Error: 需要 repo"
        val path = args.stringArg("path") ?: return "Error: 需要 path"
        val branch = args["branch"]?.jsonPrimitive?.contentOrNull
        val file = api.getFileContent(owner, repo, path, branch)
        val content = file.decoded()
        return buildString {
            appendLine("📄 $owner/$repo/$path (${file.size} bytes)")
            if (content.length > 3000) {
                append(content.take(3000))
                appendLine("\n[截断，共${content.length}字符]")
            } else {
                append(content)
            }
        }
    }

    private suspend fun callCreateOrUpdateFile(args: JsonObject): String {
        val owner = args.stringArg("owner") ?: return "Error: 需要 owner"
        val repo = args.stringArg("repo") ?: return "Error: 需要 repo"
        val path = args.stringArg("path") ?: return "Error: 需要 path"
        val content = args["content"]?.jsonPrimitive?.contentOrNull ?: return "Error: 需要 content"
        val message = args["message"]?.jsonPrimitive?.contentOrNull ?: "Update $path"
        val branch = args["branch"]?.jsonPrimitive?.contentOrNull
        val explicitSha = args["sha"]?.jsonPrimitive?.contentOrNull

        // 与原生 github_write_file 相同的探针：无显式 sha 时先查现存文件
        // （404 → 新建；其他错误向上抛、由 callTool 折叠为工具错误），
        // 避免盲 PUT 被 GitHub 422 拒绝（"sha missing"）。
        var existingSha = explicitSha
        if (existingSha == null) {
            try {
                existingSha = api.getFileContent(owner, repo, path, branch).sha
            } catch (e: GithubApiException) {
                if (e.code != 404) throw e
            }
        }

        val result = api.createOrUpdateFile(owner, repo, path, content, message, branch, existingSha)
        val action = if (existingSha != null) "更新" else "创建"
        return "✅ 已${action}文件 $owner/$repo/$path\nCommit: ${result.commit?.sha ?: "?"} — $message"
    }

    private suspend fun callCreateIssue(args: JsonObject): String {
        val owner = args.stringArg("owner") ?: return "Error: 需要 owner"
        val repo = args.stringArg("repo") ?: return "Error: 需要 repo"
        val title = args.stringArg("title") ?: return "Error: 需要 title"
        val body = args["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val issue = api.createIssue(owner, repo, title, body)
        return "✅ Issue #${issue.number} 已创建: ${issue.title}\nURL: ${issue.html_url}"
    }

    private suspend fun callListIssues(args: JsonObject): String {
        val owner = args.stringArg("owner") ?: return "Error: 需要 owner"
        val repo = args.stringArg("repo") ?: return "Error: 需要 repo"
        val limit = args.intArg("limit")?.coerceIn(1, 100) ?: DEFAULT_ISSUE_LIMIT
        val issues = api.listIssues(owner, repo, perPage = limit)
        if (issues.isEmpty()) return "没有 open 状态的 Issues"
        return buildString {
            appendLine("$owner/$repo 的 Issues (${issues.size}):")
            issues.forEach { i ->
                val icon = if (i.state == "open") "🟢" else "🔴"
                appendLine("$icon #${i.number}: ${i.title}")
            }
        }
    }

    private suspend fun callSearchCode(args: JsonObject): String {
        val query = args.stringArg("query") ?: return "Error: 需要 query"
        // P1 修复（同步原生 GithubSearchCodeTool 的限定符前置校验）：
        // GitHub legacy /search/code 硬约束 —— query 必须含 repo:/user:/org:
        // 限定符，纯关键词必 422 "Validation Failed"。旧实现直接
        // api.searchCode(query, null, null, null)，MCP 路径纯关键词搜索必返
        // 422 错误（原生工具已修复但 MCP 路径未同步）。缺限定符时返回可自修复
        // 的 Error（省一次 HTTP 往返；模型下一轮带上 repo/org/user 重试即可）。
        val repo = args.stringArg("repo")
        val org = args.stringArg("org")
        val user = args.stringArg("user")
        val hasQualifierInQuery = query.contains(Regex("""\b(repo|user|org):"""))
        if (repo == null && org == null && user == null && !hasQualifierInQuery) {
            return "Error: GitHub code search requires a repo, org, or user scope. " +
                "Re-run with repo (\"owner/name\"), org, or user parameter. " +
                "Keyword-only global code search is not supported by the GitHub API."
        }
        val limit = args.intArg("limit")?.coerceIn(1, 100) ?: DEFAULT_SEARCH_LIMIT
        val result = api.searchCode(query, repo, org, user, perPage = limit)
        if (result.items.isEmpty()) return "未找到匹配代码"
        return buildString {
            appendLine("搜索 \"$query\" — ${result.total_count} 个结果:")
            result.items.forEach { item ->
                appendLine("📄 ${item.repository?.full_name ?: "?"}/${item.path}")
            }
        }
    }

    // ── 参数提取 ───────────────────────────────────────────────────

    private fun JsonObject.stringArg(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.intArg(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    // ── 工具清单（命名对齐官方 github-mcp-server） ──────────────────

    private companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val METHOD_NOT_FOUND = -32601
        const val DEFAULT_REPO_LIMIT = 15
        const val DEFAULT_ISSUE_LIMIT = 20
        const val DEFAULT_SEARCH_LIMIT = 10
        const val TOKEN_MISSING_HINT =
            "GitHub 未连接：请先在抽屉或输入栏 GitHub 按钮配置 Personal Access Token"

        val TOOL_NAMES = listOf(
            "get_me", "list_repositories", "get_file_contents",
            "create_or_update_file", "create_issue", "list_issues", "search_code"
        )

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
                put("name", "get_me")
                put("description", "获取当前已连接 GitHub 用户的信息")
                put("inputSchema", schema(JsonObject(emptyMap()), emptyList()))
            })
            add(buildJsonObject {
                put("name", "list_repositories")
                put("description", "列出 GitHub 仓库（默认当前用户，按最近更新排序）")
                put("inputSchema", schema(buildJsonObject {
                    put("username", prop("string", "GitHub 用户名，省略则查当前已连接用户"))
                    put("limit", prop("integer", "返回数量上限（1-100，默认 $DEFAULT_REPO_LIMIT）"))
                }, emptyList()))
            })
            add(buildJsonObject {
                put("name", "get_file_contents")
                put("description", "读取 GitHub 仓库中的文件内容")
                put("inputSchema", schema(buildJsonObject {
                    put("owner", prop("string", "仓库所有者（用户或组织）"))
                    put("repo", prop("string", "仓库名"))
                    put("path", prop("string", "文件路径"))
                    put("branch", prop("string", "分支名，省略则用默认分支"))
                }, listOf("owner", "repo", "path")))
            })
            add(buildJsonObject {
                put("name", "create_or_update_file")
                put("description", "在 GitHub 仓库中创建或更新文件（自动 commit）")
                put("inputSchema", schema(buildJsonObject {
                    put("owner", prop("string", "仓库所有者"))
                    put("repo", prop("string", "仓库名"))
                    put("path", prop("string", "文件路径"))
                    put("message", prop("string", "commit 信息"))
                    put("content", prop("string", "文件新内容（纯文本，base64 自动处理）"))
                    put("branch", prop("string", "目标分支，省略则用默认分支"))
                    put("sha", prop("string", "被替换文件的 blob SHA；新建文件时省略"))
                }, listOf("owner", "repo", "path", "message", "content")))
            })
            add(buildJsonObject {
                put("name", "create_issue")
                put("description", "在 GitHub 仓库中创建 Issue")
                put("inputSchema", schema(buildJsonObject {
                    put("owner", prop("string", "仓库所有者"))
                    put("repo", prop("string", "仓库名"))
                    put("title", prop("string", "Issue 标题"))
                    put("body", prop("string", "Issue 正文（Markdown）"))
                }, listOf("owner", "repo", "title")))
            })
            add(buildJsonObject {
                put("name", "list_issues")
                put("description", "列出仓库的 Issues（默认 open 状态）")
                put("inputSchema", schema(buildJsonObject {
                    put("owner", prop("string", "仓库所有者"))
                    put("repo", prop("string", "仓库名"))
                    put("limit", prop("integer", "返回数量上限（1-100，默认 $DEFAULT_ISSUE_LIMIT）"))
                }, listOf("owner", "repo")))
            })
            add(buildJsonObject {
                put("name", "search_code")
                put("description", "在 GitHub 中搜索代码（需要 repo/org/user 范围限定，纯关键词搜索会被 GitHub API 拒绝）")
                put("inputSchema", schema(buildJsonObject {
                    put("query", prop("string", "搜索关键词（GitHub 代码搜索语法）"))
                    put("repo", prop("string", "仓库限定符 'owner/name'（推荐的作用范围）"))
                    put("org", prop("string", "组织限定符 —— 搜索该组织全部仓库"))
                    put("user", prop("string", "用户限定符 —— 搜索该用户全部仓库"))
                    put("limit", prop("integer", "返回数量上限（1-100，默认 $DEFAULT_SEARCH_LIMIT）"))
                }, listOf("query")))
            })
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
//  启动预置 + 自动连接
// ══════════════════════════════════════════════════════════════════════

/**
 * 内置 GitHub MCP 的启动器：幂等预置配置 + 后台自动连接。
 *
 * 由 app 的 McpModule 在 `provideMcpManager` 里调用（@Provides 副作用模式，
 * 与 AttachmentModule 触发 schedulePeriodicCleanup 相同）—— McpManager 是
 * @Singleton 懒创建，首次注入（聊天页/工具注册表/市场页）即触发本逻辑。
 *
 * ensure/connect 是挂起函数，放到自持 IO scope 执行，不阻塞注入线程；
 * 连接失败只记日志不重试（内置 transport 握手是纯进程内 JSON 构造，实际不会失败；
 * 真正的 GitHub 网络/鉴权错误发生在 tools/call 时，会以友好文本返回给模型）。
 */
object BuiltinGithubMcpBootstrap {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureAndConnect(manager: McpManager) {
        scope.launch {
            manager.ensureBuiltinServer(BuiltinGithubMcpServer.config()).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinGithubMcp",
                    "预置内置 GitHub MCP 失败: ${it.message}"
                )
                return@launch
            }
            val enabled = manager.getConfigs()
                .any { it.name == BuiltinGithubMcpServer.ID && it.enabled }
            if (!enabled) return@launch   // 用户明确禁用：尊重偏好，不自动连接
            manager.connect(BuiltinGithubMcpServer.ID).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinGithubMcp",
                    "连接内置 GitHub MCP 失败: ${it.message}"
                )
            }
        }
    }
}
