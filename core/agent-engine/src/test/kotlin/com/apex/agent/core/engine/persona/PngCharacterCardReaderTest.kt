package com.apex.agent.core.engine.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

/**
 * 4-e — PNG 角色卡读取器测试。手写字节级 PNG 构造器（签名 + chunk：
 * 长度大端 + 类型 + 数据 + CRC 占位零——读取器不校验 CRC，测试无需真算）。
 *
 * 覆盖：tEXt chara / zTXt 解压 / ccv3 / Chara 大小写变体 / 签名不符 /
 * 截断 / 长度越界 / extractAllTextChunks / 无文本 chunk / 多 IDAT 跳过 /
 * IEND 终止 / 坏 Base64 跳过 / RikkaHub 包装回退。
 */
class PngCharacterCardReaderTest {

    // ═══ 测试用 PNG 构造器（零三方库）═══

    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    private val cardJson = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Serena","description":"swordmistress"}}"""

    private val otherCardJson = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Vega","description":"v3 card"}}"""

    /** 4 字节大端长度 + 类型 + 数据 + CRC 占位（读取器不校验）。 */
    private fun chunkBytes(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = data.size
        out.write(
            byteArrayOf(
                (len ushr 24 and 0xFF).toByte(),
                (len ushr 16 and 0xFF).toByte(),
                (len ushr 8 and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
        )
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write(byteArrayOf(0, 0, 0, 0))
        return out.toByteArray()
    }

    /** 最小 PNG：签名 + 若干 chunk（IHDR 占位 13 字节，内容无关）。 */
    private fun png(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pngSignature)
        for ((type, data) in chunks) {
            out.write(chunkBytes(type, data))
        }
        return out.toByteArray()
    }

    /** tEXt 数据：keyword\0text（Latin-1）。 */
    private fun textData(keyword: String, value: String): ByteArray {
        val kw = keyword.toByteArray(Charsets.ISO_8859_1)
        val v = value.toByteArray(Charsets.ISO_8859_1)
        val data = ByteArray(kw.size + 1 + v.size)
        kw.copyInto(data, 0)
        data[kw.size] = 0
        v.copyInto(data, kw.size + 1)
        return data
    }

    /** zTXt 数据：keyword\0 + 0x00（zlib）+ deflate(value)。 */
    private fun ztxtData(keyword: String, value: String): ByteArray {
        val deflater = Deflater()
        deflater.setInput(value.toByteArray(Charsets.ISO_8859_1))
        deflater.finish()
        val buffer = ByteArray(65536)
        val compressedSize = deflater.deflate(buffer)
        deflater.end()
        val compressed = buffer.copyOf(compressedSize)
        val kw = keyword.toByteArray(Charsets.ISO_8859_1)
        val data = ByteArray(kw.size + 2 + compressed.size)
        kw.copyInto(data, 0)
        data[kw.size] = 0
        data[kw.size + 1] = 0
        compressed.copyInto(data, kw.size + 2)
        return data
    }

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private val ihdr = "IHDR" to ByteArray(13)
    private val iend = "IEND" to ByteArray(0)

    // ═══ extractCardJson ═══

    @Test
    fun `minimal png with tEXt chara chunk extracts base64 card json`() {
        val bytes = png(ihdr, "tEXt" to textData("chara", b64(cardJson)), iend)
        assertEquals(cardJson, PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `zTXt compressed chara chunk extracts via inflater`() {
        val bytes = png(ihdr, "zTXt" to ztxtData("chara", b64(cardJson)), iend)
        assertEquals(cardJson, PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `zTXt with non-zero compression method is skipped`() {
        val data = ztxtData("chara", b64(cardJson))
        data[data.indexOf(0.toByte()) + 1] = 1 // 未知压缩方法
        val bytes = png(ihdr, "zTXt" to data, iend)
        assertNull(PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `ccv3 keyword extracts v3 card json`() {
        val bytes = png(ihdr, "tEXt" to textData("ccv3", b64(otherCardJson)), iend)
        assertEquals(otherCardJson, PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `Chara keyword case variant extracts card json`() {
        val bytes = png(ihdr, "tEXt" to textData("Chara", b64(cardJson)), iend)
        assertEquals(cardJson, PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `base64 with embedded newlines still decodes`() {
        val encoded = b64(cardJson)
        val wrapped = encoded.chunked(40).joinToString("\n")
        val bytes = png(ihdr, "tEXt" to textData("chara", wrapped), iend)
        assertEquals(cardJson, PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `signature mismatch returns null`() {
        val bytes = png(ihdr, "tEXt" to textData("chara", b64(cardJson)), iend)
        bytes[0] = 0x42.toByte() // 破坏首字节
        assertNull(PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `truncated file mid chunk data returns null without crashing`() {
        // 不写 IEND，直接截断 tEXt chunk 的数据与 CRC：声称长度越过缓冲 → 停止
        val full = png(ihdr, "tEXt" to textData("chara", b64(cardJson)))
        val truncated = full.copyOf(full.size - 10)
        assertNull(PngCharacterCardReader.extractCardJson(truncated))
    }

    @Test
    fun `truncated file mid chunk header returns null`() {
        // 只留 tEXt chunk 头的前 3 字节（8 字节头不完整 → 循环条件不满足）
        val full = png(ihdr, "tEXt" to textData("chara", b64(cardJson)))
        val dataLen = textData("chara", b64(cardJson)).size
        val truncated = full.copyOf(full.size - (dataLen + 4 + 5))
        assertTrue(truncated.size == 8 + 25 + 3)
        assertNull(PngCharacterCardReader.extractCardJson(truncated))
    }

    @Test
    fun `chunk length overrun stops scanning gracefully`() {
        // 长度字段 0x7FFFFFFF：远超缓冲 → 停止
        val out = ByteArrayOutputStream()
        out.write(pngSignature)
        out.write(
            byteArrayOf(
                0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                't'.code.toByte(), 'E'.code.toByte(), 'X'.code.toByte(), 't'.code.toByte()
            )
        )
        out.write(textData("chara", b64(cardJson)))
        assertNull(PngCharacterCardReader.extractCardJson(out.toByteArray()))
    }

    @Test
    fun `moderate length overrun also stops`() {
        val data = textData("chara", b64(cardJson))
        // 声称长度 = 实际 + 100（越过缓冲 1 字节即触发守卫）
        val out = ByteArrayOutputStream()
        out.write(pngSignature)
        val len = data.size + 100
        out.write(
            byteArrayOf(
                (len ushr 24 and 0xFF).toByte(),
                (len ushr 16 and 0xFF).toByte(),
                (len ushr 8 and 0xFF).toByte(),
                (len and 0xFF).toByte(),
                't'.code.toByte(), 'E'.code.toByte(), 'X'.code.toByte(), 't'.code.toByte()
            )
        )
        out.write(data)
        assertNull(PngCharacterCardReader.extractCardJson(out.toByteArray()))
    }

    @Test
    fun `png without text chunks returns null`() {
        val bytes = png(ihdr, "IDAT" to ByteArray(16), iend)
        assertNull(PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `empty and tiny inputs return null`() {
        assertNull(PngCharacterCardReader.extractCardJson(ByteArray(0)))
        assertNull(PngCharacterCardReader.extractCardJson(byteArrayOf(-119, 80)))
    }

    @Test
    fun `non-card keywords are ignored`() {
        val bytes = png(
            ihdr,
            "tEXt" to textData("Title", "My Picture"),
            "tEXt" to textData("Software", "SomeTool"),
            iend
        )
        assertNull(PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `invalid base64 value is skipped and later chunk wins`() {
        val bytes = png(
            ihdr,
            "tEXt" to textData("chara", "!!! definitively not base64 or json !!!"),
            "tEXt" to textData("chara", b64(cardJson)),
            iend
        )
        assertEquals(cardJson, PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `valid base64 of non-json payload is skipped`() {
        val bytes = png(ihdr, "tEXt" to textData("chara", b64("just some plain text")), iend)
        assertNull(PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `raw json value is returned directly`() {
        val bytes = png(ihdr, "tEXt" to textData("chara", cardJson), iend)
        assertEquals(cardJson, PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `bracketed chara wrapper falls back to inner base64`() {
        // RikkaHub 兼容：metadata-extractor 文本形态 "[chara: <base64>]"
        val value = "[chara: " + b64(cardJson) + "]"
        val bytes = png(ihdr, "tEXt" to textData("chara", value), iend)
        assertEquals(cardJson, PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `IEND terminates scanning - card after IEND is not found`() {
        val bytes = png(ihdr, iend, "tEXt" to textData("chara", b64(cardJson)))
        assertNull(PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `first decodable card wins over later chunks`() {
        val bytes = png(
            ihdr,
            "tEXt" to textData("chara", "not base64!! garbage"),
            "tEXt" to textData("chara", b64(cardJson)),
            "tEXt" to textData("ccv3", b64(otherCardJson)),
            iend
        )
        assertEquals(cardJson, PngCharacterCardReader.extractCardJson(bytes))
    }

    @Test
    fun `multiple IDAT chunks are skipped without interference`() {
        val bytes = png(
            ihdr,
            "IDAT" to ByteArray(64) { (it * 31 and 0xFF).toByte() },
            "IDAT" to ByteArray(32) { 0 },
            "tEXt" to textData("chara", b64(cardJson)),
            "IDAT" to ByteArray(16),
            iend
        )
        assertEquals(cardJson, PngCharacterCardReader.extractCardJson(bytes))
    }

    // ═══ extractAllTextChunks ═══

    @Test
    fun `extractAllTextChunks returns keyword map for tEXt and zTXt`() {
        val bytes = png(
            ihdr,
            "tEXt" to textData("Title", "Card Preview"),
            "tEXt" to textData("Software", "SillyTavern 1.12"),
            "zTXt" to ztxtData("Comment", "compressed comment text"),
            iend
        )
        val map = PngCharacterCardReader.extractAllTextChunks(bytes)
        assertEquals(3, map.size)
        assertEquals("Card Preview", map["Title"])
        assertEquals("SillyTavern 1.12", map["Software"])
        assertEquals("compressed comment text", map["Comment"])
    }

    @Test
    fun `extractAllTextChunks keeps first value for duplicate keywords`() {
        val bytes = png(
            ihdr,
            "tEXt" to textData("Title", "first"),
            "tEXt" to textData("Title", "second"),
            iend
        )
        val map = PngCharacterCardReader.extractAllTextChunks(bytes)
        assertEquals("first", map["Title"])
        assertEquals(1, map.size)
    }

    @Test
    fun `extractAllTextChunks empty for signature mismatch and no text chunks`() {
        assertEquals(emptyMap<String, String>(), PngCharacterCardReader.extractAllTextChunks(ByteArray(12)))
        val bytes = png(ihdr, "IDAT" to ByteArray(8), iend)
        assertTrue(PngCharacterCardReader.extractAllTextChunks(bytes).isEmpty())
    }

    @Test
    fun `extractAllTextChunks ignores chunks after IEND`() {
        val bytes = png(ihdr, "tEXt" to textData("Title", "kept"), iend, "tEXt" to textData("After", "dropped"))
        val map = PngCharacterCardReader.extractAllTextChunks(bytes)
        assertEquals(mapOf("Title" to "kept"), map)
    }

    @Test
    fun `text chunk without null separator is skipped`() {
        // 无 \0 分隔 → 解析失败 → 跳过，不影响其它 chunk
        val bad = "no separator here".toByteArray(Charsets.ISO_8859_1)
        val bytes = png(ihdr, "tEXt" to bad, "tEXt" to textData("Title", "ok"), iend)
        val map = PngCharacterCardReader.extractAllTextChunks(bytes)
        assertEquals(mapOf("Title" to "ok"), map)
    }
}
