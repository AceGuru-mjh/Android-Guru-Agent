package com.apex.agent.core.tools.builtin

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import java.util.zip.ZipFile

/**
 * 文档文本提取器（PDF / DOCX）—— 纯 JVM、零第三方依赖。
 *
 * 背景：用户把 PDF 附件交给 Agent 后，旧链路 read_file 判定「Binary file」直接
 * 拒读 —— 模型拿不到任何正文，用户以为「AI 能读 PDF」，实际上一行都读不到。
 * 本提取器让 read_file 对 PDF/DOCX 先做文本提取，再走既有的视口滚动模式。
 *
 * ## PDF 策略（最小可行解析器）
 * PDF 正文存放在 content stream 的文本操作符里：
 *  - `Tj`（show text）/ `TJ`（数组）/ `'` / `"` 携带字符串实参；
 *  - 字符串可能是字面量（括号包裹 + 反斜杠转义 + 八进制）或十六进制 `<...>`；
 *  - content stream 大多 FlateDecode 压缩（zlib/deflate）。
 *
 * 提取步骤：扫 `stream…endstream` → 按字典里的 /FlateDecode 解压 → 在解出的
 * 内容流上扫描文本操作符 → 解码字符串 → 按换位操作符（Td、TD、T*、BT）断行。
 *
 * ## 已知限制（诚实降级，不静默给假数据）
 *  - **CID/Type0 字体**（多数中文扫描版/部分中文导出 PDF）：Tj 实参是字形 ID
 *    而非字符码，无 ToUnicode CMap 参与解码会得到乱码。检测到高比例不可打印/
 *    私有区字符时**明确报告**建议改用沙箱 `pdftotext`，不冒充成功。
 *  - **扫描图片型 PDF**：无文本操作符，提取为空 → 如实报告 0 字符。
 *
 * ## DOCX 策略
 * DOCX = ZIP + `word/document.xml`；`<w:t>` 是文本 run、`<w:p>` 是段落。
 * 解包 + 剥 XML 标签即可，覆盖率接近 100%（Office 生态标准格式）。
 */
object DocumentTextExtractor {

    /** 单次提取的字符上限：防 OOM（百 MB 级 PDF / 恶意构造文档）。 */
    private const val MAX_CHARS = 2_000_000

    private const val PDF_MAGIC = "%PDF"

    /** 提取结果：正文行 + 提示（空/乱码时给用户与模型一致的下一步指引）。 */
    data class Extraction(
        val lines: List<String>,
        /** 附加提示（null = 干净提取）。非 null 时仍返回已提取部分。 */
        val note: String?
    ) {
        val isEmpty: Boolean get() = lines.isEmpty() || lines.all { it.isBlank() }
    }

    /**
     * 按扩展名/魔数分发提取。非文档返回 null（调用方走普通文本/二进制路径）。
     */
    fun extractIfDocument(file: File): Extraction? {
        if (file.length() == 0L) return null
        val name = file.name.lowercase()
        return when {
            name.endsWith(".pdf") -> extractPdf(file)
            name.endsWith(".docx") -> extractDocx(file)
            // 无 .pdf 扩展名但带 PDF 魔数（下载重命名/邮件附件等场景）
            hasPdfMagic(file) -> extractPdf(file)
            else -> null
        }
    }

    private fun hasPdfMagic(file: File): Boolean {
        val head = file.inputStream().use { it.readNBytes(1024) }
        return indexOf(head, PDF_MAGIC.toByteArray(), 0) != null
    }

    // ══════════════════════════════ DOCX ══════════════════════════════

