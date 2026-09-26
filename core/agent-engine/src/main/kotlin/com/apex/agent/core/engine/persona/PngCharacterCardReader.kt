package com.apex.agent.core.engine.persona

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * ═══ PNG 角色卡读取器（4-e）═══
 *
 * RikkaHub 用 metadata-extractor 库读 PNG tEXt；core 层保持零三方依赖
 * （RikkaHub 有 lib 而我们手写），这里实现纯 Kotlin 的 PNG chunk 扫描器：
 *
 * ```
 * PNG 文件 = 8 字节签名 + 0..N 个 chunk
 * chunk    = 4 字节大端长度 + 4 字节类型 + 数据 + 4 字节 CRC
 * tEXt     = "keyword\0text"（Latin-1）→ SillyTavern 写 keyword=chara，
 *            值为 Base64(JSON)；V3 卡写 ccv3
 * zTXt     = "keyword\0" + 1 字节压缩方法(0=zlib) + zlib 数据
 * ```
 *
 * **CRC 不校验**（刻意，文档化决策）：校验 CRC 会拒掉一批「数据完整但
 * 尾部被截断」的卡（IM 转存重编码、下载中断、老工具写错 CRC 都常见）。
 * 签名校验 + chunk 结构边界校验（长度不许越过缓冲）+ Base64/JSON 形状
 * 门控已经足够可靠——坏 CRC 只意味着数据可能是坏的，而后续解析器还有
 * 三层防御兜底。
 *
 * **防御式纪律**：签名不符 → null；chunk 声称长度越过缓冲 → 停止扫描
 * （容错截断传输，不抛）；解压超上限（防 zip 炸弹）→ null；任何一步
 * 失败只影响该 chunk，不影响后续。iTXt 不实现（ST/Chub 生态只用 tEXt
 * 与 zTXt；iTXt 的压缩/语言标记流程从未出现在角色卡工具链里）。
 */
object PngCharacterCardReader {

    /** PNG 签名：89 50 4E 47 0D 0A 1A 0A。 */
    private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /** 单 chunk 数据长度上限（2^31-1，防病态长度字段）。 */
    private const val MAX_CHUNK_LENGTH = 0x7FFFFFFFL

    /** zTXt 解压输出上限（8MB——防解压炸弹；正常卡 JSON 远小于此）。 */
    private const val MAX_INFLATED_TEXT_BYTES = 8 * 1024 * 1024

    /** 卡数据关键词（SillyTavern V2 用 chara，V3 用 ccv3；大小写不敏感）。 */
    private val CARD_KEYWORDS = setOf("chara", "ccv3")

    /**
     * RikkaHub 兼容回退：其导入器用正则从文本里剥 "[chara: <base64>]"
     * 包装（metadata-extractor 的文本表示形态）。我们做同样的回退，
     * 命中包装则取内层 base64。
     */
    private val CHARA_WRAPPER = Regex("""\[chara:\s*(.+?)]""")

    /**
     * 从 PNG 字节流提取角色卡 JSON 文本。
     *
     * 依序扫描 tEXt / zTXt chunk（IEND 终止，IDAT 等数据 chunk 跳过），
     * 关键词（大小写不敏感）命中 chara / ccv3 的文本值依次尝试解码：
     * 1. 裸 JSON（个别工具直写 JSON 文本）；
     * 2. Base64 → UTF-8（SillyTavern 标准形态）；
     * 3. "[chara: <base64>]" 包装（RikkaHub 兼容回退）。
     *
     * 每个候选都要通过「以 { 开头」的 JSON 形状门控才算命中；返回首个
     * 可解出的卡 JSON，全部失败（含签名不符 / 截断 / 无卡 chunk）→ null。
     */
    fun extractCardJson(pngBytes: ByteArray): String? {
        val candidates = mutableListOf<String>()
        forEachTextChunk(pngBytes) { keyword, text ->
            if (keyword.trim().lowercase() in CARD_KEYWORDS) {
                candidates.add(text)
            }
        }
        for (candidate in candidates) {
            decodeCardValue(candidate)?.let { return it }
        }
        return null
    }

    /**
     * 提取全部文本 chunk（tEXt 明文 + zTXt 解压后）的关键词 → 文本映射。
     *
     * 通用辅助：卡提取之外的 PNG 文本元数据巡检（Title / Software /
     * Comment 等）。同关键词重复出现时保留首个值（与 extractCardJson 的
     * 首个可解码语义一致）。签名不符 / 截断 → 空映射（绝不抛）。
     */
    fun extractAllTextChunks(pngBytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        forEachTextChunk(pngBytes) { keyword, text ->
            val key = keyword.trim()
            if (key.isNotEmpty() && key !in result) {
                result[key] = text
            }
        }
        return result
    }

    // ═══ 内部：chunk 迭代 ═══

