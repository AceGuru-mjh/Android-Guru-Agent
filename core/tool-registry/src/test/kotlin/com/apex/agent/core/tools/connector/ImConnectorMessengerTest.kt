package com.apex.agent.core.tools.connector

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * 消息通道连接器（微信 / 飞书 / QQ）协议细节测试。
 *
 * 目的：这三家的接入**全部是"请求体形状正确才算成功"**的协议——签名算法写反、
 * receive_id_type 选错、token 端点拼错，服务端都只回一个错误码，排查成本极高。
 * 这里用假 HTTP 实现把每次调用捕获下来断言，不需要联网也不需要 MockWebServer。
 *
 * 断言的都是官方文档里最易踩坑的点（详见 docs/im-connectors.md）：
 * - 飞书签名是 `HmacSHA256(key = "{timestamp}\n{secret}", data = "")`，不是对消息体取摘要；
 * - 飞书自建应用的 `content` 必须是 JSON **字符串**；
 * - QQ 先换 app access token，再按 target 前缀选群/单聊/频道端点。
 */
class ImConnectorMessengerTest {

    // ── 假 HTTP ────────────────────────────────────────────

    private class FakeHttp(private val replies: MutableList<MessengerHttpReply>) : MessengerHttp {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()

        override fun post(url: String, body: String, headers: Map<String, String>): MessengerHttpReply {
            urls += url
            bodies += body
            this.headers += headers
            return if (replies.size > 1) replies.removeAt(0) else replies.first()
        }
    }

    private fun messenger(vararg replies: MessengerHttpReply): Pair<ConnectorMessenger, FakeHttp> {
        val http = FakeHttp(replies.toMutableList())
        return ConnectorMessenger(http) to http
    }

    private fun ok(body: String = """{"errcode":0}""") = MessengerHttpReply(200, body)

    private fun wechatDef(
        endpoint: String = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send",
        apiKey: String = "KEY",
        extra: Map<String, String> = emptyMap()
    ) = ConnectorDef(id = "wechat", name = "微信", type = "messaging", endpoint = endpoint, apiKey = apiKey, extra = extra)

    private fun feishuDef(
        endpoint: String = "https://open.feishu.cn/open-apis/bot/v2/hook/",
        apiKey: String = "TOKEN",
        extra: Map<String, String> = emptyMap()
    ) = ConnectorDef(id = "feishu", name = "飞书", type = "messaging", endpoint = endpoint, apiKey = apiKey, extra = extra)

    private fun qqDef(extra: Map<String, String>) = ConnectorDef(
        id = "qq", name = "QQ", type = "messaging",
        endpoint = ConnectorMessenger.QQ_API_BASE, apiKey = "SECRET", extra = extra
    )

    // ── 微信（企业微信群机器人）────────────────────────────

    @Test
    fun `wechat webhook appends key and sends text`() = runTest {
        val (m, http) = messenger(ok())
        val result = m.send(wechatDef(), "hello")
        assertTrue(result is ConnectorMessenger.SendResult.Success)
        assertTrue(http.urls[0].endsWith("?key=KEY"))
        assertTrue(http.bodies[0].contains("\"msgtype\":\"text\""))
        assertTrue(http.bodies[0].contains("\"content\":\"hello\""))
    }

    @Test
    fun `wechat markdown switches msgtype`() = runTest {
        val (m, http) = messenger(ok())
        m.send(wechatDef(), "# title", markdown = true)
        assertTrue(http.bodies[0].contains("\"msgtype\":\"markdown\""))
        assertTrue(http.bodies[0].contains("\"markdown\""))
    }

    @Test
    fun `wechat non zero errcode is failure`() = runTest {
        val (m, _) = messenger(ok("""{"errcode":93000}"""))
        val result = m.send(wechatDef(), "hello")
        assertTrue(result is ConnectorMessenger.SendResult.Failure)
        assertTrue((result as ConnectorMessenger.SendResult.Failure).reason.contains("93000"))
    }

    @Test
    fun `wechat clawbot mode posts to OpenClaw gateway`() = runTest {
        val (m, http) = messenger(MessengerHttpReply(202, "ok"))
        val def = wechatDef(
            endpoint = "http://192.168.1.10:18789",
            apiKey = "GW_TOKEN",
            extra = mapOf("mode" to "clawbot")
        )
        val result = m.send(def, "hi")
        assertTrue(result is ConnectorMessenger.SendResult.Success)
        assertEquals("http://192.168.1.10:18789/hooks/agent", http.urls[0])
        assertTrue(http.bodies[0].contains("\"message\":\"hi\""))
        assertTrue(http.bodies[0].contains("\"channel\":\"openclaw-weixin\""))
        assertEquals("Bearer GW_TOKEN", http.headers[0]["Authorization"])
    }

    // ── 飞书 ──────────────────────────────────────────────

    @Test
    fun `feishu webhook without secret has no sign`() = runTest {
        val (m, http) = messenger(ok("""{"code":0}"""))
        val result = m.send(feishuDef(), "hi")
        assertTrue(result is ConnectorMessenger.SendResult.Success)
        assertEquals("https://open.feishu.cn/open-apis/bot/v2/hook/TOKEN", http.urls[0])
        assertTrue(http.bodies[0].contains("\"msg_type\":\"text\""))
        assertTrue(!http.bodies[0].contains("\"sign\""))
    }

