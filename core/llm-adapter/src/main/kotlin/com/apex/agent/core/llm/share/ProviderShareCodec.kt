package com.apex.agent.core.llm.share

import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json

/**
 * # Provider Share Codec（Task 4-f）
 *
 * 把 [ProviderConfig] + [ModelProfile] 档位列表编码成单行分享 URI
 * （贴进二维码 / 系统分享纯文本通用），以及反向解码还原。
 *
 * 学习自 RikkaHub 的 `ProviderSetting.encodeForShare` +
 * `decodeProviderSetting`（worklog 2-b §9），apex 侧四点适配：
 *
 * 1. **载荷更大**：RikkaHub 剥离 models 只导连接配置；apex 分享单元是
 *    Provider + Profile 档位，载荷可达数 KB——超过 [COMPRESS_THRESHOLD_BYTES]
 *    自动 GZIP（学习 operit 无、rikkahub 无，纯 apex 增强）；
 * 2. **信封层**：「是否压缩」标志必须带外传输（不可能写进被压缩的 JSON），
 *    引入 [ShareEnvelope] 承载 format / version / compressed / data；
 * 3. **密钥红线**：默认导出脱敏（apiKeys 清空 + includesKeys=false），
 *    includeKeys=true 才带明文 key（用户显式确认后才可）；
 * 4. **防御式解码**：RikkaHub 解码失败 require 直接抛；apex 全程折叠
 *    [ShareCodecError] 分型错误（Result failure 装 [ShareDecodeException] 桥），
 *    任何形状的坏输入都不向上抛、不 crash。
 *
 * ## URI 分层（自外向内——想清楚再动）
 *
 * ```
 * URI      = "apex-provider:v1:" + B64(信封 JSON)        外层 Base64
 * 信封      = { format, version, compressed, data }       恒小（~百字节），永不压缩
 * data     = B64(gzip(载荷 JSON)) 或 B64(载荷 JSON)       中层 Base64
 * 载荷      = ProviderSharePayload JSON                   Provider + Profiles，唯一可能大的层
 * ```
 *
 * 要点：**压缩只发生在载荷层**。信封 JSON 本身恒小（四个短字段 + Base64
 * 数据串），不需要也不应该压缩；URI 前缀后的 Base64 装的是信封 JSON 的
 * 字节（不是压缩字节）。解码按相反顺序剥洋葱：trim → 去前缀 → 外层
 * Base64 → 信封 JSON → 按 compressed 决定 gunzip 与否 → 内层 Base64 →
 * 载荷 JSON。
 *
 * 三处版本号各司其职：URI 前缀的 v1（scheme 语义，只增不改）、信封
 * [ShareEnvelope.version]（包装格式）、载荷 [ProviderSharePayload.version]
 * （内容格式）。当前均为 1；解码时信封版本与载荷版本都严格校验（不认识
 * 就 [ShareCodecError.UnsupportedVersion]，绝不瞎猜旧格式）。
 *
 * ## Base64 字母表
 *
 * 标准 RFC 4648 字母表 + 填充（非 URL_SAFE）：对齐 RikkaHub 的 Base64
 * 默认字母表；分享码是纯文本载荷，对 QR / 剪贴板 / 系统分享无害。解码端
 * 只认标准字母表（严格拒收 URL_SAFE 的 - 和 _）——可预测性优先于宽容度。
 * 信封 Base64 段内的白字符（QR 文本拷贝常夹带的换行 / 空格）在解码前剔除。
 *
 * ## 线程安全
 *
 * object 单例、无状态纯函数（[json] 与 Base64 codec 实例均线程安全），
 * 可在任意线程直接调用。
 */
object ProviderShareCodec {

    /** 分享 URI 前缀（对齐 RikkaHub "ai-provider:v1:" 命名法，apex 自家前缀）。 */
    const val URI_PREFIX = "apex-provider:v1:"

    /**
     * GZIP 启用阈值：载荷 JSON **原始字节数**严格大于该值才压缩。
     *
     * 512 的取值：小于此的 JSON（单 Provider + 少量档位）压缩反而变大
     * （gzip 头 10 字节 + trailer 8 字节的固定开销），且小载荷本就进得了
     * 低版本 QR；大于此基本确定在往大 QR 走，压缩收益显著。
     */
    const val COMPRESS_THRESHOLD_BYTES = 512

    /**
     * 解压上限（防 gzip 炸弹）：解出的载荷超过 2 MiB 视为损坏
     * （[ShareCodecError.CorruptPayload]）。正常 Provider + Profiles 载荷
     * 远小于此；分享码不该携带 2 MiB 级内容。
     */
    private const val MAX_DECOMPRESSED_BYTES = 2 * 1024 * 1024

    // ── QR 版本估算表（纯启发式，见 estimateQrVersion）──────────────
    private const val QR_LEN_FOR_V7 = 126
    private const val QR_LEN_FOR_V14 = 285
    private const val QR_LEN_FOR_V20 = 550
    private const val QR_LEN_FOR_V27 = 976
    private const val QR_LEN_FOR_V40 = 1852

