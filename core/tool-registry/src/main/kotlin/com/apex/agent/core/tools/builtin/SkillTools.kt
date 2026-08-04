package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Skill 搜索工具
 *
 * Agent 用此工具在网上搜索可用的 Skill。搜索 GitHub 的 apex-skill 仓库
 * 和本地内置模板。返回结果列表，用 skill_install 安装。
 */
class SkillSearchTool(
    private val httpClient: OkHttpClient
) : AgentTool {

    override val id = "skill_search"
    override val name = "Search Skills"
    override val description = """
        Search for available skills online.
        Searches the Apex Skill Registry and community repositories.
        Returns a list of matching skills with download URLs.

        Examples:
        - {"query": "web scraping"}
        - {"query": "data analysis python"}
        - {"query": "github automation"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Search query for skills"},
                "source": {"type": "string", "enum": ["registry", "github", "all"], "description": "Search source (default: all)"}
            },
            "required": ["query"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val query = json["query"]?.jsonPrimitive?.content ?: return "Error: 'query' required"
        val source = json["source"]?.jsonPrimitive?.content ?: "all"

        val results = mutableListOf<String>()

        // 搜索 GitHub（apex-skills 仓库）
        if (source == "all" || source == "github") {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "https://api.github.com/search/repositories?q=apex-skill+$encoded&per_page=5"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "ApexAgent/1.0")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val searchResult = Json.parseToJsonElement(body).jsonObject
                    val items = searchResult["items"]?.jsonArray ?: return emptyResults(query)

                    items.forEach { item ->
                        val obj = item.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.content ?: ""
                        val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                        val htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: ""
                        results.add("• $name\n  $desc\n  URL: $htmlUrl")
                    }
                }
            } catch (_: Exception) { /* 网络失败时降级到内置模板 */ }
        }

        // 搜索内置模板
        if (source == "all" || source == "registry") {
            results.addAll(getBuiltinSkillTemplates(query))
        }

        if (results.isEmpty()) {
            return "No skills found for '$query'. You can create one with skill_create, or search the web with web_search."
        }

        return buildString {
            appendLine("Found ${results.size} skills for '$query':")
            appendLine("---")
            results.forEach { appendLine(it); appendLine() }
            appendLine("Use skill_install with a URL or skill_create to make your own.")
        }
    }

    private fun emptyResults(query: String): String =
        "No skills found for '$query'. Try a different query or use skill_create."

    private fun getBuiltinSkillTemplates(query: String): List<String> {
        val templates = mapOf(
            "web" to "• web_scraper (内置模板)\n  网页数据提取\n  安装: skill_install({\"source\":\"template\",\"template\":\"web_scraper\"})",
            "file" to "• file_organizer (内置模板)\n  文件自动分类整理\n  安装: skill_install({\"source\":\"template\",\"template\":\"file_organizer\"})",
            "code" to "• code_runner (内置模板)\n  代码执行与调试\n  安装: skill_install({\"source\":\"template\",\"template\":\"code_runner\"})",
            "data" to "• data_analyzer (内置模板)\n  数据分析与可视化\n  安装: skill_install({\"source\":\"template\",\"template\":\"data_analyzer\"})"
        )
        return templates.filter { (key, _) -> query.contains(key, ignoreCase = true) }.values.toList()
    }
}

/**
 * Skill 安装工具
 *
 * 三种安装来源：
 * - "url"       — 从 URL 下载 skill JSON
 * - "template"  — 使用内置模板（web_scraper / file_organizer / code_runner / data_analyzer）
 * - "content"   — 直接传入 skill JSON 内容
 */
class SkillInstallTool(
    private val skillRegistry: SkillRegistry,
    private val httpClient: OkHttpClient
) : AgentTool {

    override val id = "skill_install"
    override val name = "Install Skill"
    override val description = """
        Install a skill from a URL, local file, or built-in template.
        After installation, the skill's tools are automatically registered and configured.

        Sources:
        - URL: Download skill JSON from a web URL
        - Template: Use a built-in template (web_scraper, file_organizer, code_runner, data_analyzer)
        - Content: Directly provide the skill JSON content

        Examples:
        - {"source": "url", "url": "https://example.com/skill.json"}
        - {"source": "template", "template": "web_scraper"}
        - {"source": "content", "content": "{...skill json...}"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "source": {"type": "string", "enum": ["url", "template", "content"], "description": "Installation source"},
                "url": {"type": "string", "description": "URL to download skill from"},
                "template": {"type": "string", "description": "Built-in template name"},
                "content": {"type": "string", "description": "Skill JSON content directly"}
            },
            "required": ["source"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val source = json["source"]?.jsonPrimitive?.content ?: return "Error: 'source' required"

        val manifestJson = when (source) {
            "url" -> {
                val url = json["url"]?.jsonPrimitive?.content ?: return "Error: 'url' required"
                downloadSkill(url)
            }
            "template" -> {
                val template = json["template"]?.jsonPrimitive?.content ?: return "Error: 'template' required"
                getTemplate(template)
            }
            "content" -> {
                json["content"]?.jsonPrimitive?.content ?: return "Error: 'content' required"
            }
            else -> return "Error: Unknown source '$source'"
        }

        if (manifestJson.startsWith("Error")) return manifestJson

        val result = skillRegistry.install(manifestJson)

        return result.fold(
            onSuccess = { manifest ->
                buildString {
                    appendLine("✅ Skill installed successfully!")
                    appendLine("  Name: ${manifest.name}")
                    appendLine("  ID: ${manifest.id}")
                    appendLine("  Version: ${manifest.version}")
                    appendLine("  Tools added: ${manifest.tools.map { it.id }.joinToString(", ").ifEmpty { "none" }}")
                    appendLine("  Auto-config: ${manifest.configuration.autoSetup.size} actions executed")
                    if (manifest.promptInjection != null) {
                        appendLine("  Prompt injection: active")
                    }
                    appendLine()
                    appendLine("The skill is now active. Restart the agent for its tools to register in the ToolRegistry.")
                }
            },
            onFailure = { e -> "❌ Skill installation failed: ${e.message}" }
        )
    }

    private suspend fun downloadSkill(url: String): String {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ApexAgent/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return "Error: HTTP ${response.code} downloading skill"
            }
            response.body?.string() ?: "Error: Empty response"
        } catch (e: Exception) {
            "Error: Download failed - ${e.message}"
        }
    }

    private fun getTemplate(name: String): String {
        return when (name) {
            "web_scraper" -> WEB_SCRAPER_TEMPLATE
            "file_organizer" -> FILE_ORGANIZER_TEMPLATE
            "code_runner" -> CODE_RUNNER_TEMPLATE
            "data_analyzer" -> DATA_ANALYZER_TEMPLATE
            else -> "Error: Unknown template '$name'. Available: web_scraper, file_organizer, code_runner, data_analyzer"
        }
    }

    companion object {
        val WEB_SCRAPER_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "web_scraper",
  "name": "网页数据爬取",
  "version": "1.0.0",
  "description": "从网页提取结构化数据",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["web_fetch", "write_file"]},
  "tools": [{
    "id": "web_scrape",
    "name": "Scrape Web Data",
    "description": "Fetches URL and returns its text content for further extraction.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "web_fetch", "args": {"url": "{{url}}"}}
    ]}
  }],
  "configuration": {
    "autoSetup": [
      {"action": "create_directory", "path": "./scrape_output"}
    ]
  }
}
""".trimIndent()

        val FILE_ORGANIZER_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "file_organizer",
  "name": "文件自动整理",
  "version": "1.0.0",
  "description": "按类型自动分类整理文件（图片/文档/视频/音乐/归档）",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["list_files", "shell_execute"]},
  "tools": [{
    "id": "organize_files",
    "name": "Organize Files",
    "description": "Organize files in a directory by type (images, docs, videos, etc.)",
    "parameters": "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Directory to organize\"}},\"required\":[\"path\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "list_files", "args": {"path": "{{path}}"}},
      {"tool": "shell_execute", "args": {}}
    ]}
  }],
  "configuration": {"autoSetup": []}
}
""".trimIndent()

        val CODE_RUNNER_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "code_runner",
  "name": "代码运行器",
  "version": "1.0.0",
  "description": "编写并运行代码，自动修复错误",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["write_file", "shell_execute", "read_file"]},
  "tools": [{
    "id": "run_code",
    "name": "Run Code",
    "description": "Write code to a file and execute it. Returns output or errors.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"language\":{\"type\":\"string\",\"enum\":[\"python\",\"shell\",\"node\"]},\"code\":{\"type\":\"string\",\"description\":\"Code to run\"}},\"required\":[\"language\",\"code\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "write_file", "args": {"path": "./code_output/run.py", "content": "{{code}}"}},
      {"tool": "shell_execute", "args": {}}
    ]}
  }],
  "configuration": {"autoSetup": [
    {"action": "create_directory", "path": "./code_output"}
  ]}
}
""".trimIndent()

        val DATA_ANALYZER_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "data_analyzer",
  "name": "数据分析",
  "version": "1.0.0",
  "description": "分析CSV/JSON数据，生成统计报告",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["read_file", "shell_execute", "write_file"]},
  "tools": [{
    "id": "analyze_data",
    "name": "Analyze Data",
    "description": "Analyze a data file (CSV/JSON) and generate statistics summary.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"file\":{\"type\":\"string\",\"description\":\"Data file path\"}},\"required\":[\"file\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "read_file", "args": {"path": "{{file}}", "max_lines": 10}},
      {"tool": "shell_execute", "args": {}}
    ]}
  }],
  "configuration": {"autoSetup": []}
}
""".trimIndent()
    }
}

