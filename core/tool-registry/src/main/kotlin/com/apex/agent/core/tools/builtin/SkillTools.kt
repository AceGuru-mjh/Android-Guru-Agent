package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // v2：网络搜索统一在 IO 线程执行（旧实现在调用方线程同步 execute）
        return withContext(Dispatchers.IO) { executeInternal(arguments) }
    }

    private fun executeInternal(arguments: String): String {
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

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""

                    if (response.isSuccessful && body.length <= MAX_SEARCH_RESPONSE_BYTES) {
                        val searchResult = Json.parseToJsonElement(body).jsonObject
                        // v2 修复：旧实现在 items 缺失时直接 return emptyResults 提前退出，
                        // 跳过了后面「内置模板搜索」分支（GitHub 空结果时永远搜不到内置模板）
                        val items = searchResult["items"]?.jsonArray ?: emptyList()

                        items.forEach { item ->
                            val obj = item.jsonObject
                            val name = obj["name"]?.jsonPrimitive?.content ?: ""
                            val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                            val htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: ""
                            results.add("• $name\n  $desc\n  URL: $htmlUrl")
                        }
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

    private fun getBuiltinSkillTemplates(query: String): List<String> {
        val templates = mapOf(
            "web" to "• web_scraper (内置模板)\n  网页数据提取\n  安装: skill_install({\"source\":\"template\",\"template\":\"web_scraper\"})",
            "file" to "• file_organizer (内置模板)\n  文件自动分类整理\n  安装: skill_install({\"source\":\"template\",\"template\":\"file_organizer\"})",
            "code" to "• code_runner (内置模板)\n  代码执行与调试\n  安装: skill_install({\"source\":\"template\",\"template\":\"code_runner\"})",
            "data" to "• data_analyzer (内置模板)\n  数据分析与可视化\n  安装: skill_install({\"source\":\"template\",\"template\":\"data_analyzer\"})",
            "principle" to "• coding_principles (内置模板)\n  编码协作九原则（Karpathy）\n  安装: skill_install({\"source\":\"template\",\"template\":\"coding_principles\"})"
        )
        return templates.filter { (key, _) -> query.contains(key, ignoreCase = true) }.values.toList()
    }

    companion object {
        /** GitHub 搜索响应体上限（防御超大响应 OOM）。 */
        private const val MAX_SEARCH_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}

/**
 * Skill 安装工具
 *
 * 三种安装来源：
 * - "url"       — 从 URL 下载 skill JSON
 * - "template"  — 使用内置模板（web_scraper / file_organizer / code_runner / data_analyzer / coding_principles）
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
        - Template: Use a built-in template (web_scraper, file_organizer, code_runner, data_analyzer, coding_principles)
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
        // v2：网络下载统一在 IO 线程执行，且限制响应体大小（旧实现无上限，
        // 恶意/超大响应会 OOM；且走非 flowOn 路径时会在调用方线程阻塞至超时）
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "ApexAgent/1.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use "Error: HTTP ${response.code} downloading skill"
                    }
                    response.body?.byteStream()?.let { stream ->
                        val bytes = stream.readBytes(MAX_DOWNLOAD_BYTES)
                        if (bytes == null) {
                            "Error: skill file too large (>${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB)"
                        } else {
                            String(bytes, Charsets.UTF_8)
                        }
                    } ?: "Error: Empty response"
                }
            } catch (e: Exception) {
                "Error: Download failed - ${e.message}"
            }
        }
    }

    /** 读取至多 [max] 字节；超限时返回 null（调用方提示过大）。 */
    private fun java.io.InputStream.readBytes(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val n = read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        if (total >= max && read() >= 0) return null  // 还有剩余字节 → 超限
        return out.toByteArray()
    }

    private fun getTemplate(name: String): String {
        return when (name) {
            "web_scraper" -> WEB_SCRAPER_TEMPLATE
            "file_organizer" -> FILE_ORGANIZER_TEMPLATE
            "code_runner" -> CODE_RUNNER_TEMPLATE
            "data_analyzer" -> DATA_ANALYZER_TEMPLATE
            "coding_principles" -> CODING_PRINCIPLES_TEMPLATE
            "deep_research" -> DEEP_RESEARCH_TEMPLATE
            "code_review" -> CODE_REVIEW_TEMPLATE
            "crash_triage" -> CRASH_TRIAGE_TEMPLATE
            "git_workflow" -> GIT_WORKFLOW_TEMPLATE
            "standup_report" -> STANDUP_REPORT_TEMPLATE
            "im_notify" -> IM_NOTIFY_TEMPLATE
            else -> "Error: Unknown template '$name'. Available: web_scraper, file_organizer, code_runner, data_analyzer, coding_principles, deep_research, code_review, crash_triage, git_workflow, standup_report, im_notify"
        }
    }

    companion object {
        /** 单次 skill 下载响应体上限（防御超大响应 OOM）。 */
        private const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024

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

        // Prompt 型内置技能：不新增工具，安装后把九条编码协作原则注入 System Prompt
        val CODING_PRINCIPLES_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "coding_principles",
  "name": "编码原则 (Karpathy)",
  "version": "1.0.0",
  "description": "Andrej Karpathy 式 AI 编程协作九原则：写之前先读、想清楚再动手、保持简单、外科手术式修改、先验证再交付等",
  "author": "apex-builtin",
  "promptInjection": "你遵循以下编码协作原则（源自 Andrej Karpathy），适用于一切读代码、写代码、改代码的任务：\n1. 写之前先读代码库：动笔前，务必阅读要修改的文件和项目中类似功能的实现方式，确保新代码与项目风格一致。\n2. 想清楚再动手：明确假设，说出权衡（例如「我假设你希望使用基于 JWT 的认证」）；存在歧义时先向用户确认。\n3. 保持简单：只编写解决问题所需的最少代码，不添加任何未被要求的功能。\n4. 外科手术式修改：只修改被要求的部分，避免附带修改或「顺便」重构。\n5. 先验证再交付：交付代码前先测试或验证，确保改动没有引入新问题。\n6. 目标驱动执行：以明确的验收标准为导向，自行寻找达标的路径，而非等待逐步指令。\n7. 不猜先调查：遇到不确定的事情，先调查清楚，而不是猜测。\n8. 谨慎加依赖：引入新依赖前要三思，尽量使用项目已有的库。\n9. 把沟通写清楚：与协作者（包括人类和其他 Agent）沟通时，要清晰、明确、结论先行。",
  "tools": [],
  "configuration": {"autoSetup": []}
}
""".trimIndent()

        // ═══ 第二批内置技能（调研 / 评审 / 排障 / 协作 / 通知）═══

        /** Composite 型：检索 → 抓取 → 落盘报告（promptInjection 约束报告结构）。 */
        val DEEP_RESEARCH_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "deep_research",
  "name": "深度调研",
  "version": "1.0.0",
  "description": "多源检索 + 抓取 + 交叉验证，产出带引用来源的调研报告",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["web_search", "web_fetch", "write_file"]},
  "tools": [{
    "id": "deep_research",
    "name": "Deep Research",
    "description": "Research a topic: search the web, fetch the most relevant sources, then write a cited report to a file.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\",\"description\":\"调研主题或问题\"},\"output\":{\"type\":\"string\",\"description\":\"报告输出路径，默认 ./research_output/report.md\"}},\"required\":[\"topic\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "web_search", "args": {"query": "{{topic}}"}},
      {"tool": "web_fetch", "args": {"url": "{{topic}}"}},
      {"tool": "write_file", "args": {"path": "{{output}}", "content": "{{topic}}"}}
    ]}
  }],
  "promptInjection": "执行调研类任务时按以下流程与规范产出：\n1. 先把主题拆成 3-5 个子问题，分别检索，避免一次性泛泛搜索。\n2. 每个结论必须能追溯到具体来源（标题 + URL），无法溯源的结论要显式标注为推测。\n3. 多源冲突时并列呈现不同说法与各自依据，不要擅自选边。\n4. 报告结构：结论先行（3-5 条要点）→ 关键证据 → 风险/不确定性 → 参考来源列表。\n5. 明确区分「事实」「推断」「待验证」三类信息，不要把推断写成事实。",
  "configuration": {
    "autoSetup": [
      {"action": "create_directory", "path": "./research_output"}
    ]
  }
}
""".trimIndent()

        /** Composite 型：列目录 → 读文件，配合 promptInjection 出分级问题清单。 */
        val CODE_REVIEW_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "code_review",
  "name": "代码评审",
  "version": "1.0.0",
  "description": "按严重度分级的代码审查：正确性 / 安全 / 性能 / 可维护性",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["list_files", "read_file"]},
  "tools": [{
    "id": "review_path",
    "name": "Review Path",
    "description": "Collect the files under a path for review: list them, then read the entry files.",
    "parameters": "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"要评审的文件或目录路径\"}},\"required\":[\"path\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "list_files", "args": {"path": "{{path}}"}},
      {"tool": "read_file", "args": {"path": "{{path}}"}}
    ]}
  }],
  "promptInjection": "执行代码评审时按以下规范输出：\n1. 按严重度分级：🔴 阻断（会出错/数据丢失/安全漏洞）、🟡 建议（性能/健壮性/可读性）、🟢 可选（风格/命名）。\n2. 每条问题给出：位置（文件:行）→ 问题 → 影响 → 具体改法（给出可直接应用的修改）。\n3. 不吹毛求疵：与本次改动无关的历史问题单独列为「顺带发现」，不混进主评审结论。\n4. 先确认理解代码意图再评价实现方式；意图不明就问，不要假定。\n5. 结尾给出一句总体结论：可以合并 / 需要修改后重审 / 需要重新设计。",
  "configuration": {"autoSetup": []}
}
""".trimIndent()

        /** Prompt 型：Android 崩溃栈 / ANR 日志的根因定位流程。 */
        val CRASH_TRIAGE_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "crash_triage",
  "name": "崩溃/ANR 定位",
  "version": "1.0.0",
  "description": "Android 崩溃栈与 ANR 日志的根因定位流程（先看栈顶与主线程，再补上下文）",
  "author": "apex-builtin",
  "promptInjection": "分析 Android 崩溃/ANR 时按以下流程：\n1. 先区分类型：Java/Kotlin 异常栈、native tombstone、ANR（主线程阻塞）。三者定位路径不同。\n2. 崩溃：读最顶层「本项目包名」的帧（不是系统帧），确定真正触发点；注意 `Caused by` 链才是根因。\n3. ANR：看主线程栈顶与 `held by` / 锁等待关系，判断是锁竞争、IO 还是死循环；再看 CPU 占用与 Binder 调用。\n4. 补上下文：发生版本、机型/系统版本、是否首次启动/后台/低内存、复现频率——没有这些信息先向用户索要。\n5. 输出：根因一句话 → 证据（日志行）→ 修复方案（含代码位置）→ 验证方式 → 防复发建议（如加保护/监控）。",
  "tools": [],
  "configuration": {"autoSetup": []}
}
""".trimIndent()

        /** Prompt 型：Conventional Commits + PR 描述模板。 */
        val GIT_WORKFLOW_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "git_workflow",
  "name": "Git 提交与 PR 规范",
  "version": "1.0.0",
  "description": "Conventional Commits 提交规范 + 可评审的 PR 描述模板",
  "author": "apex-builtin",
  "promptInjection": "涉及 Git 提交、分支与 PR 时遵循以下规范：\n1. 提交信息用 Conventional Commits：`feat/fix/refactor/docs/test/chore/perf/build`（可选 scope）+ 简短祈使句标题；标题不超过 72 字符。\n2. 一个提交只做一件事；格式化/重构与功能改动分开提交，便于 review 与 revert。\n3. 正文说明「为什么」而不是「改了什么」（后者看 diff 就知道）；关联 issue 用 `Refs #123` / `Fixes #123`。\n4. 分支命名：`feat/`、`fix/`、`chore/` 前缀 + 短横线小写短语。\n5. PR 描述模板：## 背景与动机 / ## 改动内容 / ## 验证方式（命令 + 结果）/ ## 风险与回滚方案 / ## 待确认问题。\n6. 提交前自检：diff 里不留调试代码、Secret、无关格式化改动。",
  "tools": [],
  "configuration": {"autoSetup": []}
}
""".trimIndent()

        /** Prompt 型：日报/周报结构与写作规范。 */
        val STANDUP_REPORT_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "standup_report",
  "name": "日报/周报生成",
  "version": "1.0.0",
  "description": "把零散进展整理成结论先行的日报/周报（含阻塞与下一步）",
  "author": "apex-builtin",
  "promptInjection": "生成日报/周报时按以下规范：\n1. 结论先行：开头一段给出总体状态（进展/风险/是否按计划），再展开细节。\n2. 按「已完成 / 进行中 / 阻塞 / 下一步」四段组织；每条写清产出物而不只是动作（「完成接口联调」优于「做了联调」）。\n3. 阻塞项必须写清：阻塞什么、卡在谁/什么依赖、需要的帮助、预计解除时间。\n4. 量化优先：用数字与链接（提交、PR、Issue、报告路径）代替形容词。\n5. 不确定的信息标注来源与置信度，不把推测写成事实；信息不足时明确列出「待补充」。",
  "tools": [],
  "configuration": {"autoSetup": []}
}
""".trimIndent()

        /** Composite 型：把执行结果推送到已配置的消息通道（微信/飞书/QQ/Telegram）。 */
        val IM_NOTIFY_TEMPLATE = """
{
  "schema": "apex-skill-v1",
  "id": "im_notify",
  "name": "结果推送到 IM",
  "version": "1.0.0",
  "description": "把执行结果推送到微信/飞书/QQ/Telegram 等已配置的消息通道",
  "author": "apex-builtin",
  "requirements": {"toolsRequired": ["connector_list", "connector_send_message"]},
  "tools": [{
    "id": "im_notify",
    "name": "Notify On IM",
    "description": "Push a short result summary to a configured messaging connector (wechat / feishu / qq / telegram).",
    "parameters": "{\"type\":\"object\",\"properties\":{\"connector_id\":{\"type\":\"string\",\"description\":\"通道 id（connector_list 查看）：wechat / feishu / qq / telegram\"},\"message\":{\"type\":\"string\",\"description\":\"要推送的摘要文本\"}},\"required\":[\"connector_id\",\"message\"]}",
    "implementation": {"type": "composite", "steps": [
      {"tool": "connector_send_message", "args": {"connector_id": "{{connector_id}}", "message": "{{message}}"}}
    ]}
  }],
  "promptInjection": "需要把结果通知到人时：先用 connector_list 确认可用通道与凭据状态，未配置就引导用户去「市场 → 连接器」配置（微信 ClawBot/企业微信、飞书、QQ、Telegram）。推送内容要短：结论 + 关键数字 + 下一步，附上详情文件路径而不是全文粘贴。发送前确认通道与目标正确——消息一旦发出无法撤回。",
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

    /**
     * v2 修复：补齐换行/回车/制表符转义。旧实现只转义 `\\` 和 `"`，
     * 多行 description / prompt_injection 拼出的 JSON 非法，
     * `skill_create` 对多行输入必然安装失败。
     */
    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
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
