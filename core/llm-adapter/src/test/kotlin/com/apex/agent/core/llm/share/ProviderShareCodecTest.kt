package com.apex.agent.core.llm.share

import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProviderShareCodec] 单元测试（Task 4-f）。
 *
 * 仓库测试纪律：JUnit4、无 mock 框架、断言值先读源码核实。
 *
 * 覆盖矩阵（对标 RikkaHub ShareSheetTest 的 QR 往返 + apex 增强）：
 * - 小载荷往返（明文路径，信封 compressed=false）+ 字段采样；
 * - 大载荷往返（> 512 字节 → gzip 路径，compressed=true）；
 * - 密钥红线：默认脱敏（apiKeys 空 + includesKeys=false + URI 文本无 key 材料）；
 *   includeKeys=true 全量保留 + 标记位；
 * - 前缀 / 空白容忍：缺前缀 Malformed、首尾空白与 Base64 段内换行容忍、
 *   RikkaHub 前缀不误吞；
 * - 防御式解码：乱码 base64 → Malformed、截断 gzip → CorruptPayload、
 *   伪 gzip 魔数 → CorruptPayload、坏 schema → WrongSchema、
 *   载荷/信封版本 99 → UnsupportedVersion、信封 format 陌生 → Malformed；
 * - decodeOrNull 折叠 null；错误 message 人读可展示；
 * - estimateQrVersion 边界（126 / 285 / 550 / 976 / 1852）。
 *
 * 白盒约定：envelopeOf 复刻 decode 的外两层（去前缀 → Base64 → 信封 JSON），
 * 用于断言压缩路径选择；payloadUri 手工造明文信封 URI，用于构造
 * 坏 schema / 坏版本等被测场景（不走被测 encode——它只会产出合法载荷）。
 */
class ProviderShareCodecTest {

    /** 测试侧独立 Json（encodeDefaults=true——手工造的坏载荷全字段显式落盘）。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ── 构造工具 ──────────────────────────────────────────────

    private fun provider(
        id: String = "prov-a",
        keys: List<String> = listOf("sk-secret-1", "sk-secret-2"),
    ) = ProviderConfig(
        id = id,
        displayName = "Provider A",
        baseUrl = "https://api.example.com/v1",
        apiKeys = keys,
        defaultHeaders = mapOf("X-Region" to "cn-east-1"),
    )

    private fun profile(
        id: String = "pf-1",
        providerId: String = "prov-a",
        modelId: String = "gpt-x-mini",
        systemPromptPrefix: String = "",
    ) = ModelProfile(
        id = id,
        name = "Profile $id",
        providerId = providerId,
        modelId = modelId,
        temperature = 0.3f,
        systemPromptPrefix = systemPromptPrefix,
        customHeaders = mapOf("X-Profile-Tag" to "edge"),
    )

    /** 白盒：解开 URI 信封层（复刻 decode 外两层，不经被测代码）。 */
    private fun envelopeOf(uri: String): ShareEnvelope {
        val body = uri.trim()
            .removePrefix(ProviderShareCodec.URI_PREFIX)
            .filterNot { it.isWhitespace() }
        val envelopeJson = String(Base64.getDecoder().decode(body))
        return json.decodeFromString(ShareEnvelope.serializer(), envelopeJson)
    }

    /** 手工把信封包成 URI（坏信封场景构造用）。 */
    private fun uriOf(envelope: ShareEnvelope): String =
        ProviderShareCodec.URI_PREFIX + Base64.getEncoder().encodeToString(
            json.encodeToString(ShareEnvelope.serializer(), envelope).encodeToByteArray()
        )

    /** 手工造明文（不压缩）信封 URI——装载任意（含非法）载荷。 */
    private fun payloadUri(payload: ProviderSharePayload): String {
        val payloadBytes = json
            .encodeToString(ProviderSharePayload.serializer(), payload)
            .encodeToByteArray()
        return uriOf(
            ShareEnvelope(
                format = ShareEnvelope.FORMAT,
                version = ShareEnvelope.CURRENT_VERSION,
                compressed = false,
                data = Base64.getEncoder().encodeToString(payloadBytes),
            )
        )
    }

    private fun errorOf(uri: String): ShareCodecError? =
        ProviderShareCodec.decode(uri).shareCodecErrorOrNull()

    // ═════════════ 往返（明文路径） ═════════════