    /** 紧凑 Json：encodeDefaults=false（默认值字段不进载荷），解码忽略未知字段（向前兼容）。 */
    private val json = Json { ignoreUnknownKeys = true }

    private val b64Encoder: Base64.Encoder = Base64.getEncoder()
    private val b64Decoder: Base64.Decoder = Base64.getDecoder()

    // ═════════════════════════════ 编码 ═════════════════════════════

    /**
     * 编码为分享 URI（前缀 + 外层 Base64(信封)）。
     *
     * 密钥处理（红线规则，详见类 KDoc 第 3 点）：
     *
     * - [includeKeys] == false（默认）：[ProviderConfig.apiKeys] 置空、
     *   [ProviderSharePayload.includesKeys] = false；
     *   [ProviderConfig.defaultHeaders] 与各档位 [ModelProfile.customHeaders]
     *   **保留**——刻意取舍（简单 + 文档化）：请求头绝大多数是路由信息
     *   （X-Region / anthropic-version 等），无密钥语义；真正的密钥材料只认
     *   apiKeys 列表。需要连头一起彻底脱敏的调用方，自行先清头再传入。
     * - [includeKeys] == true：全量保留，includesKeys = true——导入端必须
     *   提示「此码含密钥，请只分享给信任的人」。
     *
     * 压缩决策：载荷 JSON 原始字节数 > [COMPRESS_THRESHOLD_BYTES] 时
     * gzip（信封 compressed=true），否则明文（compressed=false）。
     * 信封 JSON 恒小，永不压缩（分层见类 KDoc）。
     *
     * @param provider 被分享的 Provider 配置（apiKeys 按上述规则脱敏）
     * @param profiles 随行模型档位（原样保留——档位是分享价值主体）
     * @param includeKeys false（默认）剥离 apiKeys；true 保留明文 key
     * @param nowMs 导出时刻（写入 exportedAt；默认 0 = 不记录）
     */
    fun encode(
        provider: ProviderConfig,
        profiles: List<ModelProfile> = emptyList(),
        includeKeys: Boolean = false,
        nowMs: Long = 0,
    ): String {
        val safeProvider =
            if (includeKeys) provider else provider.copy(apiKeys = emptyList())
        val payload = ProviderSharePayload(
            provider = safeProvider,
            profiles = profiles,
            exportedAt = nowMs,
            includesKeys = includeKeys,
        )
        val payloadBytes = json
            .encodeToString(ProviderSharePayload.serializer(), payload)
            .encodeToByteArray()
        val compressed = payloadBytes.size > COMPRESS_THRESHOLD_BYTES
        val data =
            if (compressed) gzip(payloadBytes).toBase64String()
            else payloadBytes.toBase64String()
        val envelope = ShareEnvelope(
            format = ShareEnvelope.FORMAT,
            version = ShareEnvelope.CURRENT_VERSION,
            compressed = compressed,
            data = data,
        )
        val envelopeJson = json.encodeToString(ShareEnvelope.serializer(), envelope)
        return URI_PREFIX + envelopeJson.encodeToByteArray().toBase64String()
    }

    // ═════════════════════════════ 解码 ═════════════════════════════

    /**
     * 解码分享 URI——全程防御式，任何形状的坏输入都折叠为
     * [ShareDecodeException]（桥接 [ShareCodecError]），绝不向上抛。
     * 纯同步函数、无挂起点，不存在协程取消透传问题。
     *
     * 失败模式 → 错误分型（自外向内逐层判）：
     *
     * | 输入症状                                     | 错误                  |
     * |----------------------------------------------|-----------------------|
     * | 空串 / 缺前缀 / 前缀后无内容                  | Malformed            |
     * | 信封 Base64 非法（乱码字符）                   | Malformed            |
     * | 信封 JSON 解不开 / format 不认识               | Malformed            |
     * | 信封 version 不等于当前版                      | UnsupportedVersion   |
     * | data Base64 非法 / gzip 流损坏 / 解压超限      | CorruptPayload       |
     * | 载荷 JSON 解不开                               | CorruptPayload       |
     * | 载荷 schema 不是 apex-provider                 | WrongSchema           |
     * | 载荷 version 不等于当前版                      | UnsupportedVersion   |
     *
     * 容忍度（QR / 剪贴板现实噪音）：首尾空白 trim；前缀之后 Base64 段内的
     * 白字符（换行 / 空格 / 制表符）全部剔除。除此之外严格——URL_SAFE
     * 字母表、异构 App 的码（RikkaHub 的 "ai-provider:v1:" 前缀）一律拒收。
     */
    fun decode(uri: String): Result<ProviderSharePayload> {
        val trimmed = uri.trim()
        if (!trimmed.startsWith(URI_PREFIX)) {
            return malformed("missing prefix \"$URI_PREFIX\" (input starts with \"${trimmed.take(24)}\")")
        }
        // QR 文本拷贝常在 Base64 段里夹带换行——白字符剔除（Base64 字母表不含白字符，剔除无损）
        val envelopeB64 = trimmed.removePrefix(URI_PREFIX).filterNot { it.isWhitespace() }
        if (envelopeB64.isEmpty()) {
            return malformed("empty body after prefix")
        }
        val envelopeBytes = runCatching { b64Decoder.decode(envelopeB64) }
            .getOrElse { return malformed("envelope base64 invalid (${describe(it)})") }
        val envelope = runCatching {
            json.decodeFromString(ShareEnvelope.serializer(), envelopeBytes.decodeToString())
        }.getOrElse { return malformed("envelope JSON invalid (${describe(it)})") }
        if (envelope.format != ShareEnvelope.FORMAT) {
            return malformed("unknown envelope format \"${envelope.format.take(32)}\"")
        }
        if (envelope.version != ShareEnvelope.CURRENT_VERSION) {
            return failure(ShareCodecError.UnsupportedVersion(envelope.version))
        }
        val payloadBytes = runCatching {
            val raw = b64Decoder.decode(envelope.data.filterNot { it.isWhitespace() })
            if (envelope.compressed) gunzip(raw) else raw
        }.getOrElse { return corrupt("payload data invalid (${describe(it)})") }
        val payload = runCatching {
            json.decodeFromString(ProviderSharePayload.serializer(), payloadBytes.decodeToString())
        }.getOrElse { return corrupt("payload JSON invalid (${describe(it)})") }
        if (payload.schema != ProviderSharePayload.SCHEMA) {
            return failure(ShareCodecError.WrongSchema(payload.schema))
        }
        if (payload.version != ProviderSharePayload.CURRENT_VERSION) {
            return failure(ShareCodecError.UnsupportedVersion(payload.version))
        }
        return Result.success(payload)
    }

