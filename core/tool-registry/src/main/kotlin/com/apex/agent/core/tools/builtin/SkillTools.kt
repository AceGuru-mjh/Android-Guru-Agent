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
        // 关键词 → 模板 id 的映射。查询命中任一关键词即列出对应模板。
        // 命中规则采用 contains（ignoreCase），覆盖中英文同义词。
        val queryLower = query.lowercase()
        val keywordToTemplateId = listOf(
            // 原有 4 个
            "web"        to "web_scraper",
            "爬"          to "web_scraper",
            "file"       to "file_organizer",
            "整理"         to "file_organizer",
            "code"       to "code_runner",
            "代码"         to "code_runner",
            "跑"          to "code_runner",
            "data"       to "data_analyzer",
            "数据分析"       to "data_analyzer",
            // 新增 9 个
            "research"   to "research_digest",
            "调研"         to "research_digest",
            "摘要"         to "research_digest",
            "批量"         to "batch_web_fetch",
            "batch"      to "batch_web_fetch",
            "抓取"         to "batch_web_fetch",
            "duplicate"  to "duplicate_finder",
            "重复"         to "duplicate_finder",
            "repo"       to "git_repo_analyzer",
            "仓库"         to "git_repo_analyzer",
            "结构"         to "git_repo_analyzer",
            "snippet"    to "snippet_library",
            "片段"         to "snippet_library",
            "transform"  to "bulk_text_transform",
            "变换"         to "bulk_text_transform",
            "base64"     to "bulk_text_transform",
            "text"       to "bulk_text_transform",
            "log"        to "log_analyzer",
            "日志"         to "log_analyzer",
            "changelog"  to "changelog_generator",
            "todo"       to "changelog_generator",
            "债务"         to "changelog_generator",
            "scaffold"   to "project_scaffolder",
            "骨架"         to "project_scaffolder",
            "项目"         to "project_scaffolder",
            "gitignore"  to "project_scaffolder"
        )

        val templateIds = keywordToTemplateId
            .filter { (kw, _) -> queryLower.contains(kw) }
            .map { it.second }
            .toSet()

        if (templateIds.isEmpty()) {
            // 未命中关键词：列出所有可用模板，提示用户细化查询。
            return listOf(allBuiltinSummary())
        }

        return templateIds.map { id ->
            val entry = SkillInstallTool.BUILTIN_TEMPLATES_BY_ID[id]
            if (entry != null) {
                "• $id（内置模板）\n  ${entry.name}：${entry.description}\n  安装: skill_install({\"source\":\"template\",\"template\":\"$id\"})"
            } else {
                "• $id（未识别的模板 id）"
            }
        }
    }

    /** 生成全部内置模板的简明清单，用于无关键词命中时引导。 */
    private fun allBuiltinSummary(): String {
        val ids = SkillInstallTool.BUILTIN_TEMPLATES_BY_ID.keys.joinToString(", ")
        return buildString {
            appendLine("未命中关键词。当前可用的内置模板（${SkillInstallTool.BUILTIN_TEMPLATES_BY_ID.size} 个）：")
            SkillInstallTool.BUILTIN_TEMPLATES_BY_ID.forEach { (id, entry) ->
                appendLine("  • $id — ${entry.name}：${entry.description}")
            }
            appendLine("用 skill_install({\"source\":\"template\",\"template\":\"<id>\"}) 安装；或用更精确的关键词再次 skill_search。")
        }
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
        return BUILTIN_TEMPLATES_BY_ID[name]
            ?: "Error: Unknown template '$name'. Available: ${BUILTIN_TEMPLATES_BY_ID.keys.joinToString(", ")}"
    }

    /**
     * 内置模板注册表：唯一的真相来源。
     * SkillInstallTool.getTemplate / SkillSearchTool.getBuiltinSkillTemplates /
     * SkillMenuProvider.BUILTIN_TEMPLATES 都应从这里取，避免三处漂移。
     *
     * 每个 Map 条目：id -> TemplateEntry(name, description, manifestJson)
     */
    companion object {
        data class TemplateEntry(
            val name: String,
            val description: String,
            val manifestJson: String
        )

        /**
         * 旧常量改名为原始 manifest 字符串，便于注册表统一索引。
         */
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
  "version": "1.1.0",
  "description": "按扩展名自动分类目录下的文件到 images/docs/videos/archives/other 子目录",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["list_files", "shell_execute"]},
  "tools": [{
    "id": "organize_files",
    "name": "Organize Files",
    "description": "List a directory's contents, then move its regular files into type-based subdirectories (jpg/png -> images, pdf/doc/txt -> docs, mp4/mkv -> videos, zip/tar.gz/7z -> archives, rest -> other). Subdirectories that hold the files are created as needed. Folders themselves are not moved.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"要整理的目录路径\"}},\"required\":[\"path\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "list_files", "args": {"path": "{{path}}", "depth": 1, "max_items": 100, "show_size": true}},
      {"tool": "shell_execute", "args": {"command": "cd '{{path}}' 2>/dev/null && mkdir -p images docs videos archives other && for f in *; do if [ -f \"$f\" ]; then case \"$f\" in *.jpg|*.jpeg|*.png|*.gif|*.bmp|*.webp) mv \"$f\" images/ 2>/dev/null;; *.pdf|*.doc|*.docx|*.txt|*.md|*.xls|*.xlsx|*.ppt|*.pptx) mv \"$f\" docs/ 2>/dev/null;; *.mp4|*.mkv|*.avi|*.mov|*.webm) mv \"$f\" videos/ 2>/dev/null;; *.zip|*.tar|*.tar.gz|*.tgz|*.7z|*.rar|*.gz|*.bz2) mv \"$f\" archives/ 2>/dev/null;; *) mv \"$f\" other/ 2>/dev/null;; esac; fi; done && echo 'Organized. Counts:' && for d in images docs videos archives other; do printf '%s: ' \"$d\"; ls \"$d\" 2>/dev/null | wc -l; done", "max_lines": 30}}
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
  "version": "1.1.0",
  "description": "写入代码并立即运行，返回 stdout/stderr。支持 python/shell/node 三种语言",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["write_file", "shell_execute"]},
  "tools": [{
    "id": "run_code",
    "name": "Run Code",
    "description": "Write the code body to a file whose extension matches the language (python -> .py, shell -> .sh, node -> .js), then execute it with the matching interpreter and return stdout/stderr.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"language\":{\"type\":\"string\",\"enum\":[\"python\",\"shell\",\"node\"],\"description\":\"代码语言（决定文件名与运行解释器）\"},\"code\":{\"type\":\"string\",\"description\":\"要运行的代码内容\"}},\"required\":[\"language\",\"code\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "write_file", "args": {"path": "./code_output/code_run.{{language}}", "content": "{{code}}"}},
      {"tool": "shell_execute", "args": {"command": "cd ./code_output 2>/dev/null || exit 1; if [ '{{language}}' = 'python' ]; then python code_run.python 2>&1; elif [ '{{language}}' = 'shell' ]; then sh code_run.shell 2>&1; elif [ '{{language}}' = 'node' ]; then node code_run.node 2>&1; else echo 'Unsupported language: {{language}}'; fi", "max_lines": 40, "timeout": 60}}
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
  "version": "1.1.0",
  "description": "读取数据文件前若干行做预览，再用 shell 生成行数/单词数/字符数/首尾行概览",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["read_file", "shell_execute"]},
  "tools": [{
    "id": "analyze_data",
    "name": "Analyze Data",
    "description": "Preview the head of a data file (CSV/JSON/text), then run wc to summarize it (lines/words/bytes) and print its first and last line as a quick structural overview.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"file\":{\"type\":\"string\",\"description\":\"要分析的数据文件路径\"}},\"required\":[\"file\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "read_file", "args": {"path": "{{file}}", "limit": 10}},
      {"tool": "shell_execute", "args": {"command": "wc -lwc '{{file}}' 2>/dev/null && echo '--- first line ---' && head -n 1 '{{file}}' 2>/dev/null && echo '--- last line ---' && tail -n 1 '{{file}}' 2>/dev/null", "max_lines": 20}}
    ]}
  }],
  "configuration": {"autoSetup": []}
}
""".trimIndent()

        // ══════ 高质量技能模板（v1.1 扩展包，通用代码/数据/办公方向） ══════
        // 编排原则：每步 args 模板的键与底层工具的 parametersSchema 严格对齐，
        // 避免引用不存在的形参，{{var}} 由 SkillToolAdapter.resolveTemplate 解析。

        /**
         * 调研摘录：搜索 → 抓取目标页正文 → 写入本地 markdown 摘要。
         * 三步链路，每步真实可执行；max_chars 限制避免输出爆炸。
         *
         * 关键：web_fetch 需要明确的 url。composite 步骤无法自动从 web_search
         * 输出里解析提取 URL，因此要求调用方先看 web_search 结果，再把目标 URL
         * 作为 {{url}} 传入（由 LLM 在调用 skill 前一次性补齐）。
         */
        val RESEARCH_DIGEST_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "research_digest",
  "name": "调研摘录",
  "version": "1.0.0",
  "description": "对任意主题做网调：搜索→抓取目标页正文→归档为本地 Markdown 摘要",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["web_search", "web_fetch", "write_file"]},
  "tools": [{
    "id": "digest_topic",
    "name": "Digest Topic",
    "description": "Research a topic: step 1 runs a web_search so the caller sees candidate URLs; the caller then supplies a target URL, step 2 fetches its readable text (max 6000 chars), step 3 writes a Markdown digest to ./research_output/.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"调研主题（搜索关键词；用于第一步的 web_search）\"},\"url\":{\"type\":\"string\",\"description\":\"要抓取正文并归档的目标 URL（见第一步搜索结果，由调用方选定的那条）\"},\"out_file\":{\"type\":\"string\",\"description\":\"输出 Markdown 文件名（如 topic-xxx.md）\"}},\"required\":[\"query\",\"url\",\"out_file\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "web_search", "args": {"query": "{{query}}", "max_results": 5}},
      {"tool": "web_fetch", "args": {"url": "{{url}}", "mode": "text", "max_chars": 6000}},
      {"tool": "write_file", "args": {"path": "./research_output/{{out_file}}", "content": "# 调研摘要：{{query}}\n\n来源：{{url}}\n\n---\n\n{{prev_output}}\n"}}
    ]}
  }],
  "configuration": {
    "autoSetup": [
      {"action": "create_directory", "path": "./research_output"}
    ]
  }
}
""".trimIndent()

        /**
         * 批量网页抓取（单源归档版）。
         *
         * 设计说明：composite 步骤的 {{prev_output}} 只承载上一步输出，
         * 无法在单次执行中把多个 web_fetch 的结果同时拼进最终 write_file。
         * 为保证每步真实可执行、最终产物有意义，本技能收敛为"抓取单个 URL
         * 正文并归档为 Markdown"；如需多源归档，可对不同 URL 多次调用本技能，
         * 用各自独立的 out_file 累积到 ./web_reports/。
         */
        val BATCH_WEB_FETCH_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "batch_web_fetch",
  "name": "批量网页抓取",
  "version": "1.0.0",
  "description": "抓取指定 URL 的可读正文并归档为 Markdown 报告（多源时多次调用、各存独立文件）",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["web_fetch", "write_file"]},
  "tools": [{
    "id": "fetch_and_archive",
    "name": "Fetch and Archive",
    "description": "Fetch one URL's readable text (max 4000 chars) and write a Markdown archive to ./web_reports/. For multiple sources, call this skill repeatedly with different url + out_file pairs.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"要抓取的 URL\"},\"out_file\":{\"type\":\"string\",\"description\":\"输出 Markdown 文件名（如 archive-xxx.md）；显式给出避免多次调用互相覆盖\"}},\"required\":[\"url\",\"out_file\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "web_fetch", "args": {"url": "{{url}}", "mode": "text", "max_chars": 4000}},
      {"tool": "write_file", "args": {"path": "./web_reports/{{out_file}}", "content": "# 抓取归档：{{url}}\n\n---\n\n{{prev_output}}\n"}}
    ]}
  }],
  "configuration": {
    "autoSetup": [
      {"action": "create_directory", "path": "./web_reports"}
    ]
  }
}
""".trimIndent()

        /**
         * 重复文件查找：glob_files 列出候选 → shell_execute 计算校验和并去重。
         * shell 用纯 find + md5sum/cksum + sort + uniq -d 管道，避免 awk 正则
         * 在 Android toybox 下的兼容性问题（POSIX awk 不识别 \S 等）。
         * 输出：重复的校验和及对应文件列表。
         */
        val DUPLICATE_FINDER_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "duplicate_finder",
  "name": "重复文件查找",
  "version": "1.0.0",
  "description": "按内容指纹查找目录下的重复文件（不依赖文件名）",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["glob_files", "shell_execute"]},
  "tools": [{
    "id": "find_duplicates",
    "name": "Find Duplicates",
    "description": "Find duplicate files by content hash in a directory. Lists groups of files sharing identical content (size+checksum). Step 1 pre-lists files via glob_files for a quick size overview; step 2 independently re-walks the directory with find+md5sum/cksum and reports duplicates.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"待扫描的目录\"}},\"required\":[\"path\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "glob_files", "args": {"path": "{{path}}", "pattern": "*", "max_results": 200, "sort_by": "size"}},
      {"tool": "shell_execute", "args": {"command": "cd '{{path}}' 2>/dev/null && find . -type f -size +0c 2>/dev/null | head -200 | while read f; do if command -v md5sum >/dev/null 2>&1; then h=$(md5sum \"$f\" | cut -d' ' -f1); else h=$(cksum \"$f\" | cut -d' ' -f1-2 | tr ' ' '_'); fi; echo \"$h $f\"; done | sort | uniq -w32 -d -c | sort -rn", "max_lines": 60}}
    ]}
  }],
  "configuration": {"autoSetup": []}
}
""".trimIndent()

        /**
         * 仓库结构分析：list_files 浏览目录 → search_files 定位 TODO/FIXME/关键符号。
         * 输出结构概览 + 代码异味清单。
         */
        val GIT_REPO_ANALYZER_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "git_repo_analyzer",
  "name": "仓库结构分析",
  "version": "1.0.0",
  "description": "扫描代码仓库结构：目录树概览 + TODO/FIXME 技术债定位",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["list_files", "search_files"]},
  "tools": [{
    "id": "analyze_repo",
    "name": "Analyze Repo",
    "description": "Walk a repository to produce a structural overview (two-level directory tree) and locate TODO/FIXME/XXX technical-debt markers across its code files. For custom symbol searches call search_files directly with your own pattern.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"仓库根目录\"},\"depth\":{\"type\":\"integer\",\"description\":\"目录递归深度（默认 2）\"}},\"required\":[\"path\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "list_files", "args": {"path": "{{path}}", "depth": "{{depth}}", "max_items": 80, "show_size": true}},
      {"tool": "search_files", "args": {"path": "{{path}}", "pattern": "TODO|FIXME|XXX", "file_type": "code", "context_lines": 1, "max_results": 20}}
    ]}
  }],
  "configuration": {"autoSetup": []}
}
""".trimIndent()

        /**
         * 代码片段收藏：读取源文件 → 追加写入个人 snippets 库。
         * write_file 用 mode=append 避免覆盖已收藏内容。
         */
        val SNIPPET_LIBRARY_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "snippet_library",
  "name": "代码片段收藏",
  "version": "1.0.0",
  "description": "从源文件读取代码并追加归档到本地 snippets 库（Markdown 分隔块）",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["read_file", "write_file"]},
  "tools": [{
    "id": "save_snippet",
    "name": "Save Snippet",
    "description": "Read a source file and append it as a fenced Markdown snippet to ./snippets/library.md with a header and provenance.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"要收藏的源文件路径\"},\"title\":{\"type\":\"string\",\"description\":\"片段标题（默认用文件名）\"}},\"required\":[\"path\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "read_file", "args": {"path": "{{path}}", "limit": 120}},
      {"tool": "write_file", "args": {"path": "./snippets/library.md", "mode": "append", "content": "\n## {{title}}\n\n> 来源：{{path}}\n\n```kotlin\n{{prev_output}}\n```\n"}}
    ]}
  }],
  "configuration": {
    "autoSetup": [
      {"action": "create_directory", "path": "./snippets"}
    ]
  }
}
""".trimIndent()

        /**
         * 批量文本处理：读入文件 → text_transform 转换 → 写回新文件。
         * 支持大小写/编解码/格式化等所有 text_transform operation。
         * 输出路径用 {{out_name}} 显式传入，避免依赖 shell basename 展开
         * （write_file 的 path 字段不经 shell，$(...) 不会展开）。
         */
        val BULK_TEXT_TRANSFORM_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "bulk_text_transform",
  "name": "批量文本处理",
  "version": "1.0.0",
  "description": "读取文件 → 指定变换（大小写/Base64/URL编解码/JSON格式化/哈希等）→ 写出结果",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["read_file", "text_transform", "write_file"]},
  "configuration": {
    "userConfig": [
      {"key": "out_dir", "type": "string", "default": "./text_output", "description": "变换结果输出目录"}
    ],
    "autoSetup": [
      {"action": "create_directory", "path": "./text_output"}
    ]
  },
  "tools": [{
    "id": "transform_file",
    "name": "Transform File",
    "description": "Apply a text_transform operation to a file and write the result to ./text_output/<out_name>. Supported operations: base64_encode, base64_decode, url_encode, url_decode, uppercase, lowercase, md5, sha256, reverse, json_format etc. The first step reads up to 300 lines of the file's viewport; for larger files call read_file directly.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"输入文件路径\"},\"operation\":{\"type\":\"string\",\"enum\":[\"base64_encode\",\"base64_decode\",\"url_encode\",\"url_decode\",\"uppercase\",\"lowercase\",\"md5\",\"sha256\",\"reverse\",\"word_count\",\"char_count\",\"json_format\"],\"description\":\"text_transform 的 operation\"},\"out_name\":{\"type\":\"string\",\"description\":\"输出文件名（如 result.txt）；显式给出避免覆盖\"}},\"required\":[\"path\",\"operation\",\"out_name\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "read_file", "args": {"path": "{{path}}", "limit": 300}},
      {"tool": "text_transform", "args": {"text": "{{prev_output}}", "operation": "{{operation}}"}},
      {"tool": "write_file", "args": {"path": "./text_output/{{out_name}}", "content": "{{prev_output}}"}}
    ]}
  }]
}
""".trimIndent()

        /**
         * 日志分析：读末尾 N 行 → shell 统计错误/警告频次与样例。
         * 适配 Android logcat 留盘文件或任意文本日志。
         */
        val LOG_ANALYZER_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "log_analyzer",
  "name": "日志分析",
  "version": "1.0.0",
  "description": "读取日志文件末尾 250 行，统计 ERROR/WARN 频次及典型样本",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["read_file", "shell_execute"]},
  "tools": [{
    "id": "analyze_log",
    "name": "Analyze Log",
    "description": "Read the last 250 lines of a log file, then run an awk pipeline to count FATAL/ERROR and WARN occurrences and print one sample line per category. Works on Android logcat dumps or any text log; for larger windows call read_file with tail directly.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"日志文件路径\"}},\"required\":[\"path\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "read_file", "args": {"path": "{{path}}", "tail": 250}},
      {"tool": "shell_execute", "args": {"command": "tail -n 250 '{{path}}' 2>/dev/null | awk 'BEGIN{e=0;w=0;ews=\"\";wws=\"\"} /ERROR|Error|FATAL/{e++; if(ews==\"\")ews=$0} /WARN|Warning/{w++; if(wws==\"\")wws=$0} END{print \"ERROR/FATAL: \"e\" (sample: \"ews\")\"; print \"WARN: \"w\" (sample: \"wws\")\"; print \"总计扫描 \"NR\" 行\"}'", "max_lines": 20}}
    ]}
  }],
  "configuration": {"autoSetup": []}
}
""".trimIndent()

        /**
         * 变更日志生成：扫描 TODO/FIXME/XXX 标记 → 写成待办清单报告。
         * 独立于源代码现状，作为“债务快照”可独立运行。
         */
        val CHANGELOG_GENERATOR_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "changelog_generator",
  "name": "变更日志生成",
  "version": "1.0.0",
  "description": "扫描代码中的 TODO/FIXME/XXX 标记，归类生成 Markdown 待办快照",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["search_files", "write_file"]},
  "tools": [{
    "id": "gen_changelog",
    "name": "Generate Changelog",
    "description": "Search a directory for TODO/FIXME/XXX debt markers and write a Markdown snapshot to ./changelogs/tech-debt.md. The optional project name only appears in the snapshot heading.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"要扫描的目录\"},\"project\":{\"type\":\"string\",\"description\":\"项目名（仅用于快照标题，可省略）\"}},\"required\":[\"path\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "search_files", "args": {"path": "{{path}}", "pattern": "TODO|FIXME|XXX", "file_type": "code", "context_lines": 1, "max_results": 30}},
      {"tool": "write_file", "args": {"path": "./changelogs/tech-debt.md", "content": "# 待办债务快照\n\n项目：{{project}}\n生成时间：见文件时间戳\n扫描目录：{{path}}\n\n## 发现的 TODO/FIXME/XXX\n\n{{prev_output}}\n\n---\n> 由 apex-agent changelog_generator 技能生成\n"}}
    ]}
  }],
  "configuration": {
    "autoSetup": [
      {"action": "create_directory", "path": "./changelogs"}
    ]
  }
}
""".trimIndent()

        /**
         * 项目骨架生成：一次性写多个 README/.gitignore/CI 配置文件骨架。
         * 纯 write_file 多步串行，每步产出独立文件，无输出依赖。
         */
        val PROJECT_SCAFFOLDER_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "project_scaffolder",
  "name": "项目骨架生成",
  "version": "1.0.0",
  "description": "在指定目录生成项目骨架：README、.gitignore、CI 工作流等基础文件",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["write_file"]},
  "tools": [{
    "id": "scaffold",
    "name": "Scaffold Project",
    "description": "Create base project files in a target dir: README.md, .gitignore, and a GitHub Actions CI skeleton. Idempotent per-file (overwrites if present).",
    "parameters": "{\"type\":\"object\",\"properties\":{\"root\":{\"type\":\"string\",\"description\":\"项目根目录（相对或绝对）\"},\"name\":{\"type\":\"string\",\"description\":\"项目名（写入 README 标题）\"},\"lang\":{\"type\":\"string\",\"enum\":[\"kotlin\",\"python\",\"node\",\"go\"],\"description\":\"主要语言（决定 .gitignore 模板）\"}},\"required\":[\"root\",\"name\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "write_file", "args": {"path": "{{root}}/README.md", "content": "# {{name}}\n\n> 由 apex-agent project_scaffolder 技能生成骨架。\n\n## 简介\n\nTODO: 描述项目用途。\n\n## 快速开始\n\n```\nTODO: 安装与运行步骤\n```\n\n## 许可证\n\nMIT\n"}},
      {"tool": "write_file", "args": {"path": "{{root}}/.gitignore", "content": "# 通用\n.DS_Store\n*.log\nbuild/\n.gradle/\n.idea/\n.vscode/\n*.iml\n\n# 语言：{{lang}}\n# 请按项目主要语言补充对应的忽略规则\n"}},
      {"tool": "write_file", "args": {"path": "{{root}}/.github/workflows/ci.yml", "content": "name: CI\non:\n  push:\n    branches: [main]\n  pull_request:\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n      - name: Setup\n        run: echo \"TODO: 为 {{name}} 配置构建步骤（{{lang}}）\"\n"}}
    ]}
  }],
  "configuration": {"autoSetup": []}
}
""".trimIndent()

        /**
         * 内置模板总注册表（单一真相来源）。
         * 新增模板时只需在此 Map 追加一条即可，三处调用方均自动更新。
         */
        val BUILTIN_TEMPLATES_BY_ID: Map<String, TemplateEntry> = linkedMapOf(
            "web_scraper"         to TemplateEntry("网页数据爬取", "从网页提取结构化数据", WEB_SCRAPER_TEMPLATE),
            "file_organizer"      to TemplateEntry("文件自动整理", "按扩展名分类移动文件到 images/docs/videos 等子目录", FILE_ORGANIZER_TEMPLATE),
            "code_runner"         to TemplateEntry("代码运行器", "写入代码并运行，返回 stdout/stderr（python/shell/node）", CODE_RUNNER_TEMPLATE),
            "data_analyzer"       to TemplateEntry("数据分析", "预览数据文件并生成行数/字符/首尾行概览", DATA_ANALYZER_TEMPLATE),
            "research_digest"     to TemplateEntry("调研摘录", "对主题做网调并归档为本地 Markdown 摘要", RESEARCH_DIGEST_TEMPLATE),
            "batch_web_fetch"     to TemplateEntry("批量网页抓取", "抓取 URL 正文归档为 Markdown（多源时多次调用、各存独立文件）", BATCH_WEB_FETCH_TEMPLATE),
            "duplicate_finder"    to TemplateEntry("重复文件查找", "按内容指纹查找目录下重复文件", DUPLICATE_FINDER_TEMPLATE),
            "git_repo_analyzer"   to TemplateEntry("仓库结构分析", "扫描仓库结构与 TODO/FIXME 热点", GIT_REPO_ANALYZER_TEMPLATE),
            "snippet_library"     to TemplateEntry("代码片段收藏", "将源文件归档到本地 snippets 库", SNIPPET_LIBRARY_TEMPLATE),
            "bulk_text_transform" to TemplateEntry("批量文本处理", "读文件→变换→写出（Base64/大小写/JSON 等）", BULK_TEXT_TRANSFORM_TEMPLATE),
            "log_analyzer"        to TemplateEntry("日志分析", "统计日志末尾 ERROR/WARN 频次与样本", LOG_ANALYZER_TEMPLATE),
            "changelog_generator" to TemplateEntry("变更日志生成", "扫描 TODO/FIXME/XXX 生成 Markdown 债务快照", CHANGELOG_GENERATOR_TEMPLATE),
            "project_scaffolder"  to TemplateEntry("项目骨架生成", "初始化 README/.gitignore/CI 等基础文件", PROJECT_SCAFFOLDER_TEMPLATE)
        )
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