/**
 * Skill 创建工具
 *
 * Agent 用此工具自己编写新 Skill。
 */
class SkillCreateTool(
    private val skillRegistry: SkillRegistry
) : AgentTool {

    override val id = "skill_create"
    override val name = "Create Skill"
    override val description = """
        Create a new skill from scratch.
        You define the skill's id, name, description, and optional prompt injection.
        The skill is immediately installed and available.

        Use this when:
        - No existing skill matches the need
        - User asks you to create a custom automation
        - You identify a repeatable pattern worth saving
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "id": {"type": "string", "description": "Unique skill ID (snake_case)"},
                "name": {"type": "string", "description": "Display name"},
                "description": {"type": "string", "description": "What the skill does"},
                "prompt_injection": {"type": "string", "description": "Optional system prompt to inject"}
            },
            "required": ["id", "name", "description"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val id = json["id"]?.jsonPrimitive?.content ?: return "Error: 'id' required"
        val name = json["name"]?.jsonPrimitive?.content ?: return "Error: 'name' required"
        val description = json["description"]?.jsonPrimitive?.content ?: return "Error: 'description' required"
        val promptInjection = json["prompt_injection"]?.jsonPrimitive?.contentOrNull

        // 构建最小 manifest
        val manifest = buildString {
            append("{")
            append("\"schema\":\"apex-skill-v1\",")
            append("\"id\":\"${escape(id)}\",")
            append("\"name\":\"${escape(name)}\",")
            append("\"version\":\"1.0.0\",")
            append("\"description\":\"${escape(description)}\",")
            append("\"author\":\"agent-created\",")
            if (promptInjection != null) {
                append("\"promptInjection\":\"${escape(promptInjection)}\",")
            }
            append("\"tools\":[],")
            append("\"configuration\":{\"autoSetup\":[]}")
            append("}")
        }

        val result = skillRegistry.install(manifest)
        return result.fold(
            onSuccess = { "✅ Skill '$name' created and installed. It's now active." },
            onFailure = { "❌ Failed to create skill: ${it.message}" }
        )
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * Skill 列表工具
 */
class SkillListTool(
    private val skillRegistry: SkillRegistry
) : AgentTool {

    override val id = "skill_list"
    override val name = "List Skills"
    override val description = "List all installed skills and their status."

    override val parametersSchema = """
        {"type": "object", "properties": {}, "required": []}
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val skills = skillRegistry.getInstalled()
        if (skills.isEmpty()) {
            return "No skills installed. Use skill_search to find skills or skill_create to make one."
        }

        return buildString {
            appendLine("Installed skills (${skills.size}):")
            appendLine("---")
            skills.forEach { skill ->
                val status = if (skill.enabled) "✅" else "⬜"
                appendLine("$status ${skill.manifest.name} (${skill.manifest.id}) v${skill.manifest.version}")
                appendLine("   ${skill.manifest.description}")
                appendLine("   Tools: ${skill.manifest.tools.map { it.id }.joinToString(", ").ifEmpty { "prompt-only" }}")
                appendLine()
            }
        }
    }
}

/**
 * Skill 卸载工具
 */
class SkillUninstallTool(
    private val skillRegistry: SkillRegistry
) : AgentTool {

    override val id = "skill_uninstall"
    override val name = "Uninstall Skill"
    override val description = "Remove an installed skill by ID."

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "skill_id": {"type": "string", "description": "Skill ID to uninstall"}
            },
            "required": ["skill_id"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val skillId = json["skill_id"]?.jsonPrimitive?.content ?: return "Error: 'skill_id' required"

        val success = skillRegistry.uninstall(skillId)
        return if (success) "✅ Skill '$skillId' uninstalled" else "Error: Skill '$skillId' not found"
    }
}