    @Test
    fun `feishu webhook signs with timestamp and secret`() = runTest {
        val (m, http) = messenger(ok("""{"code":0}"""))
        val def = feishuDef(extra = mapOf("sign_secret" to "s3cret"))
        m.send(def, "hi")

        val body = http.bodies[0]
        assertTrue(body.contains("\"timestamp\""))
        assertTrue(body.contains("\"sign\""))

        // 复算官方算法：HmacSHA256(key = "{timestamp}\n{secret}", data = "") → Base64
        val timestamp = Regex("\"timestamp\"\\s*:\\s*\"(\\d+)\"").find(body)!!.groupValues[1]
        val sign = Regex("\"sign\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("$timestamp\ns3cret".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        assertEquals(Base64.getEncoder().encodeToString(mac.doFinal(ByteArray(0))), sign)
    }

    @Test
    fun `feishu app mode exchanges token then sends message`() = runTest {
        val (m, http) = messenger(
            ok("""{"tenant_access_token":"t-123","expire":7200}"""),
            ok("""{"code":0,"msg":"ok"}""")
        )
        val def = feishuDef(
            apiKey = "APP_SECRET",
            extra = mapOf("mode" to "app", "app_id" to "cli_xxx", "target" to "oc_group")
        )
        val result = m.send(def, "hi")
        assertTrue(result is ConnectorMessenger.SendResult.Success)

        assertEquals(
            "${ConnectorMessenger.FEISHU_BASE}/open-apis/auth/v3/tenant_access_token/internal",
            http.urls[0]
        )
        assertEquals(
            "${ConnectorMessenger.FEISHU_BASE}/open-apis/im/v1/messages?receive_id_type=chat_id",
            http.urls[1]
        )
        assertEquals("Bearer t-123", http.headers[1]["Authorization"])
        // content 必须是 JSON 字符串（不是嵌套对象）——官方最易踩坑点
        assertTrue(http.bodies[1].contains("\\\"text\\\":\\\"hi\\\""))
    }

    @Test
    fun `feishu app without target fails with hint`() = runTest {
        val (m, _) = messenger(ok("""{"tenant_access_token":"t","expire":7200}"""))
        val def = feishuDef(
            apiKey = "APP_SECRET",
            extra = mapOf("mode" to "app", "app_id" to "cli_xxx")
        )
        val result = m.send(def, "hi")
        assertTrue(result is ConnectorMessenger.SendResult.Failure)
        assertTrue((result as ConnectorMessenger.SendResult.Failure).reason.contains("extra.target"))
    }

    // ── QQ ────────────────────────────────────────────────

    @Test
    fun `qq gets app access token then sends group message`() = runTest {
        val (m, http) = messenger(
            ok("""{"access_token":"QQ_TOKEN","expires_in":"7200"}"""),
            ok("""{"id":"msg-1"}""")
        )
        val def = qqDef(mapOf("app_id" to "1020xxxx", "target" to "group:ABCD"))
        val result = m.send(def, "hi")
        assertTrue(result is ConnectorMessenger.SendResult.Success)

        assertEquals(ConnectorMessenger.QQ_TOKEN_URL, http.urls[0])
        assertTrue(http.bodies[0].contains("\"appId\":\"1020xxxx\""))
        assertEquals("${ConnectorMessenger.QQ_API_BASE}/v2/groups/ABCD/messages", http.urls[1])
        assertEquals("QQBot QQ_TOKEN", http.headers[1]["Authorization"])
        assertTrue(http.bodies[1].contains("\"msg_type\":0"))
    }

    @Test
    fun `qq user target uses c2c endpoint`() = runTest {
        val (m, http) = messenger(
            ok("""{"access_token":"T","expires_in":"7200"}"""),
            ok("""{"id":"m"}""")
        )
        m.send(qqDef(mapOf("app_id" to "1", "target" to "user:OPENID")), "hi")
        assertEquals("${ConnectorMessenger.QQ_API_BASE}/v2/users/OPENID/messages", http.urls[1])
    }

    @Test
    fun `qq channel target uses channel endpoint`() = runTest {
        val (m, http) = messenger(
            ok("""{"access_token":"T","expires_in":"7200"}"""),
            ok("""{"id":"m"}""")
        )
        m.send(qqDef(mapOf("appId" to "1", "target" to "channel:12345")), "hi", markdown = true)
        assertEquals("${ConnectorMessenger.QQ_API_BASE}/channels/12345/messages", http.urls[1])
        assertTrue(http.bodies[1].contains("\"msg_type\":2"))
    }

    @Test
    fun `qq without target fails with hint`() = runTest {
        val (m, _) = messenger(ok("""{"access_token":"T","expires_in":"7200"}"""))
        val result = m.send(qqDef(mapOf("app_id" to "1")), "hi")
        assertTrue(result is ConnectorMessenger.SendResult.Failure)
        assertTrue((result as ConnectorMessenger.SendResult.Failure).reason.contains("extra.target"))
    }

    // ── 配置校验（不发请求）───────────────────────────────

    @Test
    fun `verify reports every missing qq credential`() = runTest {
        val (m, http) = messenger(ok())
        val result = m.verifyConfig(
            ConnectorDef(id = "qq", name = "QQ", type = "messaging", endpoint = ConnectorMessenger.QQ_API_BASE)
        )
        assertTrue(result is ConnectorMessenger.SendResult.Failure)
        val reason = (result as ConnectorMessenger.SendResult.Failure).reason
        assertTrue(reason.contains("app_id"))
        assertTrue(reason.contains("clientSecret"))
        assertTrue(reason.contains("target"))
        assertTrue(http.urls.isEmpty()) // verify 绝不发请求
    }

    @Test
    fun `verify accepts complete clawbot config`() = runTest {
        val (m, _) = messenger(ok())
        val result = m.verifyConfig(
            wechatDef(endpoint = "http://host:18789", apiKey = null, extra = mapOf("mode" to "clawbot"))
        )
        assertTrue(result is ConnectorMessenger.SendResult.Success)
    }
}
