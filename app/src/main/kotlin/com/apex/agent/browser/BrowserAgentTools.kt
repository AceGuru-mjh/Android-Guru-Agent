package com.apex.agent.browser

import android.content.Context
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.builtin.browser.DomParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 浏览器自动化工具集（对标 Operit 的 BrowserTools）。
 *
 * 2026 裁决最终形态（综合 Qwen 评审）：
 * - 全部工具以语义哈希 [ref]（如 "r_3k9f"）定位元素，替代旧顺序 [bid]。
 * - 每个工具执行前调用 [BrowserEngine.assertAgentControl] 守卫：人工接管期间返回 SYSTEM_LOCKED。
 * - 点击/输入/选择返回动作后验证摘要（URL 变化 / 新增元素数），供 Agent 做下一步决策。
 * - 新增 [select]/[toggle]/[show]/[dialog]/[file_upload] 补齐动作空间与显式握手。
 */
class BrowserAgentTools @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val engine: BrowserEngine,
    private val tracer: BrowserTracer,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun argStr(args: String, key: String): String? =
        runCatching { json.decodeFromString<JsonObject>(args)[key]?.jsonPrimitive?.content }.getOrNull()

    private fun argInt(args: String, key: String, default: Int = 0): Int =
        argStr(args, key)?.toIntOrNull() ?: default

    private fun argBool(args: String, key: String, default: Boolean = false): Boolean =
        argStr(args, key)?.toBooleanStrictOrNull() ?: default

    /**
     * 安全加固（清单 11.12）：校验上传文件路径，防路径遍历与越权目录访问。
     * 仅允许指向以下白名单目录及其子目录：Download / DCIM / Pictures / Documents / Movies / Music /
     * 应用私有 cache 与外部 cache。路径含 ".." 或规范化后不在白名单内则返回 null。
     */
    private fun safeUploadPath(raw: String): String? {
        if (raw.isBlank()) return null
        val file = runCatching { java.io.File(raw).canonicalFile }.getOrNull() ?: return null
        val path = file.absolutePath
        if (path.contains("..${java.io.File.separator}") || path.startsWith("..")) return null
        val allowedRoots = listOfNotNull(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)?.absolutePath,
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM)?.absolutePath,
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)?.absolutePath,
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)?.absolutePath,
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)?.absolutePath,
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)?.absolutePath,
            appContext.cacheDir?.absolutePath,
            appContext.externalCacheDir?.absolutePath,
        )
        return if (allowedRoots.any { path == it || path.startsWith("$it${java.io.File.separator}") }) path
        else null
    }

    /** 守卫：人工接管期间拒绝执行，返回友好锁定提示 */
    private fun guard(engine: BrowserEngine): String? = try {
        engine.assertAgentControl()
        null
    } catch (e: BrowserEngine.HandoffLockedException) {
        "SYSTEM_LOCKED: ${e.message}"
    }

    /** 跳转到 URL（支持 wait_for 元素等待 + 超时兜底） */
    val navigate = object : AgentTool {
        override val id = "browser_navigate"
        override val name = "browser_navigate"
        override val description =
            "在内置浏览器中打开 URL 并加载页面（支持 JS 渲染）。可指定 wait_for 等待某个 CSS 选择器出现，避免拿到骨架页。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{
                "url":{"type":"string","description":"目标网址，可带或不带 http(s) 前缀"},
                "new_tab":{"type":"boolean","description":"是否在新标签打开，默认 false"},
                "wait_for":{"type":"string","description":"可选 CSS 选择器，等待该元素出现后再返回（如 .search-results）"},
                "timeout_ms":{"type":"integer","description":"加载/等待超时毫秒，默认 15000"}
            },
            "required":["url"]
        }"""
        override suspend fun execute(arguments: String): String {
            guard(engine)?.let { return it }
            val url = argStr(arguments, "url") ?: return "Error: 缺少 url 参数"
            val newTab = argBool(arguments, "new_tab")
            val waitFor = argStr(arguments, "wait_for")
            val timeout = argInt(arguments, "timeout_ms", 15000)
            val nav = if (newTab) {
                // 新标签：先建空标签，再由 navigate 统一负责加载 + waitFor 等待 + 超时兜底
                engine.newTab(null)
                engine.navigate(url, waitFor, timeout.toLong())
            } else {
                engine.navigate(url, waitFor, timeout.toLong())
            }
            val snap = engine.snapshot()
            val warn = if (nav.timedOut) "\n⚠ 页面加载超时（15s），以下为当前已渲染状态。" else ""
            return "已打开 $url${warn}\n${snap.domSummary}"
        }
    }

    /** 抓取当前页面 DOM 结构化快照（ref 稳定引用） */
    val snapshot = object : AgentTool {
        override val id = "browser_snapshot"
        override val name = "browser_snapshot"
        override val description =
            "获取当前页面的结构化快照：可交互元素列表（带稳定 ref，如 r_3k9f）与页面概要。Agent 应先调用本工具了解页面，再用 ref 操作元素。" +
            "可用 focus 指定剪枝策略：form（仅表单元素）/ content（仅文本链接标题）/ 不填或 all（全部交互元素）。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{
                "focus":{"type":"string","description":"剪枝策略: all(默认,全交互元素) | form(仅表单输入) | content(仅文本/链接/标题)"}
            }
        }"""
        override suspend fun execute(arguments: String): String {
            guard(engine)?.let { return it }
            val focus = argStr(arguments, "focus")?.lowercase()
            val strategy = when (focus) {
                "form" -> DomParser.SnapshotStrategy.FORM_FIELDS
                "content" -> DomParser.SnapshotStrategy.CONTENT_SUMMARY
                else -> DomParser.SnapshotStrategy.INTERACTIVE_ONLY
            }
            val snap = engine.snapshot(strategy = strategy)
            val dialogNote = engine.lastDialog?.let { "\n💬 页面弹出对话: $it" } ?: ""
            return if (snap.interactiveCount == 0) "(当前页面无可交互元素或尚未加载)$dialogNote"
            else snap.domSummary + dialogNote
        }
    }

    /** 读取浏览器网络请求日志（#18 fetch/xhr 监控） */
    val networkLog = object : AgentTool {
        override val id = "browser_network_log"
        override val name = "browser_network_log"
        override val description =
            "读取内置浏览器已发生的网络请求（fetch / XMLHttpRequest）日志，含 method / url / status。可用于判断页面数据是否加载完成，或直接获取 API 响应线索。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{"limit":{"type":"integer","description":"返回最近 N 条，默认 50"}}
        }"""
        override suspend fun execute(arguments: String): String {
            guard(engine)?.let { return it }
            val limit = argInt(arguments, "limit", 50)
            val logs = engine.networkLog(limit)
            if (logs.isEmpty()) return "(暂无网络请求记录，可能页面尚未发起 fetch/xhr)"
            return logs.joinToString("\n") { e ->
                val m = e["method"] ?: "-"; val u = e["url"] ?: "-"; val s = e["status"] ?: 0
                "[$m] $u -> $s"
            }
        }
    }

    /** 读取最近一次文件下载记录（#14） */
    val downloadList = object : AgentTool {
        override val id = "browser_download_list"
        override val name = "browser_download_list"
        override val description =
            "读取内置浏览器最近一次触发的文件下载记录（文件名 / 来源 URL）。网页触发下载后，可用本工具确认下载已发起，再结合系统\"下载\"目录读取文件内容。"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String): String {
            guard(engine)?.let { return it }
            val d = engine.lastDownload ?: return "(暂无下载记录)"
            return "已发起下载：\n文件名: ${d.fileName}\n来源: ${d.url}\n下载ID: ${d.downloadId}\n" +
                "保存位置: 系统 Download 目录。下载完成后可在 DownloadManager 查询状态。"
        }
    }

    /** 按 ref 物理触摸点击元素（DOM 定位 → dispatchTouchEvent，抗 isTrusted 校验） */
    val click = object : AgentTool {
        override val id = "browser_click"
        override val name = "browser_click"
        override val description =
            "点击页面元素（由 browser_snapshot 给出的 ref 定位）。采用物理触摸注入，比坐标更可靠，适用于按钮/链接/选项/勾选框等。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{"ref":{"type":"string","description":"browser_snapshot 返回的元素稳定引用，如 r_3k9f"}},
            "required":["ref"]
        }"""
        override suspend fun execute(arguments: String): String {
            guard(engine)?.let { return it }
            val ref = argStr(arguments, "ref") ?: return "Error: 缺少 ref 参数"
            val st = engine.clickElement(ref)
            return st.toText() + "\n建议：执行 browser_snapshot 获取最新页面状态。"
        }
    }

    /** 向输入框填充文本 */
    val input = object : AgentTool {
        override val id = "browser_input"
        override val name = "browser_input"
        override val description =
            "向某输入框（browser_snapshot 给出的 ref）填入文本，适用于搜索框、表单等。会触发 input/change 事件。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{
                "ref":{"type":"string","description":"输入框元素的稳定引用"},
                "text":{"type":"string","description":"要填入的文本"}
            },
            "required":["ref","text"]
        }"""
        override suspend fun execute(arguments: String): String {
            guard(engine)?.let { return it }
            val ref = argStr(arguments, "ref") ?: return "Error: 缺少 ref 参数"
            val text = argStr(arguments, "text") ?: return "Error: 缺少 text 参数"
            val st = engine.inputText(ref, text)
            return st.toText()
        }
    }

    /** <select> 下拉选择 */
    val select = object : AgentTool {
        override val id = "browser_select"
        override val name = "browser_select"
        override val description =
            "在下拉框（ref 定位）中选择选项：默认按 option 的 value 匹配，by_text=true 时按可见文本匹配。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{
                "ref":{"type":"string","description":"select 元素的稳定引用"},
                "value":{"type":"string","description":"要选中的 option 的 value 或可见文本"},
                "by_text":{"type":"boolean","description":"true 时按可见文本匹配，默认 false 按 value"}
            },
            "required":["ref","value"]
        }"""
        override suspend fun execute(arguments: String): String {
            guard(engine)?.let { return it }
            val ref = argStr(arguments, "ref") ?: return "Error: 缺少 ref"
            val value = argStr(arguments, "value") ?: return "Error: 缺少 value"
            val byText = argBool(arguments, "by_text")
            val st = engine.selectOption(ref, value, byText)
            return st.toText()
        }
    }

    /** checkbox / radio 切换（物理触摸点击） */
    val toggle = object : AgentTool {
        override val id = "browser_toggle"
        override val name = "browser_toggle"
        override val description = "切换勾选框 / 单选框（ref 定位），返回切换后状态。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{"ref":{"type":"string","description":"checkbox/radio 元素的稳定引用"}},
            "required":["ref"]
        }"""
        override suspend fun execute(arguments: String): String {
            guard(engine)?.let { return it }
            val ref = argStr(arguments, "ref") ?: return "Error: 缺少 ref"
            val st = engine.toggle(ref)
            return st.toText()
        }
    }

    /** 滚动页面（支持无限滚动检测） */
    val scroll = object : AgentTool {
        override val id = "browser_scroll"
        override val name = "browser_scroll"
        override val description = "在页面内滚动，delta_y 为正向下、为负向上。wait_for_new=true 时检测无限滚动新内容加载。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{
                "delta_y":{"type":"integer","description":"滚动像素，正向下负向上，默认 400"},
                "wait_for_new":{"type":"boolean","description":"是否在滚动后等待新内容加载并报告新增元素数"}
            }
        }"""
        override suspend fun execute(arguments: String): String {
            guard(engine)?.let { return it }
            val dy = argInt(arguments, "delta_y", 400)
            val waitNew = argBool(arguments, "wait_for_new")
            val r = engine.scroll(dy, waitNew)
            return if (r.newElementsDetected > 0) "已滚动 $dy 像素，检测到新增 ${r.newElementsDetected} 个元素，建议重新 snapshot"
            else "已滚动 $dy 像素"
        }
    }

    /** 整页/视口截图（PNG base64） */
    val screenshot = object : AgentTool {
        override val id = "browser_screenshot"
        override val name = "browser_screenshot"
        override val description =
            "对当前浏览器页面视口截图，返回 PNG 的 base64。供视觉模型理解渲染效果或验证码等图形内容。"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String): String {
            guard(engine)?.let { return it }
            val bytes = engine.screenshot() ?: return "Error: 无法截图（无激活页面）"
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            return "data:image/png;base64,$b64"
        }
    }

    /** 展开/收起浮窗，进入/退出人工接管（显式握手） */
    val show = object : AgentTool {
        override val id = "browser_show"
        override val name = "browser_show"
        override val description =
            "展开内置浏览器浮窗，进入人工接管模式（用于登录/过验证码）。人类完成后应点击浮窗上的「我已完成操作」交还 Agent。expand=false 收起浮窗。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{"expand":{"type":"boolean","description":"true 展开并进入接管，false 收起，默认 true"}}
        }"""
        override suspend fun execute(arguments: String): String {
            val expand = argBool(arguments, "expand", true)
            if (expand) {
                engine.enterHandoffMode()
                "已展开浮窗并进入人工接管。Agent 自动化工具已锁定，等待人类点击「我已完成操作」。"
            } else {
                engine.completeHandoff()
                "已收起浮窗并交还 Agent。已自动持久化 Cookie 并刷新快照。"
            }
        }
    }

    /** 文件上传：Agent 指定本地文件路径完成 <input type=file> */
    val fileUpload = object : AgentTool {
        override val id = "browser_file_upload"
        override val name = "browser_file_upload"
        override val description =
            "在文件选择对话框挂起时，由 Agent 提供本地文件路径完成上传。若未处于等待状态，需先用 browser_show 让人手动选择。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{"path":{"type":"string","description":"本地文件绝对路径"}}
        }"""
        override suspend fun execute(arguments: String): String {
            val path = argStr(arguments, "path") ?: return "Error: 缺少 path"
            // 安全加固（清单 11.12）：校验路径，防路径遍历（../ 越界）与越权目录访问
            val safe = safeUploadPath(path) ?: return "Error: 路径不合法或超出允许目录（${path}）"
            val uri = android.net.Uri.fromFile(java.io.File(safe))
            return if (engine.respondFileChooser(uri)) "已注入文件: $safe"
            else "当前没有等待中的文件选择对话框，请用 browser_show 让人手动选择。"
        }
    }

    /** 全部浏览器工具 */
    /** 导出最近 N 步浏览器操作 trace（P1 #9 可观测性） */
    val debugDump = object : AgentTool {
        override val id = "browser_debug_dump"
        override val name = "browser_debug_dump"
        override val description =
            "导出最近 N 步浏览器工具调用的完整 trace（工具名/参数/结果/耗时/URL/状态），用于调试 Agent 行为。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{"limit":{"type":"integer","description":"导出最近多少步，默认 20，最大 100"}}
        }"""
        override suspend fun execute(arguments: String): String {
            val limit = argInt(arguments, "limit", 20).coerceIn(1, 100)
            val lines = tracer.recent(limit).mapIndexed { i, e ->
                "[${i + 1}] ${e.timestamp} ${e.tool}\n   参数: ${e.params}\n   结果: ${e.resultSummary}\n   耗时: ${e.durationMs}ms | url=${e.url} | 状态=${e.state}"
            }
            return if (lines.isEmpty()) "(暂无 trace 记录)" else lines.joinToString("\n\n")
        }
    }

    /** 生成压缩的浏览器任务进度摘要（P1 #8 上下文窗口管理轻量版） */
    val contextSummary = object : AgentTool {
        override val id = "browser_context_summary"
        override val name = "browser_context_summary"
        override val description =
            "生成浏览器任务进度压缩摘要：最近 3 步保留详情，更早步骤压缩为单行。用于多步任务中控制上下文体积。"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String): String = tracer.contextSummary()
    }

    /**
     * trace 包装：所有浏览器工具经此包装，统一记录可观测性（P1 #9）。
     * 在 guard 之后、实际执行前后记录，覆盖成功/锁定/异常三种结果。
     */
    private class TracedTool(
        private val delegate: AgentTool,
        private val engine: BrowserEngine,
        private val tracer: BrowserTracer,
    ) : AgentTool {
        override val id get() = delegate.id
        override val name get() = delegate.name
        override val description get() = delegate.description
        override val parametersSchema get() = delegate.parametersSchema

        override suspend fun execute(arguments: String): String {
            val start = System.currentTimeMillis()
            val loggedParams = sanitizeParams(delegate.id, arguments) // 入 trace 前脱敏敏感字段
            return try {
                val result = delegate.execute(arguments)
                tracer.record(delegate.id, loggedParams, result.take(200),
                    System.currentTimeMillis() - start, engine.activeTab()?.url, engine.currentState.name)
                result
            } catch (e: Throwable) {
                tracer.record(delegate.id, loggedParams, "ERROR: ${e.message?.take(160)}",
                    System.currentTimeMillis() - start, engine.activeTab()?.url, engine.currentState.name)
                throw e
            }
        }
    }

    /** 日期/时间输入：HTML input[type=date|time|datetime-local] 的 value 为 ISO 字符串（如 2026-08-13 / 14:30） */
    val dateInput = object : AgentTool {
        override val id = "browser_date_input"
        override val name = "browser_date_input"
        override val description =
            "为日期/时间类输入框（input[type=date|time|datetime-local]，browser_snapshot 给出的 ref）设置值。" +
            "value 使用 ISO 格式：date 为 YYYY-MM-DD，time 为 HH:MM，datetime-local 为 YYYY-MM-DDTHH:MM。会触发 input/change 事件。"
        override val parametersSchema = """{
            "type":"object",
            "properties":{
                "ref":{"type":"string","description":"日期/时间输入框元素的稳定引用"},
                "value":{"type":"string","description":"ISO 格式的日期/时间值，如 2026-08-13 或 14:30"}
            },
            "required":["ref","value"]
        }"""
        override suspend fun execute(arguments: String): String {
            guard(engine)?.let { return it }
            val ref = argStr(arguments, "ref") ?: return "Error: 缺少 ref 参数"
            val value = argStr(arguments, "value") ?: return "Error: 缺少 value 参数"
            // 复用通用文本输入通道设置 value（日期输入本质即设置 ISO 字符串值 + 触发事件）
            val st = engine.inputText(ref, value)
            return st.toText()
        }
    }

    /**
     * 可观测性脱敏（清单 11.8）：入 trace 的参数屏蔽敏感字段明文。
     * - browser_input 的 text（可能是密码/Token）
     * - browser_select 的 value、browser_file_upload 的 path（路径含用户名等）
     * 仅替换值，保留字段名与结构，便于排障又不泄露敏感信息。
     */
    private fun sanitizeParams(toolId: String, args: String): String {
        val redact = { key: String ->
            args.replace(Regex("\"$key\"\\s*:\\s*\"[^\"]*\""), "\"$key\":\"***\"")
        }
        return when (toolId) {
            "browser_input" -> redact("text")
            "browser_select" -> redact("value")
            "browser_file_upload" -> redact("path")
            else -> args
        }.take(200)
    }

    fun all(): List<AgentTool> = listOf(
        navigate, snapshot, click, input, select, toggle, scroll, screenshot, show,
        fileUpload, dateInput, debugDump, contextSummary, networkLog, downloadList
    ).map { TracedTool(it, engine, tracer) }
}
