package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * # More Capabilities Tools (v5)
 *
 * 用户原始诉求："全面增强 agent 能力……然后还要有更多能力"。
 *
 * 本文件扩展了设备的实用能力（联系人 / 短信 / 日历 / 闹钟 / 文件选择 /
 * 拍照 / 系统通知），与已有的 torch / vibrate / battery_status / tts_speak /
 * share_content / deep_link / image_info / image_convert 等高级设备工具互补。
 *
 * 设计：
 * - 工具本体只做参数解析（pure JVM，可测）；
 * - Android 侧实现注入为 lambda，由 [com.apex.agent.tools.MoreCapabilitiesHost]
 *   桥接到 ContentResolver / SmsManager / AlarmManager / NotificationManager 等
 *   系统 API；
 * - 风险与权限：每个工具的元数据显式声明风险级别与必要权限（用户/会话门控
 *   会处理 HIGH/MEDIUM 的弹窗确认）。
 *
 * 工具清单（本文件 7 个工具）：
 *   1. `contact_search` —— 在通讯录中按姓名/电话/邮箱检索联系人
 *   2. `sms_send` —— 发送 SMS 短信
 *   3. `calendar_event_list` —— 读取近期日历事件
 *   4. `alarm_set` —— 设置系统闹钟（Intent 路径，无特殊权限）
 *   5. `file_pick` —— 经 SAF 让用户选文件，返回 URI
 *   6. `image_capture` —— 启动相机拍照（Intent 路径）
 *   7. `notification_post` —— 发系统通知（HIGH 风险：影响用户感官）
 */

/**
 * 联系人精简视图（用于 LLM 阅读）——只暴露非敏感字段。
 */
@Serializable
data class ContactRecord(
    val id: String,
    val displayName: String,
    val phoneNumbers: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val organization: String = ""
)

/**
 * 日历事件精简视图。
 */
@Serializable
data class CalendarEventRecord(
    val id: String,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean = false,
    val calendarName: String = ""
)

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 1: contact_search
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `contact_search` — 在通讯录中检索联系人。
 *
 * 参数：query（必填，模糊匹配姓名/电话/邮箱）、limit（默认 20，最大 100）。
 * 返回 JSON 数组。
 *
 * 风险 LOW（读通讯录需 READ_CONTACTS 权限，但读取本身是查询行为）。
 */
