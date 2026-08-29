package com.apex.agent.github.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.github.GithubApiException
import com.apex.agent.github.GithubApiService
import kotlinx.serialization.json.*

class GithubGetUserTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_get_user"
    override val name = "GitHub User Info"
    override val description = "获取当前已连接 GitHub 用户的信息"
    override val parametersSchema = """{"type":"object","properties":{},"required":[]}"""
    override suspend fun execute(arguments: String): String {
        val user = api.getCurrentUser()
        return "GitHub 用户: ${user.login}\n名称: ${user.name ?: "N/A"}\n公开仓库: ${user.public_repos}"
    }
}

class GithubListReposTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_list_repos"
    override val name = "List GitHub Repos"
    override val description = "列出 GitHub 仓库"
    override val parametersSchema = """{"type":"object","properties":{"username":{"type":"string"},"limit":{"type":"integer"}},"required":[]}"""
    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val username = json["username"]?.jsonPrimitive?.contentOrNull
        val limit = json["limit"]?.jsonPrimitive?.intOrNull ?: 15
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
}

class GithubReadFileTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_read_file"
    override val name = "Read GitHub File"
    override val description = "读取 GitHub 仓库中的文件内容"
    override val parametersSchema = """{"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"},"path":{"type":"string"},"branch":{"type":"string"}},"required":["owner","repo","path"]}"""
    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val owner = json["owner"]?.jsonPrimitive?.content ?: return "Error: 需要 owner"
        val repo = json["repo"]?.jsonPrimitive?.content ?: return "Error: 需要 repo"
        val path = json["path"]?.jsonPrimitive?.content ?: return "Error: 需要 path"
        val branch = json["branch"]?.jsonPrimitive?.contentOrNull
        val file = api.getFileContent(owner, repo, path, branch)
        val content = file.decoded()
        return buildString {
            appendLine("📄 $owner/$repo/$path (${file.size} bytes)")
            if (content.length > 3000) { append(content.take(3000)); appendLine("\n[截断，共${content.length}字符]") }
            else append(content)
        }
    }
}

class GithubWriteFileTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_write_file"
    override val name = "Write GitHub File"
    override val description = "在 GitHub 仓库中创建或更新文件（自动 commit）"
    override val parametersSchema = """{"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"},"path":{"type":"string"},"content":{"type":"string"},"message":{"type":"string"},"branch":{"type":"string"}},"required":["owner","repo","path","content","message"]}"""
    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val owner = json["owner"]?.jsonPrimitive?.content ?: return "Error: 需要 owner"
        val repo = json["repo"]?.jsonPrimitive?.content ?: return "Error: 需要 repo"
        val path = json["path"]?.jsonPrimitive?.content ?: return "Error: 需要 path"
        val content = json["content"]?.jsonPrimitive?.content ?: return "Error: 需要 content"
        val message = json["message"]?.jsonPrimitive?.content ?: "Update $path"
        val branch = json["branch"]?.jsonPrimitive?.contentOrNull
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
                return "❌ 检查文件状态失败 (HTTP ${e.code ?: "?"}): ${e.message}"
            }
        } catch (e: Exception) {
            // 网络/解码异常不应被误判为"文件不存在"（同上理由）。
            return "❌ 检查文件状态失败: ${e.message}"
        }
        api.createOrUpdateFile(owner, repo, path, content, message, branch, existingSha)
        val action = if (existingSha != null) "更新" else "创建"
        return "✅ 已${action}文件 $owner/$repo/$path\nCommit: $message"
    }
}

class GithubCreateIssueTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_create_issue"
    override val name = "Create GitHub Issue"
    override val description = "在 GitHub 仓库中创建 Issue"
    override val parametersSchema = """{"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"},"title":{"type":"string"},"body":{"type":"string"},"labels":{"type":"array","items":{"type":"string"}}},"required":["owner","repo","title"]}"""
    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val owner = json["owner"]?.jsonPrimitive?.content ?: return "Error: 需要 owner"
        val repo = json["repo"]?.jsonPrimitive?.content ?: return "Error: 需要 repo"
        val title = json["title"]?.jsonPrimitive?.content ?: return "Error: 需要 title"
        val body = json["body"]?.jsonPrimitive?.content ?: ""
        val labels = json["labels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val issue = api.createIssue(owner, repo, title, body, labels)
        return "✅ Issue #${issue.number} 已创建: ${issue.title}\nURL: ${issue.html_url}"
    }
}

class GithubListIssuesTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_list_issues"
    override val name = "List GitHub Issues"
    override val description = "列出仓库的 Issues"
    override val parametersSchema = """{"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"},"state":{"type":"string","enum":["open","closed","all"]}},"required":["owner","repo"]}"""
    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val owner = json["owner"]?.jsonPrimitive?.content ?: return "Error: 需要 owner"
        val repo = json["repo"]?.jsonPrimitive?.content ?: return "Error: 需要 repo"
        val state = json["state"]?.jsonPrimitive?.content ?: "open"
        val issues = api.listIssues(owner, repo, state)
        if (issues.isEmpty()) return "没有 ${state} 状态的 Issues"
        return buildString {
            appendLine("$owner/$repo 的 Issues (${issues.size}):")
            issues.forEach { i ->
                val icon = if (i.state == "open") "🟢" else "🔴"
                appendLine("$icon #${i.number}: ${i.title}")
            }
        }
    }
}

class GithubSearchCodeTool(private val api: GithubApiService) : AgentTool {
    override val id = "github_search_code"
    override val name = "Search GitHub Code"
    override val description = "在 GitHub 中搜索代码"
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string"},"repo":{"type":"string"}},"required":["query"]}"""
    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val query = json["query"]?.jsonPrimitive?.content ?: return "Error: 需要 query"
        val repo = json["repo"]?.jsonPrimitive?.contentOrNull
        val result = api.searchCode(query, repo)
        if (result.items.isEmpty()) return "未找到匹配代码"
        return buildString {
            appendLine("搜索 \"$query\" — ${result.total_count} 个结果:")
            result.items.take(10).forEach { item ->
                appendLine("📄 ${item.repository?.full_name ?: "?"}/${item.path}")
            }
        }
    }
}