    private fun extractDocx(file: File): Extraction {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml")
                    ?: return Extraction(emptyList(), "DOCX 内未找到 word/document.xml（非标准 Word 文档）")
                val xml = zip.getInputStream(entry).buffered().readBytes().decodeToString()
                val text = extractTextFromDocxXml(xml)
                if (text.isEmpty()) {
                    Extraction(emptyList(), "DOCX 无文本内容（可能为空文档）")
                } else {
                    // 段落边界已由 </w:p> 注入 \n；再裁掉多余空行（保持段落节奏）
                    Extraction(text.lines(), null)
                }
            }
        } catch (e: Exception) {
            Extraction(emptyList(), "DOCX 解析失败：${e.message ?: e::class.simpleName}")
        }
    }

    /** w:t 文本 run 拼接；</w:p> 段落落行；&amp; 等实体还原。 */
    internal fun extractTextFromDocxXml(xml: String): String {
        val sb = StringBuilder(minOf(xml.length, MAX_CHARS))
        var i = 0
        val n = xml.length
        while (i < n && sb.length < MAX_CHARS) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            // 落在标签外的裸文本（非 Word 命名空间结构）忽略 —— 只认 w:t
            val tagEnd = xml.indexOf('>', lt)
            if (tagEnd < 0) break
            val tag = xml.substring(lt + 1, tagEnd)
            when {
                tag.startsWith("w:t") -> {
                    // 取标签内文本直到下一个 '<'
                    val next = xml.indexOf('<', tagEnd + 1)
                    val text = if (next < 0) xml.substring(tagEnd + 1) else xml.substring(tagEnd + 1, next)
                    sb.append(decodeXmlEntities(text))
                    i = if (next < 0) n else next
                }
                tag == "/w:p" || tag.startsWith("/w:p ") -> {
                    sb.append('\n')
                    i = tagEnd + 1
                }
                else -> i = tagEnd + 1
            }
        }
        return sb.toString().trim()
    }

    private fun decodeXmlEntities(s: String): String = when {
        s.contains('&') -> s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
        else -> s
    }

    // ══════════════════════════════ PDF ══════════════════════════════

    private fun extractPdf(file: File): Extraction {
        return try {
            val bytes = file.inputStream().use { it.readBytes() }
            val text = StringBuilder()
            var i = 0
            val n = bytes.size
            while (i < n && text.length < MAX_CHARS) {
                // 定位下一个 stream 关键字
                val s = indexOf(bytes, "stream".toByteArray(), i) ?: break
                // 向前回看字典（最多 512B）判断是否 FlateDecode
                val dictStart = maxOf(0, s - 512)
                val dict = bytes.decodeToString(dictStart, s)
                // 流数据起点：跳过 stream 后的 EOL（\r\n / \n / \r）
                var dataStart = s + 6
                if (dataStart < n && bytes[dataStart] == '\r'.code.toByte()) dataStart++
                if (dataStart < n && bytes[dataStart] == '\n'.code.toByte()) dataStart++
                val end = indexOf(bytes, "endstream".toByteArray(), dataStart) ?: break
                if (end <= dataStart) { i = end + 9; continue }

                val raw = bytes.copyOfRange(dataStart, end)
                val decoded: ByteArray? = if (dict.contains("/FlateDecode")) {
                    inflate(raw)
                } else {
                    // 未压缩流：只在其内容可打印比例高时当正文（否则是图片等二进制流）
                    if (printableRatio(raw) > 0.9) raw else null
                }
                if (decoded != null && looksLikeContentStream(decoded)) {
                    extractTextOps(decoded.decodeToString(), text)
                }
                i = end + 9
            }
            finishPdfExtraction(text.toString())
        } catch (e: Exception) {
            Extraction(emptyList(), "PDF 解析失败：${e.message ?: e::class.simpleName}")
        }
    }

    /** zlib/deflate 解压（PDF FlateDecode = raw deflate，无 zlib 头时兼容）。 */
    private fun inflate(data: ByteArray): ByteArray? {
        // 尝试 zlib（含头）→ raw deflate 双路径
        for (raw in listOf(false, true)) {
            try {
                val inflater = Inflater(raw)
                inflater.setInput(data)
                val output = ByteArray(1 shl 20) // 1MB 增长缓冲
                val out = java.io.ByteArrayOutputStream(maxOf(1024, data.size * 4))
                while (!inflater.finished()) {
                    val count = inflater.inflate(output)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        out.write(output, 0, count)
                        if (out.size() > MAX_CHARS) { inflater.end(); return out.toByteArray() }
                    }
                }
                inflater.end()
                if (out.size() > 0) return out.toByteArray()
            } catch (_: Exception) {
                // 尝试下一种模式
            }
        }
        return null
    }

    /**
     * 在一条 content stream 上扫描文本操作符并解码字符串。
     * Td、TD、T*、BT 视为换行点（行定位指令），保证输出带段落结构。
     */
    internal fun extractTextOps(stream: String, out: StringBuilder) {
        var i = 0
        val n = stream.length
        var pendingBreak = false
        while (i < n && out.length < MAX_CHARS) {
            val c = stream[i]
            when {
                // 字面量字符串 → 收集，其后紧跟的可能是 Tj/' /" 或 TJ 数组元素
                c == '(' -> {
                    val (value, next) = readLiteralString(stream, i)
                    if (value.isNotEmpty()) {
                        if (pendingBreak) { out.append('\n'); pendingBreak = false }
                        out.append(value)
                    }
                    i = next
                }
                // 十六进制字符串
                c == '<' && i + 1 < n && stream[i + 1] != '<' -> {
                    val (value, next) = readHexString(stream, i)
                    if (value.isNotEmpty()) {
                        if (pendingBreak) { out.append('\n'); pendingBreak = false }
                        out.append(value)
                    }
                    i = next
                }
                // 换行操作符：段落/行边界
                // 注意：startsWith(prefix, i) 从当前位置匹配 —— 传 i+1 会把
                // 「当前字符 + 下一字符」的窗口整体右移一位，操作符永远匹配不上
                //（诊断用例：Td 断行全部失效、文本连成一行）。
                c == 'T' -> {
                    if (stream.startsWith("Td", i) || stream.startsWith("TD", i)) {
                        pendingBreak = true; i += 2
                    } else if (stream.startsWith("T*", i)) {
                        pendingBreak = true; i += 2
                    } else i++
                }
                c == 'B' && stream.startsWith("BT", i) -> { pendingBreak = true; i += 2 }
                else -> i++
            }
        }
    }

    /** 括号字面量：嵌套括号计数 + 转义（\n \r \t \b \f \( \) \\ \ddd 八进制）。 */
    private fun readLiteralString(s: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1
        var depth = 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    val e = s[i + 1]
                    when (e) {
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        '(' -> { sb.append('('); i += 2 }
                        ')' -> { sb.append(')'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        '\n' -> i += 2 // 行继续符：跳过
                        else -> {
                            if (e in '0'..'7') {
                                // 最多 3 位八进制
                                var j = i + 1
                                val oct = StringBuilder()
                                while (j < s.length && oct.length < 3 && s[j] in '0'..'7') {
                                    oct.append(s[j]); j++
                                }
                                val code = oct.toString().toInt(8)
                                sb.append(code.toChar())
                                i = j
                            } else {
                                sb.append(e); i += 2
                            }
                        }
                    }
                }
                c == '(' -> { depth++; sb.append(c); i++ }
                c == ')' -> {
                    depth--
                    if (depth == 0) return sb.toString() to (i + 1)
                    sb.append(c); i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString() to i
    }

    /** 十六进制字符串 <...> → 字节 → Latin-1 近似解码（WinAnsi 常态）。 */
    private fun readHexString(s: String, start: Int): Pair<String, Int> {
        val end = s.indexOf('>', start)
        if (end < 0) return "" to s.length
        val hex = s.substring(start + 1, end).filterNot { it.isWhitespace() }
        val sb = StringBuilder(hex.length / 2)
        var i = 0
        while (i + 1 < hex.length) {
            val byte = ((hex[i].digitToIntOrNull(16) ?: 0) shl 4) or (hex[i + 1].digitToIntOrNull(16) ?: 0)
            sb.append(byte.toChar())
            i += 2
        }
        return sb.toString() to (end + 1)
    }

    private fun finishPdfExtraction(text: String): Extraction {
        val cleaned = text.replace("\r\n", "\n").replace('\r', '\n')
        if (cleaned.isBlank()) {
            return Extraction(
                emptyList(),
                "PDF 无可提取文本（可能是扫描图片型 PDF 或仅含图形）—— 建议在 Ubuntu 终端安装 poppler-utils 后用 pdftotext 转换，或直接把文字粘贴给我"
            )
        }
        // CID 乱码检测：私有使用区/控制符占比过高 → 判定编码不可解
        val suspicious = cleaned.count { it.code in 0xE000..0xF8FF || (it.code < 32 && it != '\n' && it != '\t') }
        val ratio = suspicious.toFloat() / cleaned.length
        val lines = cleaned.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
        return if (ratio > 0.25f) {
            Extraction(
                lines,
                "PDF 使用内嵌 CID 字体（常见于中文导出），直解得到字形编码而非文字 —— 请在 Ubuntu 终端安装 poppler-utils 后用 pdftotext 提取，或提供文本版文件"
            )
        } else {
            Extraction(lines, null)
        }
    }

    // ══════════════════════════════ 共用 ══════════════════════════════

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
        var i = maxOf(0, from)
        outer@ while (i <= haystack.size - needle.size) {
            for (k in needle.indices) {
                if (haystack[i + k] != needle[k]) {
                    i++; continue@outer
                }
            }
            return i
        }
        return null
    }

    private fun printableRatio(bytes: ByteArray): Float {
        if (bytes.isEmpty()) return 0f
        val sampleSize = minOf(bytes.size, 4096)
        var printable = 0
        for (i in 0 until sampleSize) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0x20..0x7E || b == '\n'.code || b == '\r'.code || b == '\t'.code || b >= 0x80) printable++
        }
        return printable.toFloat() / sampleSize
    }

    /** content stream 粗筛：含文本/绘图操作符才算（过滤XObject 图片等）。 */
    private fun looksLikeContentStream(bytes: ByteArray): Boolean {
        val s = bytes.decodeToString(0, minOf(bytes.size, 2048))
        return s.contains("BT") || s.contains("Tj") || s.contains("TJ")
    }

    /** 供 FileReadTool 惰性缓存使用：以路径 + 修改时间为键。 */
    internal fun cacheKey(file: File): String = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
}
