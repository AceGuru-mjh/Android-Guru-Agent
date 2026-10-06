package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.builtin.AlarmSetTool
import com.apex.agent.core.tools.builtin.CalendarEventListTool
import com.apex.agent.core.tools.builtin.ContactSearchTool
import com.apex.agent.core.tools.builtin.FilePickTool
import com.apex.agent.core.tools.builtin.ImageCaptureTool
import com.apex.agent.core.tools.builtin.NotificationPostTool
import com.apex.agent.core.tools.builtin.SmsSendTool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更多能力工具的纯 JVM 测试。
 *
 * 覆盖参数解析 + 校验逻辑（companion 纯函数 / 工具入口）；Android 实现
 * 由各 Host 类提供，本测试不依赖 Android。
 */
class MoreCapabilitiesToolsTest {

    // ── contact_search ──────────────────────────────────────────────────

    @Test
    fun `contact_search rejects too short query`() = runTest {
        val tool = ContactSearchTool { _, _ -> emptyList() }
        val result = tool.execute("""{"query": "a"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention query: $result", result.contains("query"))
    }

    @Test
    fun `contact_search returns empty message when no match`() = runTest {
        val tool = ContactSearchTool { _, _ -> emptyList() }
        val result = tool.execute("""{"query": "张三"}""")
        assertTrue("should mention no contacts: $result", result.contains("No contacts"))
    }

    @Test
    fun `contact_search returns JSON when matches found`() = runTest {
        val tool = ContactSearchTool { q, _ ->
            listOf(
                com.apex.agent.core.tools.builtin.ContactRecord(
                    id = "1",
                    displayName = "Zhang San",
                    phoneNumbers = listOf("+8613800138000"),
                    emails = listOf("zs@example.com")
                )
            )
        }
        val result = tool.execute("""{"query": "zhang", "limit": 5}""")
        assertTrue("should be JSON: $result", result.contains("Zhang San"))
        assertTrue("should contain phone: $result", result.contains("+8613800138000"))
    }

    @Test
    fun `contact_search clamps limit to 100`() = runTest {
        var capturedLimit: Int = -1
        val tool = ContactSearchTool { _, lim -> capturedLimit = lim; emptyList() }
        tool.execute("""{"query": "test", "limit": 500}""")
        assertEquals(100, capturedLimit)
    }

    // ── sms_send ────────────────────────────────────────────────────────

    @Test
    fun `sms_send rejects invalid phone number`() = runTest {
        val tool = SmsSendTool { _, _, _ -> SmsSendTool.SendResult(true, "ok") }
        val result = tool.execute("""{"phone": "abc123", "message": "hello"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention phone: $result", result.contains("phone"))
    }

    @Test
    fun `sms_send rejects blank message`() = runTest {
        val tool = SmsSendTool { _, _, _ -> SmsSendTool.SendResult(true, "ok") }
        val result = tool.execute("""{"phone": "+8613800138000", "message": ""}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention message: $result", result.contains("message"))
    }

    @Test
    fun `sms_send rejects too long message`() = runTest {
        val tool = SmsSendTool { _, _, _ -> SmsSendTool.SendResult(true, "ok") }
        val longMsg = "x".repeat(1001)
        val result = tool.execute("""{"phone": "+8613800138000", "message": "$longMsg"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention too long: $result", result.contains("too long"))
    }

    @Test
    fun `sms_send rejects invalid slot_id`() = runTest {
        val tool = SmsSendTool { _, _, _ -> SmsSendTool.SendResult(true, "ok") }
        val result = tool.execute(
            """{"phone": "+8613800138000", "message": "hi", "slot_id": 5}"""
        )
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention slot: $result", result.contains("slot_id"))
    }

    @Test
    fun `sms_send succeeds with valid params`() = runTest {
        var capturedPhone: String? = null
        var capturedMsg: String? = null
        var capturedSlot: Int? = null
        val tool = SmsSendTool { p, m, s ->
            capturedPhone = p; capturedMsg = m; capturedSlot = s
            SmsSendTool.SendResult(true, "sent")
        }
        val result = tool.execute(
            """{"phone": "+8613800138000", "message": "hello", "slot_id": 0}"""
        )
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertEquals("+8613800138000", capturedPhone)
        assertEquals("hello", capturedMsg)
        assertEquals(0, capturedSlot)
    }

    @Test
    fun `sms_send reports send failure`() = runTest {
        val tool = SmsSendTool { _, _, _ -> SmsSendTool.SendResult(false, "no SIM card") }
        val result = tool.execute(
            """{"phone": "+8613800138000", "message": "hello"}"""
        )
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention no SIM: $result", result.contains("no SIM card"))
    }

    // ── calendar_event_list ────────────────────────────────────────────

    @Test
    fun `calendar_list returns empty message when no events`() = runTest {
        val tool = CalendarEventListTool { _, _ -> emptyList() }
        val result = tool.execute("""{}""")
        assertTrue("should mention no events: $result", result.contains("No calendar events"))
    }

    @Test
    fun `calendar_list returns JSON when events exist`() = runTest {
        val tool = CalendarEventListTool { _, _ ->
            listOf(
                com.apex.agent.core.tools.builtin.CalendarEventRecord(
                    id = "1",
                    title = "Team Standup",
                    startMs = 1700000000000L,
                    endMs = 1700000300000L
                )
            )
        }
        val result = tool.execute("""{"days_ahead": 7}""")
        assertTrue("should contain event: $result", result.contains("Team Standup"))
    }

    @Test
    fun `calendar_list clamps days_ahead to 90`() = runTest {
        var capturedDays: Int = -1
        val tool = CalendarEventListTool { d, _ -> capturedDays = d; emptyList() }
        tool.execute("""{"days_ahead": 365}""")
        assertEquals(90, capturedDays)
    }

    // ── alarm_set ──────────────────────────────────────────────────────

    @Test
    fun `alarm_set rejects hour out of range`() = runTest {
        val tool = AlarmSetTool { _ -> true }
        val result = tool.execute("""{"hour": 25, "minutes": 30}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention hour: $result", result.contains("hour"))
    }

    @Test
    fun `alarm_set rejects minutes out of range`() = runTest {
        val tool = AlarmSetTool { _ -> true }
        val result = tool.execute("""{"hour": 7, "minutes": 60}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention minutes: $result", result.contains("minutes"))
    }

    @Test
    fun `alarm_set succeeds with valid time`() = runTest {
        val tool = AlarmSetTool { _ -> true }
        val result = tool.execute("""{"hour": 7, "minutes": 30, "message": "Wake up"}""")
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertTrue("should mention time: $result", result.contains("07:30"))
        assertTrue("should mention label: $result", result.contains("Wake up"))
    }

    @Test
    fun `alarm_set rejects bad days_of_week`() = runTest {
        val tool = AlarmSetTool { _ -> true }
        val result = tool.execute(
            """{"hour": 9, "minutes": 0, "days_of_week": [0, 8]}"""
        )
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention days: $result", result.contains("days_of_week"))
    }

    @Test
    fun `alarm_set accepts valid days_of_week array`() = runTest {
        val tool = AlarmSetTool { _ -> true }
        val result = tool.execute(
            """{"hour": 9, "minutes": 0, "days_of_week": [2, 3, 4, 5, 6]}"""
        )
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertTrue("should mention repeat: $result", result.contains("repeat"))
    }

    @Test
    fun `alarm_set reports setter failure`() = runTest {
        val tool = AlarmSetTool { _ -> false }
        val result = tool.execute("""{"hour": 7, "minutes": 30}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    // ── file_pick ──────────────────────────────────────────────────────

    @Test
    fun `file_pick rejects invalid mime_type`() = runTest {
        val tool = FilePickTool { _, _ -> FilePickTool.FilePickResult(uris = emptyList()) }
        val result = tool.execute("""{"mime_type": "not_a_mime"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention mime: $result", result.contains("mime_type"))
    }

    @Test
    fun `file_pick returns empty result when user cancels`() = runTest {
        val tool = FilePickTool { _, _ -> FilePickTool.FilePickResult(uris = emptyList()) }
        val result = tool.execute("""{}""")
        assertTrue("should mention cancelled: $result", result.contains("cancelled") || result.contains("no file"))
    }

    // ── image_capture ──────────────────────────────────────────────────

    @Test
    fun `image_capture rejects save_path with dot-dot`() = runTest {
        val tool = ImageCaptureTool { _ ->
            ImageCaptureTool.CaptureResult(success = true, savedPath = "/tmp/x.jpg")
        }
        val result = tool.execute("""{"save_path": "/tmp/../escape.jpg"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention path: $result", result.contains("save_path"))
    }

    @Test
    fun `image_capture rejects relative save_path`() = runTest {
        val tool = ImageCaptureTool { _ ->
            ImageCaptureTool.CaptureResult(success = true, savedPath = "x.jpg")
        }
        val result = tool.execute("""{"save_path": "relative.jpg"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `image_capture clamps quality to 100`() = runTest {
        var capturedQuality: Int = -1
        val tool = ImageCaptureTool { p ->
            capturedQuality = p.quality
            ImageCaptureTool.CaptureResult(success = true, savedPath = "/tmp/x.jpg")
        }
        tool.execute("""{"quality": 200}""")
        assertEquals(100, capturedQuality)
    }

    @Test
    fun `image_capture reports capture failure`() = runTest {
        val tool = ImageCaptureTool { _ ->
            ImageCaptureTool.CaptureResult(success = false, errorMessage = "no camera app")
        }
        val result = tool.execute("""{}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention camera: $result", result.contains("no camera app"))
    }

    // ── notification_post ──────────────────────────────────────────────

    @Test
    fun `notification_post rejects empty title`() = runTest {
        val tool = NotificationPostTool { _ -> true }
        val result = tool.execute("""{"title": "", "text": "body"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `notification_post rejects empty text`() = runTest {
        val tool = NotificationPostTool { _ -> true }
        val result = tool.execute("""{"title": "hi", "text": ""}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `notification_post rejects too long title`() = runTest {
        val tool = NotificationPostTool { _ -> true }
        val longTitle = "x".repeat(101)
        val result = tool.execute("""{"title": "$longTitle", "text": "body"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }

    @Test
    fun `notification_post succeeds with valid params`() = runTest {
        val tool = NotificationPostTool { _ -> true }
        val result = tool.execute(
            """{"title": "Task Done", "text": "Build finished", "channel_id": "agent_reminder"}"""
        )
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertTrue("should mention channel: $result", result.contains("agent_reminder"))
    }

    @Test
    fun `notification_post reports poster failure`() = runTest {
        val tool = NotificationPostTool { _ -> false }
        val result = tool.execute("""{"title": "hi", "text": "body"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
    }
}
