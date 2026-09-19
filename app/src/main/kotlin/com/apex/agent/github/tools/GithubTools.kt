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
//
// ═══ 描述双语化 + 连接引导（模型不选用 GitHub 工具的根因修复）══════════
// 4. 系统提示词的工具清单只取 description 首行且截断 160 字符——旧版全中文
//    短句（"获取当前已连接 GitHub 用户的信息"）在 100+ 工具中辨识度极低。
//    现首行为英文（≤160 字符，一句话功能 + 使用时机），与 github_* 语义对齐。
// 5. code=null（未连接 GitHub）/401/403 的错误文案升级为可行动引导：告诉
//    模型应指引到 设置 → 连接器 → GitHub 配置 Token，连接后先调
//    github_get_user 验证——旧版只说"请先配置 Token"，模型无从引导。

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
        // code=null = authHeader() 抛出的"未连接"（Token 未配置）——最常见失败。
        null -> append(
            ". GitHub is NOT connected. Tell the user: 打开 设置 → 连接器 → GitHub " +
                "配置 Personal Access Token（Settings → Connectors → GitHub）。" +
                "连接后先调用 github_get_user 验证再继续本任务。"
        )
        401 -> append(
            ". Token invalid or expired — ask the user to re-connect GitHub " +
                "(设置 → 连接器 → GitHub), then verify with github_get_user."
        )
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
    override val description = "Get the authenticated GitHub user profile. Call this FIRST to verify the GitHub connection works. 先验证 GitHub 连接。"
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
    override val description = "List GitHub repositories of a user (default: the authenticated user). Returns full_name, language, stars. 列出 GitHub 仓库。"
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
    override val description = "Read a file from a GitHub repo (owner/repo/path). Truncated to 3000 chars. Use branch for non-default branch. 读取远程仓库文件。"
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
    override val description = "Create or update a file in a GitHub repo with an automatic commit. ⚠️HIGH-RISK: real commit to the remote branch. 写入并 commit。"
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
    override val description = "Create an issue in a GitHub repo (title/body/labels). ⚠️HIGH-RISK: creates a real issue. 创建 Issue。"
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
    override val description = "List issues of a GitHub repo (state: open/closed/all). 列出仓库 Issues。"
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
    override val description = "Search code INSIDE GitHub repos. Requires repo/org/user scope (keyword-only search is rejected by the API). 在仓库内搜索代码。"
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

// ═══ 新增工具（描述双语化配套）══════════════════════════════════════════
// github_list_branches：写入非默认分支前探查（api.listBranches 此前无工具入口）；
// github_search_repos：按关键词找仓库（search_code 只能搜代码内文本，找仓库
// 需 /search/repositories）——模型找仓库的常规入口。

class GithubListBranchesTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_list_branches"
    override val name = "List GitHub Branches"
    override val description = "List branches of a GitHub repo with head commit sha. Use before github_write_file targeting a non-default branch. 列出分支。"
    override val parametersSchema = """{"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"}},"required":["owner","repo"]}"""
    override suspend fun execute(arguments: String): String {
        val json = parseArgs(arguments) ?: return argsParseError(arguments)
        val owner = json.stringOf("owner") ?: return "Error: 需要 owner"
        val repo = json.stringOf("repo") ?: return "Error: 需要 repo"
        if (owner.isBlank() || repo.isBlank()) return "Error: owner/repo 不能为空"
        return try {
            val branches = api.listBranches(owner, repo)
            if (branches.isEmpty()) return "没有分支"
            buildString {
                appendLine("$owner/$repo 的分支 (${branches.size}):")
                branches.forEach { b ->
                    val sha = b.commit?.sha?.take(8) ?: "?"
                    appendLine("📌 ${b.name} @ $sha")
                }
            }
        } catch (e: GithubApiException) {
            githubError(e)
        }
    }
}

class GithubSearchReposTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_search_repos"
    override val name = "Search GitHub Repositories"
    override val description = "Search GitHub repositories by keyword (sorted by stars). Returns full_name, description, stars, language. Use to find repos before reading files. 按关键词找仓库。"
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"Search keywords, e.g. 'kotlin agent'"},"limit":{"type":"integer","description":"Max results (default 10)"}},"required":["query"]}"""
    override suspend fun execute(arguments: String): String {
        val json = parseArgs(arguments) ?: return argsParseError(arguments)
        val query = json.stringOf("query") ?: return "Error: 需要 query"
        if (query.isBlank()) return "Error: query 不能为空"
        val limit = (json["limit"] as? JsonPrimitive)?.let { runCatching { it.content.toInt() }.getOrNull() } ?: 10
        return try {
            val result = api.searchRepositories(query, perPage = limit.coerceIn(1, 30))
            if (result.items.isEmpty()) return "未找到匹配仓库"
            buildString {
                appendLine("搜索 \"$query\" — 共 ${result.total_count} 个仓库，显示前 ${result.items.size}:")
                result.items.forEach { r ->
                    appendLine("⭐${r.stargazers_count} ${r.full_name} [${r.language ?: "N/A"}]")
                    r.description?.let { appendLine("   ${it.take(60)}") }
                }
            }
        } catch (e: GithubApiException) {
            githubError(e)
        }
    }
}