    /**
     * 解码（失败折叠为 null）——快速路径：UI 预检 / 批量过滤场景用
     * [decode] + [shareCodecErrorOrNull] 拿分型错误，这里只关心成不成。
     */
    fun decodeOrNull(uri: String): ProviderSharePayload? = decode(uri).getOrNull()

    /**
     * 是否 apex 分享 URI（容忍首尾空白）。
     *
     * 注意：RikkaHub 的 "ai-provider:v1:" 前缀**不是** apex 分享码——跨应用
     * 前缀刻意不同，误扫别家的码不会被本 codec 吞掉（decode 也会 Malformed）。
     */
    fun isShareUri(uri: String): Boolean = uri.trim().startsWith(URI_PREFIX)

    // ═════════════════════════ QR 尺寸提示 ═════════════════════════

    /**
     * QR 版本粗估（**纯启发式**，仅供 UI 提示，不参与编解码决策）。
     *
     * 表（URI 字符数 → QR 版本）：<=126 → v7；<=285 → v14；<=550 → v20；
     * <=976 → v27；<=1852 → v40；超出 → -1（过大，建议改走文本 / 文件分享）。
     * 数字按高纠错档的字节容量再加保守余量拍定——真实容量随 ECC 等级 /
     * 编码模式 / 分段结构变化，拿不准就估大不估小。按**原样** URI 长度计
     * （含首尾空白——QR 原样编码它们）。
     */
    fun estimateQrVersion(uri: String): Int {
        val length = uri.length
        return when {
            length <= QR_LEN_FOR_V7 -> 7
            length <= QR_LEN_FOR_V14 -> 14
            length <= QR_LEN_FOR_V20 -> 20
            length <= QR_LEN_FOR_V27 -> 27
            length <= QR_LEN_FOR_V40 -> 40
            else -> -1
        }
    }

    // ═════════════════════════ 内部管道 ═════════════════════════

    /** GZIP 压缩（java.util.zip，含 10 字节头 + 8 字节 trailer 的固定开销）。 */
    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(512)
        GZIPOutputStream(out).use { output -> output.write(bytes) }
        return out.toByteArray()
    }

    /**
     * GZIP 解压——带 [MAX_DECOMPRESSED_BYTES] 上限（gzip 炸弹防御：超限抛
     * [IOException]，由 decode 折叠为 CorruptPayload，绝不让恶意码把内存打爆）。
     */
    private fun gunzip(bytes: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream(512)
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_DECOMPRESSED_BYTES) {
                    throw IOException("decompressed share payload exceeds $MAX_DECOMPRESSED_BYTES bytes")
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun ByteArray.toBase64String(): String = b64Encoder.encodeToString(this)

    private fun malformed(reason: String): Result<ProviderSharePayload> =
        failure(ShareCodecError.Malformed(reason))

    private fun corrupt(reason: String): Result<ProviderSharePayload> =
        failure(ShareCodecError.CorruptPayload(reason))

    private fun failure(error: ShareCodecError): Result<ProviderSharePayload> =
        Result.failure(ShareDecodeException(error))

    /** 异常描述（截断 120 字符——错误句有界，防超长异常消息刷屏）。 */
    private fun describe(e: Throwable): String = e.message?.take(120) ?: "no detail"
}