    @Test
    fun `small payload roundtrips uncompressed and samples fields`() {
        val prov = provider()
        val pf = profile()
        val nowMs = 1_719_000_000_123L
        val uri = ProviderShareCodec.encode(prov, listOf(pf), includeKeys = true, nowMs = nowMs)

        assertTrue(uri.startsWith(ProviderShareCodec.URI_PREFIX))
        assertTrue(ProviderShareCodec.isShareUri(uri))
        // 小载荷（< 512 字节）走明文路径：信封 compressed=false
        assertFalse("small payload must stay uncompressed", envelopeOf(uri).compressed)

        val decoded = ProviderShareCodec.decode(uri).getOrThrow()
        assertEquals("prov-a", decoded.provider.id)
        assertEquals("Provider A", decoded.provider.displayName)
        assertEquals("https://api.example.com/v1", decoded.provider.baseUrl)
        assertEquals(listOf("sk-secret-1", "sk-secret-2"), decoded.provider.apiKeys)
        assertEquals(mapOf("X-Region" to "cn-east-1"), decoded.provider.defaultHeaders)
        assertEquals(1, decoded.profiles.size)
        val decodedProfile = decoded.profiles.single()
        assertEquals("pf-1", decodedProfile.id)
        assertEquals("gpt-x-mini", decodedProfile.modelId)
        assertEquals(0.3f, decodedProfile.temperature, 0.0f)
        assertEquals(mapOf("X-Profile-Tag" to "edge"), decodedProfile.customHeaders)
        assertEquals(nowMs, decoded.exportedAt)
        assertTrue(decoded.includesKeys)
        assertTrue(decoded.hasProfiles)
        assertEquals(ProviderSharePayload.SCHEMA, decoded.schema)
        assertEquals(1, decoded.version)
    }

    @Test
    fun `provider only payload roundtrips without profiles`() {
        val uri = ProviderShareCodec.encode(provider())
        val decoded = ProviderShareCodec.decode(uri).getOrThrow()
        assertTrue(decoded.profiles.isEmpty())
        assertFalse(decoded.hasProfiles)
        assertFalse(decoded.includesKeys)
        assertEquals(0, decoded.exportedAt)
    }

    // ═════════════ 往返（gzip 路径） ═════════════

    @Test
    fun `large payload roundtrips through gzip compression`() {
        val longPrefix = "y".repeat(700)
        val profiles = (1..3).map { idx ->
            profile(id = "pf-$idx", modelId = "model-$idx", systemPromptPrefix = longPrefix)
        }
        val uri = ProviderShareCodec.encode(provider(), profiles, includeKeys = true)

        // 载荷 JSON 远超 512 字节 → 信封 compressed=true（gzip 路径）
        val envelope = envelopeOf(uri)
        assertTrue("large payload must be gzipped", envelope.compressed)

        val decoded = ProviderShareCodec.decode(uri).getOrThrow()
        assertEquals(3, decoded.profiles.size)
        assertEquals(listOf("pf-1", "pf-2", "pf-3"), decoded.profiles.map { it.id })
        decoded.profiles.forEach { decodedProfile ->
            assertEquals(longPrefix, decodedProfile.systemPromptPrefix)
        }
        assertEquals("model-2", decoded.profiles[1].modelId)
        assertEquals(listOf("sk-secret-1", "sk-secret-2"), decoded.provider.apiKeys)
    }

    // ═════════════ 密钥红线 ═════════════

    @Test
    fun `keys are redacted by default and never leak into uri text`() {
        val uri = ProviderShareCodec.encode(provider()) // includeKeys 默认 false

        // 明文 key 绝不出现在 URI 文本里（Base64 串里不可能碰巧出现完整 key 子串）
        assertFalse(uri.contains("sk-secret-1"))
        assertFalse(uri.contains("sk-secret-2"))

        val decoded = ProviderShareCodec.decode(uri).getOrThrow()
        assertTrue(decoded.provider.apiKeys.isEmpty())
        assertFalse(decoded.includesKeys)
    }

    @Test
    fun `keys are preserved when explicitly included`() {
        val uri = ProviderShareCodec.encode(provider(), includeKeys = true)
        val decoded = ProviderShareCodec.decode(uri).getOrThrow()
        assertEquals(listOf("sk-secret-1", "sk-secret-2"), decoded.provider.apiKeys)
        assertTrue(decoded.includesKeys)
    }

    @Test
    fun `non key headers survive key redaction`() {
        // 刻意取舍（encode KDoc）：脱敏只清 apiKeys，请求头保留
        val uri = ProviderShareCodec.encode(provider(), listOf(profile()))
        val decoded = ProviderShareCodec.decode(uri).getOrThrow()
        assertEquals(mapOf("X-Region" to "cn-east-1"), decoded.provider.defaultHeaders)
        assertEquals(mapOf("X-Profile-Tag" to "edge"), decoded.profiles.single().customHeaders)
        assertTrue(decoded.provider.apiKeys.isEmpty())
    }