    /** 遍历 chunk（type + data），签名门控 + 结构边界守卫。 */
    private inline fun forEachChunk(
        pngBytes: ByteArray,
        block: (type: String, data: ByteArray) -> Unit
    ) {
        if (pngBytes.size < PNG_SIGNATURE.size) return
        for (i in PNG_SIGNATURE.indices) {
            if (pngBytes[i] != PNG_SIGNATURE[i]) return
        }
        var offset = PNG_SIGNATURE.size
        while (offset + 8 <= pngBytes.size) {
            val length = readUInt32BE(pngBytes, offset)
            if (length > MAX_CHUNK_LENGTH) return
            val type = String(pngBytes, offset + 4, 4, Charsets.ISO_8859_1)
            val dataStart = offset + 8
            val available = pngBytes.size - dataStart
            // 长度越过缓冲 = 传输截断 → 停止扫描（已读 chunk 照常产出）
            if (length > available) return
            val dataEnd = dataStart + length.toInt()
            block(type, pngBytes.copyOfRange(dataStart, dataEnd))
            if (type == "IEND") return
            // CRC 区截断（dataEnd 贴近缓冲尾）→ 后面不可能有完整 chunk 头
            if (dataEnd > pngBytes.size - 4) return
            offset = dataEnd + 4
        }
    }

    /** 遍历文本 chunk（tEXt / zTXt），解析失败的 chunk 静默跳过。 */
    private inline fun forEachTextChunk(
        pngBytes: ByteArray,
        block: (keyword: String, text: String) -> Unit
    ) {
        forEachChunk(pngBytes) { type, data ->
            when (type) {
                "tEXt" -> parseTextChunk(data)?.let { (keyword, text) -> block(keyword, text) }
                "zTXt" -> parseZtxtChunk(data)?.let { (keyword, text) -> block(keyword, text) }
            }
        }
    }

    /** tEXt：keyword\0text（Latin-1）；无分隔符或空关键词 → null。 */
    private fun parseTextChunk(data: ByteArray): Pair<String, String>? {
        val separator = data.indexOf(0.toByte())
        if (separator <= 0) return null
        val keyword = String(data, 0, separator, Charsets.ISO_8859_1)
        val text = String(data, separator + 1, data.size - separator - 1, Charsets.ISO_8859_1)
        return keyword to text
    }

    /**
     * zTXt：keyword\0 + 1 字节压缩方法 + zlib 数据。
     * 压缩方法非 0（仅支持 zlib/deflate）→ null；解压失败/超限 → null。
     */
    private fun parseZtxtChunk(data: ByteArray): Pair<String, String>? {
        val separator = data.indexOf(0.toByte())
        if (separator <= 0 || separator + 2 > data.size) return null
        val keyword = String(data, 0, separator, Charsets.ISO_8859_1)
        val method = data[separator + 1].toInt() and 0xFF
        if (method != 0) return null
        val inflated = inflateZlib(data, separator + 2) ?: return null
        return keyword to String(inflated, Charsets.ISO_8859_1)
    }

    /**
     * zlib 解压（java.util.zip.Inflater）：截断流返回已解压部分（容错），
     * 坏数据 → null，输出超 [MAX_INFLATED_TEXT_BYTES] → null（防炸弹）。
     */
    private fun inflateZlib(data: ByteArray, from: Int): ByteArray? {
        if (from >= data.size) return null
        val inflater = Inflater()
        try {
            inflater.setInput(data, from, data.size - from)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    // 无进展且不再要输入 = 截断/坏流 → 带已解压部分返回
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    total += n
                    if (total > MAX_INFLATED_TEXT_BYTES) return null
                    out.write(buffer, 0, n)
                }
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    // ═══ 内部：卡值解码 ═══

    /**
     * 单个候选文本值 → 卡 JSON：裸 JSON 直取 → Base64 → 包装回退。
     * MIME Base64 解码器（容忍换行/空白混入），失败或产物不似 JSON
     * （不以 { 开头）→ null，交给下一个候选。
     */
    private fun decodeCardValue(value: String): String? {
        val direct = value.trim()
        if (direct.startsWith("{")) return direct
        decodeBase64ToJson(direct)?.let { return it }
        val inner = CHARA_WRAPPER.find(direct)?.groupValues?.get(1) ?: return null
        return decodeBase64ToJson(inner.trim())
    }

    private fun decodeBase64ToJson(value: String): String? {
        if (value.isEmpty()) return null
        val bytes = try {
            Base64.getMimeDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val text = String(bytes, Charsets.UTF_8).trim()
        return if (text.startsWith("{")) text else null
    }

    /** 无符号 4 字节大端读取（Long 承载，避免符号扩展污染长度判断）。 */
    private fun readUInt32BE(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0..3) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