class ContactSearchTool(
    private val searcher: suspend (query: String, limit: Int) -> List<ContactRecord>
) : BaseTool(
    id = "contact_search",
    name = "Search Contacts",
    description = """
        Search the device contacts by name / phone / email (fuzzy match).
        Returns up to `limit` matches as a JSON array. Requires READ_CONTACTS
        permission; if missing, the host will return a permission error.
        Examples:
        - {"query": "张三"}
        - {"query": "13800138000"}
        - {"query": "john@example.com", "limit": 5}
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("query", required = true,
            description = "Search query (matches name/phone/email)")
        integer("limit", description = "Max results (default 20, max 100)",
            minimum = 1.0, maximum = 100.0, defaultValue = 20L)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SYSTEM)
        risk(ToolRisk.LOW)
        tag("contact", "phonebook", "search", "personal")
        // 标记 sensitiveAction：读通讯录是个人敏感数据
        annotations(
            ToolAnnotations(
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = false,
                sensitiveAction = true
            )
        )
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val query = args.requireString("query").trim()
        if (query.length < MIN_QUERY_LEN) {
            return ToolResult.invalid(
                field = "query",
                message = "query must be at least $MIN_QUERY_LEN characters (got ${query.length})",
                suggestion = "use a longer search term"
            )
        }
        val limit = args.intWithDefault("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val results = searcher(query, limit)
        val json = MoreCapsJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ContactRecord.serializer()),
            results
        )
        return ToolResult.ok(
            if (results.isEmpty()) {
                "No contacts matched '$query'."
            } else {
                json
            }
        )
    }

    companion object {
        const val MIN_QUERY_LEN = 2
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 2: sms_send
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `sms_send` — 发送 SMS 短信。
 *
 * 参数：phone（必填，目标手机号）、message（必填，短信正文）、
 *        slot_id（可选，双卡 SIM 槽位 0/1，默认自动选择）。
 *
 * 风险 HIGH（涉及短信发送费用 + 第三方通信），会话级确认。
 * 需要 SEND_SMS 权限（普通权限，运行时无需弹窗）。
 */
class SmsSendTool(
    private val sender: suspend (phone: String, message: String, slotId: Int?) -> SendResult
) : BaseTool(
    id = "sms_send",
    name = "Send SMS",
    description = """
        Send an SMS message to a phone number. Risk: HIGH (sends a real SMS —
        cost and side effects). Requires SEND_SMS permission. Confirm with the
        user before sending; the risk gate will prompt once per session.

        Parameters:
        - phone (required): target phone number (international format recommended,
          e.g. +8613800138000)
        - message (required): message body (≤160 chars for single-segment SMS;
          longer messages are auto-split into multi-segment)
        - slot_id (optional): SIM slot for dual-SIM devices (0 or 1). Omit for
          auto-select.

        Examples:
        - {"phone": "+8613800138000", "message": "Hello from Apex Agent"}
        - {"phone": "13800138000", "message": "会议已开始", "slot_id": 0}
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("phone", required = true,
            description = "Target phone number (international format recommended)")
        string("message", required = true,
            description = "Message body (1..1000 chars; longer auto-split)")
        integer("slot_id",
            description = "SIM slot for dual-SIM (0 or 1). Omit for auto-select.",
            minimum = 0.0, maximum = 1.0)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SYSTEM)
        risk(ToolRisk.HIGH)
        tag("sms", "message", "phone", "send")
        annotations(
            ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = true,
                sensitiveAction = true
            )
        )
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val phone = args.requireString("phone").trim()
        val message = args.requireString("message")
        if (!PHONE_REGEX.matches(phone)) {
            return ToolResult.invalid(
                field = "phone",
                message = "phone '$phone' is not a valid phone number",
                suggestion = "use international format (+8613800138000) or 11-digit national"
            )
        }
        if (message.isBlank()) {
            return ToolResult.invalid(
                field = "message",
                message = "message cannot be blank"
            )
        }
        if (message.length > MAX_MESSAGE_LEN) {
            return ToolResult.invalid(
                field = "message",
                message = "message too long (${message.length} > $MAX_MESSAGE_LEN chars)",
                suggestion = "split into multiple sms_send calls"
            )
        }
        val slotId = args.optionalInt("slot_id")
        if (slotId != null && slotId !in 0..1) {
            return ToolResult.invalid(
                field = "slot_id",
                message = "slot_id must be 0 or 1 (got $slotId)"
            )
        }
        val result = sender(phone, message, slotId)
        return if (result.success) {
            ToolResult.ok("OK: SMS sent to $phone (${message.length} chars${slotId?.let { ", slot $it" } ?: ""}). ${result.message}")
        } else {
            ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "SMS send failed: ${result.message}")
        }
    }

    /** 发送结果。 */
    data class SendResult(val success: Boolean, val message: String)

    companion object {
        const val MAX_MESSAGE_LEN = 1000
        // 简单手机号校验：可选 + 前缀 + 7..15 位数字
        val PHONE_REGEX = Regex("^\\+?\\d{7,15}\$")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 3: calendar_event_list
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `calendar_event_list` — 读取近期日历事件。
 *
 * 参数：days_ahead（默认 7，最大 90）、limit（默认 20，最大 100）。
 * 返回从当前时刻起 +days_ahead 天范围内的事件列表（按开始时间升序）。
 *
 * 风险 LOW（读日历需 READ_CALENDAR 权限，读取本身是查询行为）。
 */
class CalendarEventListTool(
    private val lister: suspend (daysAhead: Int, limit: Int) -> List<CalendarEventRecord>
) : BaseTool(
    id = "calendar_event_list",
    name = "List Calendar Events",
    description = """
        List upcoming calendar events from now to now+days_ahead days.
        Returns a JSON array sorted by start time ascending. Requires
        READ_CALENDAR permission.
        Examples:
        - {}                              # next 7 days, up to 20 events
        - {"days_ahead": 30, "limit": 50}
    """.trimIndent(),
    declaredSchema = toolSchema {
        integer("days_ahead",
            description = "Days from now to look ahead (default 7, max 90)",
            minimum = 1.0, maximum = 90.0, defaultValue = 7L)
        integer("limit",
            description = "Max events to return (default 20, max 100)",
            minimum = 1.0, maximum = 100.0, defaultValue = 20L)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SYSTEM)
        risk(ToolRisk.LOW)
        tag("calendar", "event", "schedule", "personal")
        annotations(
            ToolAnnotations(
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = false,
                sensitiveAction = true
            )
        )
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val daysAhead = args.intWithDefault("days_ahead", DEFAULT_DAYS_AHEAD).coerceIn(1, MAX_DAYS_AHEAD)
        val limit = args.intWithDefault("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val events = lister(daysAhead, limit)
        val json = MoreCapsJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(CalendarEventRecord.serializer()),
            events
        )
        return ToolResult.ok(
            if (events.isEmpty()) {
                "No calendar events in the next $daysAhead days."
            } else {
                json
            }
        )
    }

    companion object {
        const val DEFAULT_DAYS_AHEAD = 7
        const val MAX_DAYS_AHEAD = 90
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 4: alarm_set
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `alarm_set` — 设置系统闹钟。
 *
 * 走 [android.provider.AlarmClock.ACTION_SET_ALARM] Intent 路径——无任何
 * 特殊权限，启动系统闹钟 App 让用户确认。
 *
 * 参数：
 * - hour (required): 0..23
 * - minutes (required): 0..59
 * - message (optional): 闹钟标签
 * - days_of_week (optional): 重复星期，1=周日..7=周六 数组（空或省略=单次）
 * - vibrate (optional, default true): 闹钟振动
 * - skip_ui (optional, default false): true=跳过 UI 直接设置（需要 deskclock
 *   支持 EXTRA_SKIP_UI，部分 ROM 无效）
 *
 * 风险 MEDIUM（影响用户感官与作息），需弹窗确认。
 */
class AlarmSetTool(
    private val setter: suspend (AlarmParams) -> Boolean
) : BaseTool(
    id = "alarm_set",
    name = "Set Alarm",
    description = """
        Set an alarm via the system clock app (AlarmClock.ACTION_SET_ALARM).
        Launches the alarm app to confirm the alarm. No special permission needed.

        Parameters:
        - hour (required, 0..23)
        - minutes (required, 0..59)
        - message (optional): alarm label
        - days_of_week (optional): array of 1..7 (1=Sunday, 7=Saturday) for repeating alarms; omit for one-shot
        - vibrate (optional, default true)
        - skip_ui (optional, default false): if true, attempt to skip the confirmation UI (not all ROMs support this)

        Examples:
        - {"hour": 7, "minutes": 30, "message": "Wake up"}
        - {"hour": 9, "minutes": 0, "days_of_week": [2, 3, 4, 5, 6], "message": "Work"}
    """.trimIndent(),
    declaredSchema = toolSchema {
        integer("hour", required = true, description = "Hour 0..23",
            minimum = 0.0, maximum = 23.0)
        integer("minutes", required = true, description = "Minutes 0..59",
            minimum = 0.0, maximum = 59.0)
        string("message", description = "Alarm label (e.g. 'Wake up')")
        // days_of_week 是 int array —— schema 里仅声明 array，运行时校验元素
        // （ToolSchema DSL 不直接支持 items 类型，故运行时手动校验）
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SYSTEM)
        risk(ToolRisk.MEDIUM)
        tag("alarm", "clock", "schedule", "wake")
        annotations(ToolAnnotations.mutating())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val hour = args.requireInt("hour")
        val minutes = args.requireInt("minutes")
        if (hour !in 0..23) {
            return ToolResult.invalid("hour", "hour must be 0..23 (got $hour)")
        }
        if (minutes !in 0..59) {
            return ToolResult.invalid("minutes", "minutes must be 0..59 (got $minutes)")
        }
        val message = args.optionalString("message") ?: ""
        // days_of_week 是 int array，ToolArguments DSL 不直接支持 int 数组，
        // 手动从 rawElement 取 JsonArray 转 List<Int>
        val daysOfWeek: List<Int>? = args.rawElement("days_of_week")?.let { elem ->
            val arr = elem as? JsonArray
                ?: return ToolResult.invalid(
                    "days_of_week",
                    "days_of_week must be an array of integers 1..7"
                )
            arr.mapNotNull { item ->
                (item as? JsonPrimitive)?.longOrNull?.toInt()
                    ?: return ToolResult.invalid(
                        "days_of_week",
                        "all entries must be integers (got '$item')"
                    )
            }
        }
        if (daysOfWeek != null) {
            val bad = daysOfWeek.filter { it !in 1..7 }
            if (bad.isNotEmpty()) {
                return ToolResult.invalid(
                    "days_of_week",
                    "all days must be 1..7 (got $bad)"
                )
            }
        }
        val vibrate = args.booleanWithDefault("vibrate", true)
        val skipUi = args.booleanWithDefault("skip_ui", false)

        val params = AlarmParams(
            hour = hour,
            minutes = minutes,
            message = message,
            daysOfWeek = daysOfWeek ?: emptyList(),
            vibrate = vibrate,
            skipUi = skipUi
        )
        val ok = setter(params)
        return if (ok) {
            val repeatInfo = if (params.daysOfWeek.isEmpty()) "one-shot"
            else "repeat on ${params.daysOfWeek.sorted().joinToString(",") { dayName(it) }}"
            ToolResult.ok(
                "OK: alarm set for ${"%02d".format(hour)}:${"%02d".format(minutes)}" +
                    (if (message.isBlank()) "" else " ('$message')") +
                    " [$repeatInfo, vibrate=$vibrate, skip_ui=$skipUi]." +
                    " The alarm app may have launched to confirm."
            )
        } else {
            ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "alarm set failed — no alarm app available?")
        }
    }

    /** 把 1..7 转为 Sun..Sat。 */
    private fun dayName(day: Int): String = when (day) {
        1 -> "Sun"; 2 -> "Mon"; 3 -> "Tue"; 4 -> "Wed"
        5 -> "Thu"; 6 -> "Fri"; 7 -> "Sat"; else -> "?"
    }

    /** 闹钟参数（用于宿主接收）。 */
    @Serializable
    data class AlarmParams(
        val hour: Int,
        val minutes: Int,
        val message: String,
        val daysOfWeek: List<Int>,
        val vibrate: Boolean,
        val skipUi: Boolean
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 5: file_pick
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `file_pick` — 经 SAF (Storage Access Framework) 让用户选文件。
 *
 * 走 [android.content.Intent.ACTION_OPEN_DOCUMENT] ——返回 content:// URI。
 * Agent 拿到 URI 后可用 [http_request] / 自定义 file_copy 把文件复制到工作区
 * 或上传到外部服务。
 *
 * 参数：
 * - mime_type (optional, 默认全部类型): MIME 过滤，可传类型前缀（如 image、application/pdf）
 * - allow_multiple (optional, default false): 允许多选
 *
 * 风险 MEDIUM（用户主动选文件，但启动 Activity 涉及 UI 交互）。
 *
 * 注意：本工具是异步的——它会阻塞等待用户在弹出的文件选择器中选完。
 * 实际实现走 Activity Result API；Agent 调用方需等待返回。
 */
class FilePickTool(
    private val picker: suspend (mimeType: String, allowMultiple: Boolean) -> FilePickResult
) : BaseTool(
    id = "file_pick",
    name = "Pick File (SAF)",
    description = """
        Open the system file picker (Storage Access Framework) and let the
        user select a file. Returns content:// URIs that can be used with
        http_request (multipart upload) or copied to workspace via shell.

        Parameters:
        - mime_type (optional, default "*/*"): MIME filter, e.g. "image/*" or "application/pdf"
        - allow_multiple (optional, default false): allow multi-select

        Examples:
        - {}                                   # any file, single
        - {"mime_type": "image/*"}             # images only
        - {"mime_type": "application/pdf", "allow_multiple": true}

        Note: this tool blocks until the user finishes picking. If the user
        cancels, returns "no selection".
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("mime_type",
            description = "MIME filter (default */*, e.g. image/*, application/pdf, text/*)")
        boolean("allow_multiple",
            description = "Allow multi-select (default false)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.FILE)
        risk(ToolRisk.MEDIUM)
        tag("file", "picker", "saf", "select", "open")
        annotations(ToolAnnotations.mutating())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val mimeType = args.stringWithDefault("mime_type", "*/*").trim()
        if (mimeType.isBlank() || !mimeType.contains("/")) {
            return ToolResult.invalid(
                "mime_type",
                "mime_type must be a valid MIME (got '$mimeType')"
            )
        }
        val allowMultiple = args.booleanWithDefault("allow_multiple", false)
        val result = picker(mimeType, allowMultiple)
        return if (result.uris.isEmpty()) {
            ToolResult.ok("User cancelled — no file selected.")
        } else {
            val json = MoreCapsJson.encodeToString(FilePickResult.serializer(), result)
            ToolResult.ok(json)
        }
    }

    /** 文件选择结果。 */
    @Serializable
    data class FilePickResult(
        val uris: List<String>,
        val displayNames: List<String> = emptyList(),
        val mimeTypes: List<String> = emptyList(),
        val sizes: List<Long> = emptyList()
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 6: image_capture
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `image_capture` — 启动相机拍照。
 *
 * 走 [android.provider.MediaStore.ACTION_IMAGE_CAPTURE] Intent 路径——
 * 系统相机 App 拍照后返回缩略图或原图 URI。
 *
 * 参数：
 * - quality (optional, default 80): JPEG 质量 0..100
 * - save_path (optional): 自定义保存路径（默认存到工作区/Pictures/）
 * - return_thumbnail (optional, default true): 返回 base64 缩略图
 *
 * 风险 MEDIUM（启动相机涉及隐私与电池），需弹窗确认。
 */
class ImageCaptureTool(
    private val capturer: suspend (CaptureParams) -> CaptureResult
) : BaseTool(
    id = "image_capture",
    name = "Capture Photo",
    description = """
        Launch the camera app to take a photo. Returns the saved file path
        and an optional base64 thumbnail.

        Parameters:
        - quality (optional, default 80): JPEG quality 0..100
        - save_path (optional): absolute path to save the photo (default: Pictures dir)
        - return_thumbnail (optional, default true): include a small base64 thumbnail

        Examples:
        - {}                              # default quality, default path, with thumbnail
        - {"quality": 100, "return_thumbnail": false}
        - {"save_path": "/sdcard/Pictures/photo.jpg"}

        Note: this tool blocks until the user takes or cancels the photo.
    """.trimIndent(),
    declaredSchema = toolSchema {
        integer("quality",
            description = "JPEG quality 0..100 (default 80)",
            minimum = 1.0, maximum = 100.0, defaultValue = 80L)
        string("save_path",
            description = "Absolute path to save the photo (default: Pictures dir)")
        boolean("return_thumbnail",
            description = "Include a base64 thumbnail (default true)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SENSOR)
        risk(ToolRisk.MEDIUM)
        tag("camera", "photo", "image", "capture")
        annotations(
            ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = false,
                sensitiveAction = true
            )
        )
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val quality = args.intWithDefault("quality", DEFAULT_QUALITY).coerceIn(1, 100)
        val savePath = args.optionalString("save_path")?.trim()
        if (savePath != null && (!savePath.startsWith("/") || ".." in savePath)) {
            return ToolResult.invalid(
                "save_path",
                "save_path must be an absolute path without '..' (got '$savePath')"
            )
        }
        val returnThumb = args.booleanWithDefault("return_thumbnail", true)
        val params = CaptureParams(quality, savePath, returnThumb)
        val result = capturer(params)
        return if (result.success) {
            ToolResult.ok(
                "OK: photo captured.\n" +
                    "  path: ${result.savedPath}\n" +
                    "  size: ${result.sizeBytes ?: "?"} bytes\n" +
                    "  mime: ${result.mimeType ?: "?"}" +
                    (if (returnThumb && result.thumbnailBase64 != null) "\n  thumbnail: (base64, ${result.thumbnailBase64.length} chars)" else "")
            )
        } else {
            ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "image capture failed: ${result.errorMessage ?: "no error message"}"
            )
        }
    }

    @Serializable
    data class CaptureParams(
        val quality: Int,
        val savePath: String?,
        val returnThumbnail: Boolean
    )

    @Serializable
    data class CaptureResult(
        val success: Boolean,
        val savedPath: String = "",
        val sizeBytes: Long? = null,
        val mimeType: String? = null,
        val thumbnailBase64: String? = null,
        val errorMessage: String? = null
    )

    companion object {
        const val DEFAULT_QUALITY = 80
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 7: notification_post
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `notification_post` — 发系统通知。
 *
 * 通过 [android.app.NotificationManager] 发通知。需要 POST_NOTIFICATIONS
 * 权限（Android 13+）。无声音/振动（仅状态栏 + 抽屉可见）；如需提醒，
 * 调用方应配合 [vibrate] / [tts_speak]。
 *
 * 参数：
 * - title (required): 通知标题
 * - text (required): 通知正文
 * - channel_id (optional, default "agent_default"): 通知渠道
 * - tag (optional, default "agent"): 通知标签（用于后续 cancel）
 * - id (optional, default 0): 通知 id（同 id 替换，不同 id 共存）
 *
 * 风险 MEDIUM（影响用户感官，但不破坏状态）。
 */
class NotificationPostTool(
    private val poster: suspend (NotificationParams) -> Boolean
) : BaseTool(
    id = "notification_post",
    name = "Post Notification",
    description = """
        Post a system notification (status bar + drawer). Uses a silent
        channel by default — pair with vibrate / tts_speak for audible alerts.

        Parameters:
        - title (required): notification title
        - text (required): notification body
        - channel_id (optional, default "agent_default"): notification channel
        - tag (optional, default "agent"): notification tag (for later cancel)
        - id (optional, default 0): notification id (same id replaces; different ids coexist)

        Examples:
        - {"title": "Task Done", "text": "Your build finished"}
        - {"title": "Reminder", "text": "Stand up!", "channel_id": "agent_reminder"}
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("title", required = true, description = "Notification title")
        string("text", required = true, description = "Notification body")
        string("channel_id",
            description = "Notification channel id (default 'agent_default')")
        string("tag", description = "Notification tag (default 'agent')")
        integer("id", description = "Notification id (default 0)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SYSTEM)
        risk(ToolRisk.MEDIUM)
        tag("notification", "post", "notify", "alert")
        annotations(ToolAnnotations.idempotentWrite())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val title = args.requireString("title").trim()
        val text = args.requireString("text").trim()
        if (title.isEmpty() || text.isEmpty()) {
            return ToolResult.invalid(
                field = if (title.isEmpty()) "title" else "text",
                message = "title and text must be non-empty"
            )
        }
        if (title.length > MAX_TITLE_LEN || text.length > MAX_TEXT_LEN) {
            return ToolResult.invalid(
                field = if (title.length > MAX_TITLE_LEN) "title" else "text",
                message = "too long (title ≤$MAX_TITLE_LEN, text ≤$MAX_TEXT_LEN)"
            )
        }
        val channelId = args.stringWithDefault("channel_id", DEFAULT_CHANNEL)
        val tag = args.stringWithDefault("tag", DEFAULT_TAG)
        val id = args.optionalInt("id") ?: DEFAULT_ID
        val params = NotificationParams(
            title = title,
            text = text,
            channelId = channelId,
            tag = tag,
            id = id
        )
        val ok = poster(params)
        return if (ok) {
            ToolResult.ok("OK: notification posted (tag='$tag', id=$id, channel='$channelId').")
        } else {
            ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "notification post failed — POST_NOTIFICATIONS permission may be missing (Android 13+)"
            )
        }
    }

    @Serializable
    data class NotificationParams(
        val title: String,
        val text: String,
        val channelId: String,
        val tag: String,
        val id: Int
    )

    companion object {
        const val MAX_TITLE_LEN = 100
        const val MAX_TEXT_LEN = 500
        const val DEFAULT_CHANNEL = "agent_default"
        const val DEFAULT_TAG = "agent"
        const val DEFAULT_ID = 0
    }
}

// ═══════════════════════════════════════════════════════════════════════════════

/** 模块级 JSON 实例（与 builtin 包共用，pretty print 给 LLM 易读）。 */
internal val MoreCapsJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}