    // ═════════════ 前缀与噪音容忍 ═════════════

    @Test
    fun `missing prefix is malformed`() {
        val error = errorOf("hello world, not a share uri")
        assertTrue("expected Malformed but got $error", error is ShareCodecError.Malformed)
        assertTrue(error!!.message.contains("missing prefix"))
    }

    @Test
    fun `rikkahub prefix is not an apex share uri`() {
        // 跨应用前缀刻意不同——RikkaHub 的码不会被 apex codec 吞掉
        val rikkahubStyle = "ai-provider:v1:" + Base64.getEncoder()
            .encodeToString("{\"apiKey\":\"x\"}".encodeToByteArray())
        assertFalse(ProviderShareCodec.isShareUri(rikkahubStyle))
        assertTrue(errorOf(rikkahubStyle) is ShareCodecError.Malformed)
    }

    @Test
    fun `outer whitespace and embedded newlines are tolerated`() {
        val uri = ProviderShareCodec.encode(provider(), listOf(profile()))

        // 首尾空白（剪贴板 / 分享文本常见）
        assertTrue(ProviderShareCodec.decode("   $uri \n\t").isSuccess)

        // Base64 段中间夹换行（QR 文本导出常见）——解码前白字符剔除
        val body = uri.removePrefix(ProviderShareCodec.URI_PREFIX)
        val withBreak = ProviderShareCodec.URI_PREFIX +
            body.substring(0, 40) + "\n" + body.substring(40)
        assertTrue(ProviderShareCodec.decode(withBreak).isSuccess)
        assertEquals("prov-a", ProviderShareCodec.decode(withBreak).getOrThrow().provider.id)
    }

    // ═════════════ 防御式解码（绝不 crash） ═════════════

    @Test
    fun `garbage input folds to malformed without crash`() {
        val garbage = listOf(
            "",
            "   ",
            ProviderShareCodec.URI_PREFIX,
            ProviderShareCodec.URI_PREFIX + "   ",
            ProviderShareCodec.URI_PREFIX + "!!!not-valid-base64!!!",
            ProviderShareCodec.URI_PREFIX + "也许也许",
        )
        garbage.forEach { input ->
            val result = ProviderShareCodec.decode(input)
            assertTrue("decode must fold, not crash: \"$input\"", result.isFailure)
            val error = result.shareCodecErrorOrNull()
            assertTrue(
                "expected Malformed for \"$input\" but got $error",
                error is ShareCodecError.Malformed
            )
        }
    }

    @Test
    fun `truncated compressed stream is corrupt payload`() {
        val uri = ProviderShareCodec.encode(
            provider(),
            listOf(profile(systemPromptPrefix = "z".repeat(900)))
        )
        val envelope = envelopeOf(uri)
        assertTrue(envelope.compressed)

        // 截掉尾部 8 字符（8 是 4 的倍数——Base64 仍合法，但 gzip 流被截断）
        val truncated = uriOf(envelope.copy(data = envelope.data.dropLast(8)))
        val error = errorOf(truncated)
        assertTrue("expected CorruptPayload but got $error", error is ShareCodecError.CorruptPayload)
    }

    @Test
    fun `non gzip data marked compressed is corrupt payload`() {
        val notGzip = Base64.getEncoder()
            .encodeToString("plain text pretending to be gzip".encodeToByteArray())
        val envelope = ShareEnvelope(
            format = ShareEnvelope.FORMAT,
            version = ShareEnvelope.CURRENT_VERSION,
            compressed = true,
            data = notGzip,
        )
        val error = errorOf(uriOf(envelope))
        assertTrue("expected CorruptPayload but got $error", error is ShareCodecError.CorruptPayload)
    }

    @Test
    fun `payload with wrong schema is rejected`() {
        val payload = ProviderSharePayload(provider = provider(), schema = "ai-provider")
        val error = errorOf(payloadUri(payload))
        assertTrue("expected WrongSchema but got $error", error is ShareCodecError.WrongSchema)
        assertEquals("ai-provider", (error as ShareCodecError.WrongSchema).got)
    }

    @Test
    fun `payload version from the future is unsupported`() {
        val payload = ProviderSharePayload(provider = provider(), version = 99)
        val error = errorOf(payloadUri(payload))
        assertTrue("expected UnsupportedVersion but got $error", error is ShareCodecError.UnsupportedVersion)
        assertEquals(99, (error as ShareCodecError.UnsupportedVersion).version)
    }

