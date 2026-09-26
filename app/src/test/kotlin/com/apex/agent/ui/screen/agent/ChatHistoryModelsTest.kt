package com.apex.agent.ui.screen.agent

import com.apex.agent.core.llm.LlmMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * P2 回归：历史消息附件路径持久化（删除会话同步清理附件的前置数据链路）。
 *
 * 兼容性关键点：线上已存在的旧格式 JSON 没有 attachmentPaths 字段 ——
 * 解码必须走默认空列表，绝不能抛 MissingFieldException（否则旧历史整条会话丢失）。
 */
class ChatHistoryModelsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun attachment(path: String?) = MessageAttachment(
        name = "f.bin",
        mimeType = "application/octet-stream",
        sizeBytes = 16L,
        type = AttachmentType.FILE,
        localPath = path
    )

    @Test fun `legacy message without attachmentPaths decodes to empty default`() {
        val legacy = """{"role":"user","text":"hi","timestamp":123}"""
        val m = json.decodeFromString(ChatHistoryMessage.serializer(), legacy)
        assertEquals("user", m.role)
        assertEquals("hi", m.text)
        assertTrue("旧数据无附件字段 → 默认空列表", m.attachmentPaths.isEmpty())
    }

    @Test fun `attachmentPaths survive a roundtrip`() {
        val m = ChatHistoryMessage(
            role = "user",
            text = "带附件",
            attachmentPaths = listOf("/data/files/attachments/1_a.pdf", "/data/files/attachments/2_b.png")
        )
        val encoded = json.encodeToString(ChatHistoryMessage.serializer(), m)
        val decoded = json.decodeFromString(ChatHistoryMessage.serializer(), encoded)
        assertEquals(m.attachmentPaths, decoded.attachmentPaths)
    }

    @Test fun `user message extracts attachment local paths`() {
        val ui = AgentUiMessage.User(
            text = "看下这两个文件",
            attachments = listOf(
                attachment("/data/files/attachments/x.pdf"),
                attachment(null),
                attachment("/data/files/attachments_pre/y.txt")
            )
        )
        val history = ui.toHistoryMessage()!!
        assertEquals(
            listOf("/data/files/attachments/x.pdf", "/data/files/attachments_pre/y.txt"),
            history.attachmentPaths
        )
    }

    @Test fun `agent and tool messages carry no attachment paths`() {
        val agent = AgentUiMessage.Agent(text = "done").toHistoryMessage()!!
        assertTrue(agent.attachmentPaths.isEmpty())
        val tool = AgentUiMessage.ToolCall(
            toolName = "fs_read", args = "{}", output = "ok", success = true, durationMs = 5
        ).toHistoryMessage()!!
        assertTrue(tool.attachmentPaths.isEmpty())
    }

    @Test fun `toLlmMessage keeps user agent pairs only`() {
        val user = ChatHistoryMessage("user", "q")
        val agent = ChatHistoryMessage("agent", "a")
        val system = ChatHistoryMessage("system", "note")
        assertTrue(user.toLlmMessage() is LlmMessage.User)
        assertTrue(agent.toLlmMessage() is LlmMessage.Assistant)
        assertNull(system.toLlmMessage())
    }
}
