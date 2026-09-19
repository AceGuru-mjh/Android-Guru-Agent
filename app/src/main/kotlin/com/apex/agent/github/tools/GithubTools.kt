package com.apex.agent.github.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.github.GithubApiException
import com.apex.agent.github.GithubApiService
import kotlinx.serialization.json.*

// ═══ P0 修复（工具大面积失败）═══════════════════════════════════════════
// 1. 旧实现所有工具用严格 `Json.parseToJsonElement(arguments).jsonObject` 且无
//    try/catch：模型输出参数带格式瑕疵（单引号/裸 key/外层引号）时直接抛异常，
//    被 SafeAgentTool 兜底成笼统的 "Error: 工具执行失败。..."，模型无从自修复。
//    现统一走 [parseArgs]：解析失败返回带原始参数回显的 Error 文本（模型可见
//    自己发错了什么、下一轮自纠）；字符串字段用 contentOrNull 容忍非字符串值。
// 2. GithubApiException 不再裸抛：映射为带状态码 + 修复指引的 Error 文本
//    （如 401 → 检查 Token；422 → code search 需要 repo/user/org 限定符）。
// 3. github_search_code：GitHub legacy /search/code API **强制**要求 query
//    至少含一个 repo:/user:/org: 限定符，纯关键词搜索必 422。旧实现 repo
//    是可选参数且描述只写"在 GitHub 中搜索代码"，模型几乎必然只传关键词
//    → 必败。现在描述明确约束 + 缺限定符时直接返回可自修复的 Error（省一次
//    HTTP 往返），并新增 org/user 参数覆盖全部三种限定方式。

/** 宽容解析工具参数：失败时返回 null（调用方转成带指引的 Error 文本）。 */
private fun parseArgs(arguments: String): JsonObject? = try {
    Json.parseToJsonElement(arguments).jsonObject
} catch (e: Exception) {
    null
}

/** 统一的参数解析失败文案：回显原始参数，模型可看到错误并自纠。 */
private fun argsParseError(arguments: String): String =
    "Error: invalid arguments — expected a JSON object but got: " +
        "${arguments.take(200)}. Fix the JSON and retry."

/** 统一读取字符串字段：非字符串值（数字/布尔/嵌套）时取其文本表示而非抛异常。 */
private fun JsonObject.stringOf(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** GithubApiException → 模型可自修复的 Error 文本（带状态码与修复指引）。 */
private fun githubError(e: GithubApiException): String = buildString {
    append("Error: GitHub API ${e.code ?: "network"}: ${e.message}")
    when (e.code) {
        401 -> append(". Token invalid or expired — ask the user to re-connect GitHub in settings.")
        403 -> append(". Rate limited or forbidden — slow down; if it persists the token may lack scope.")
        404 -> append(". Check owner/repo spelling and token access to private repos.")
        422 -> append(". The GitHub code-search API requires at least one repo:/user:/org: " +
            "qualifier in the query — pass repo (or org/user) explicitly.")
        429 -> append(". Rate limited — wait before retrying.")
    }
}

class GithubGetUserTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_get_user"
    override val name = "GitHub User Info"
    override val description = "获取当前已连接 GitHub 用户的信息"
    override val parametersSchema = """{"type":"object","properties":{},"required":[]}"""
    override suspend fun execute(arguments: String): String {
        return try {
            val user = api.getCurrentUser()
            "GitHub 用户: ${user.login}\n名称: ${user.name ?: "N/A"}\n公开仓库: ${user.public_repos}"
        } catch (e: GithubApiException) {
            githubError(e)
        }
    }
}

class GithubListReposTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_list_repos"
    override val name = "List GitHub Repos"
    override val description = "列出 GitHub 仓库（默认当前用户）"
    override val parametersSchema = """{"type":"object","properties":{"username":{"type":"string"},"limit":{"type":"integer"}},"required":[]}"""
    override suspend fun execute(arguments: String): String {
        val json = parseArgs(arguments) ?: return argsParseError(arguments)
        return try {
            val username = json.stringOf("username")
            val limit = (json["limit"] as? JsonPrimitive)?.let { runCatching { it.content.toInt() }.getOrNull() } ?: 15
            val repos = api.listRepos(username, perPage = limit)
            if (repos.isEmpty()) return "没有找到仓库"
            buildString {
                appendLine("找到 ${repos.size} 个仓库:")
                repos.forEach { r ->
                    val lock = if (r.private) "🔒" else "📂"
                    appendLine("$lock ${r.full_name} | ${r.language ?: "N/A"} | ⭐${r.stargazers_count}")
                    r.description?.let { appendLine("   $it") }
                }
            }
        } catch (e: GithubApiException) {
            githubError(e)
        }
    }
}

class GithubReadFileTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_read_file"
    override val name = "Read GitHub File"
    override val description = "读取 GitHub 仓库中的文件内容"
    override val parametersSchema = """{"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"},"path":{"type":"string"},"branch":{"type":"string"}},"required":["owner","repo","path"]}"""
    override suspend fun execute(arguments: String): String {
        val json = parseArgs(arguments) ?: return argsParseError(arguments)
        val owner = json.stringOf("owner") ?: return "Error: 需要 owner"
        val repo = json.stringOf("repo") ?: return "Error: 需要 repo"
        val path = json.stringOf("path") ?: return "Error: 需要 path"
        val branch = json.stringOf("branch")
        return try {
            val file = api.getFileContent(owner, repo, path, branch)
            val content = file.decoded()
            buildString {
                appendLine("📄 $owner/$repo/$path (${file.size} bytes)")
                if (content.length > 3000) { append(content.take(3000)); appendLine("\n[截断，共${content.length}字符]") }
                else append(content)
            }
        } catch (e: GithubApiException) {
            githubError(e)
        }
    }
}

class GithubWriteFileTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_write_file"
    override val name = "Write GitHub File"
    override val description = "在 GitHub 仓库中创建或更新文件（自动 commit）"
    override val parametersSchema = """{"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"},"path":{"type":"string"},"content":{"type":"string"},"message":{"type":"string"},"branch":{"type":"string"}},"required":["owner","repo","path","content","message"]}"""
    override suspend fun execute(arguments: String): String {
        val json = parseArgs(arguments) ?: return argsParseError(arguments)
        val owner = json.stringOf("owner") ?: return "Error: 需要 owner"
        val repo = json.stringOf("repo") ?: return "Error: 需要 repo"
        val path = json.stringOf("path") ?: return "Error: 需要 path"
        val content = json.stringOf("content") ?: return "Error: 需要 content"
        val message = json.stringOf("message") ?: "Update $path"
        val branch = json.stringOf("branch")
        // 空值校验：避免拼接出 `/repos//contents/` 这种 URL 触发 GitHub 404/422。
        if (owner.isBlank()) return "Error: owner 不能为空"
        if (repo.isBlank()) return "Error: repo 不能为空"
        if (path.isBlank()) return "Error: path 不能为空"
        // TODO（private-fork 写保护）：若启用相关 config flag，应在此处调用
        // api.listBranches 或 getFileContent 探测 repo.private=true，并要求用户二次确认。
        // 当前没有该 config flag，先保留默认放行行为。
        var existingSha: String? = null
        try {
            existingSha = api.getFileContent(owner, repo, path, branch).sha
        } catch (e: GithubApiException) {
            // 404 = 文件确实不存在 → existingSha 保持 null，走 create 路径；
            // 其他状态码（5xx/422/网络层异常包装）必须向上抛出，否则会被当作"文件不存在"
            // 走无 sha create，被 GitHub 422 拒绝（"sha missing"），且真实错误被静默吞掉。
            if (e.code != 404) {
                // P0 修复：❌ 前缀会被 v3 成败管线误判为成功，统一 Error: 前缀。
                return githubError(e)
            }
        } catch (e: Exception) {
            // 网络/解码异常不应被误判为"文件不存在"（同上理由）。
            return "Error: 检查文件状态失败: ${e.message}"
        }
        return try {
            api.createOrUpdateFile(owner, repo, path, content, message, branch, existingSha)
            val action = if (existingSha != null) "更新" else "创建"
            "✅ 已${action}文件 $owner/$repo/$path\nCommit: $message"
        } catch (e: GithubApiException) {
            githubError(e)
        }
    }
}

class GithubCreateIssueTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_create_issue"
    override val name = "Create GitHub Issue"
    override val description = "在 GitHub 仓库中创建 Issue"
    override val parametersSchema = """{"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"},"title":{"type":"string"},"body":{"type":"string"},"labels":{"type":"array","items":{"type":"string"}}},"required":["owner","repo","title"]}"""
    override suspend fun execute(arguments: String): String {
        val json = parseArgs(arguments) ?: return argsParseError(arguments)
        val owner = json.stringOf("owner") ?: return "Error: 需要 owner"
        val repo = json.stringOf("repo") ?: return "Error: 需要 repo"
        val title = json.stringOf("title") ?: return "Error: 需要 title"
        val body = json.stringOf("body") ?: ""
        val labels = (json["labels"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return try {
            val issue = api.createIssue(owner, repo, title, body, labels)
            "✅ Issue #${issue.number} 已创建: ${issue.title}\nURL: ${issue.html_url}"
        } catch (e: GithubApiException) {
            githubError(e)
        }
    }
}

class GithubListIssuesTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_list_issues"
    override val name = "List GitHub Issues"
    override val description = "列出仓库的 Issues"
    override val parametersSchema = """{"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"},"state":{"type":"string","enum":["open","closed","all"]}},"required":["owner","repo"]}"""
    override suspend fun execute(arguments: String): String {
        val json = parseArgs(arguments) ?: return argsParseError(arguments)
        val owner = json.stringOf("owner") ?: return "Error: 需要 owner"
        val repo = json.stringOf("repo") ?: return "Error: 需要 repo"
        val state = json.stringOf("state") ?: "open"
        return try {
            val issues = api.listIssues(owner, repo, state)
            if (issues.isEmpty()) return "没有 ${state} 状态的 Issues"
            buildString {
                appendLine("$owner/$repo 的 Issues (${issues.size}):")
                issues.forEach { i ->
                    val icon = if (i.state == "open") "🟢" else "🔴"
                    appendLine("$icon #${i.number}: ${i.title}")
                }
            }
        } catch (e: GithubApiException) {
            githubError(e)
        }
    }
}

class GithubSearchCodeTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_search_code"
    override val name = "Search GitHub Code"
    override val description = """
        Search code in GitHub repositories via the GitHub code-search API.

        IMPORTANT: the GitHub code-search API REQUIRES the query to be scoped to
        at least one repo, org, or user. Always pass one of:
        - repo: "owner/name" to search one repository
        - org: "org-name" to search all repos of an organization
        - user: "username" to search all repos of a user
        Keyword-only global search will be rejected by the API (HTTP 422).

        To browse a repo's file tree, prefer github_read_file on specific paths.

        Examples:
        - {"query": "WebSearchTool", "repo": "AceGuru-mjh/Android-Guru-Agent"}
        - {"query": "fun searchCode", "org": "square"}
        - {"query": "OkHttpClient builder", "user": "square"}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Code search keywords (e.g. class name, function name)"},
                "repo": {"type": "string", "description": "Repository qualifier 'owner/name' (recommended scope)"},
                "org": {"type": "string", "description": "Organization qualifier — searches all its repos"},
                "user": {"type": "string", "description": "User qualifier — searches all their repos"}
            },
            "required": ["query"]
        }
    """.trimIndent()
    override suspend fun execute(arguments: String): String {
        val json = parseArgs(arguments) ?: return argsParseError(arguments)
        val query = json.stringOf("query") ?: return "Error: 需要 query"
        val repo = json.stringOf("repo")
        val org = json.stringOf("org")
        val user = json.stringOf("user")

        // GitHub legacy /search/code 硬约束：query 必须含 repo:/user:/org: 限定符，
        // 纯关键词必 422 "Validation Failed"。缺限定符时直接返回可自修复的 Error
        // （省一次 HTTP 往返；模型下一轮带上 repo/org/user 重试即可）。
        val hasQualifierInQuery = query.contains(Regex("""\b(repo|user|org):"""))
        if (repo == null && org == null && user == null && !hasQualifierInQuery) {
            return "Error: GitHub code search requires a repo, org, or user scope. " +
                "Re-run with repo (\"owner/name\"), org, or user parameter. " +
                "Keyword-only global code search is not supported by the GitHub API."
        }

        return try {
            val result = api.searchCode(query, repo, org, user)
            if (result.items.isEmpty()) return "未找到匹配代码"
            buildString {
                appendLine("搜索 \"$query\" — ${result.total_count} 个结果:")
                result.items.take(10).forEach { item ->
                    appendLine("📄 ${item.repository?.full_name ?: "?"}/${item.path}")
                    if (item.html_url.isNotBlank()) appendLine("   ${item.html_url}")
                }
            }
        } catch (e: GithubApiException) {
            githubError(e)
        }
    }
}