    @Test
    fun `envelope version from the future is unsupported`() {
        val payloadBytes = json
            .encodeToString(ProviderSharePayload.serializer(), ProviderSharePayload(provider = provider()))
            .encodeToByteArray()
        val envelope = ShareEnvelope(
            format = ShareEnvelope.FORMAT,
            version = 99,
            compressed = false,
            data = Base64.getEncoder().encodeToString(payloadBytes),
        )
        val error = errorOf(uriOf(envelope))
        assertTrue("expected UnsupportedVersion but got $error", error is ShareCodecError.UnsupportedVersion)
        assertEquals(99, (error as ShareCodecError.UnsupportedVersion).version)
    }

    @Test
    fun `unknown envelope format is malformed`() {
        val payloadBytes = json
            .encodeToString(ProviderSharePayload.serializer(), ProviderSharePayload(provider = provider()))
            .encodeToByteArray()
        val envelope = ShareEnvelope(
            format = "rikkahub",
            version = ShareEnvelope.CURRENT_VERSION,
            compressed = false,
            data = Base64.getEncoder().encodeToString(payloadBytes),
        )
        val error = errorOf(uriOf(envelope))
        assertTrue("expected Malformed but got $error", error is ShareCodecError.Malformed)
    }

    // ═════════════ decodeOrNull / 错误消息质量 ═════════════

    @Test
    fun `decodeOrNull folds failures to null`() {
        assertNull(ProviderShareCodec.decodeOrNull("total garbage"))
        assertNull(ProviderShareCodec.decodeOrNull(ProviderShareCodec.URI_PREFIX + "???"))
        val valid = ProviderShareCodec.encode(provider())
        assertNotNull(ProviderShareCodec.decodeOrNull(valid))
        assertEquals("prov-a", ProviderShareCodec.decodeOrNull(valid)!!.provider.id)
    }

    @Test
    fun `error messages are human readable`() {
        val samples = listOf(
            ShareCodecError.Malformed("missing prefix"),
            ShareCodecError.UnsupportedVersion(99),
            ShareCodecError.WrongSchema("ai-provider"),
            ShareCodecError.CorruptPayload("gzip truncated"),
        )
        samples.forEach { error ->
            assertTrue("message must not be blank: $error", error.message.isNotBlank())
        }
        // message 携带原因 / 期望值，UI 可原样展示
        assertTrue(ShareCodecError.Malformed("missing prefix").message.contains("missing prefix"))
        assertTrue(ShareCodecError.WrongSchema("ai-provider").message.contains("apex-provider"))
        assertTrue(ShareCodecError.UnsupportedVersion(99).message.contains("99"))
    }

    // ═════════════ QR 版本估算边界 ═════════════

    @Test
    fun `estimate qr version at boundaries`() {
        // 表：<=126→v7，<=285→v14，<=550→v20，<=976→v27，<=1852→v40，超出→-1
        assertEquals(7, ProviderShareCodec.estimateQrVersion("x".repeat(126)))
        assertEquals(14, ProviderShareCodec.estimateQrVersion("x".repeat(127)))
        assertEquals(14, ProviderShareCodec.estimateQrVersion("x".repeat(285)))
        assertEquals(20, ProviderShareCodec.estimateQrVersion("x".repeat(286)))
        assertEquals(20, ProviderShareCodec.estimateQrVersion("x".repeat(550)))
        assertEquals(27, ProviderShareCodec.estimateQrVersion("x".repeat(551)))
        assertEquals(27, ProviderShareCodec.estimateQrVersion("x".repeat(976)))
        assertEquals(40, ProviderShareCodec.estimateQrVersion("x".repeat(977)))
        assertEquals(40, ProviderShareCodec.estimateQrVersion("x".repeat(1852)))
        assertEquals(-1, ProviderShareCodec.estimateQrVersion("x".repeat(1853)))
    }

    @Test
    fun `encoded share uri stays within scannable qr budget`() {
        // 常规真实载荷（Provider + 3 档位，无超长前缀）压缩后应在 v20 档以内
        val uri = ProviderShareCodec.encode(
            provider(),
            (1..3).map { idx -> profile(id = "pf-$idx", modelId = "model-$idx") }
        )
        val version = ProviderShareCodec.estimateQrVersion(uri)
        assertTrue("expected a valid QR version (<= 40) but got $version", version in 1..40)
    }
}
