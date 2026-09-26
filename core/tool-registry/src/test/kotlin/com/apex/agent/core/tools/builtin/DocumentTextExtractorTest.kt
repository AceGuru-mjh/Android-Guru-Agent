package com.apex.agent.core.tools.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [DocumentTextExtractor] 单测：PDF（未压缩 / FlateDecode）与 DOCX 三条主路径。
 *
 * PDF 样本为手工构造的最小合法结构（跨平台零依赖，不依赖外部 fixture 文件）：
 * - `stream…endstream` 内容流含 Tj 文本操作符；
 * - Flate 样本把同一段内容流 deflate 压缩 + 字典标注 /FlateDecode。
 */
class DocumentTextExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══════════════════════ DOCX ═══════════════════════

    @Test
    fun `docx 提取文本并按段落断行`() {
        val file = tmp.newFile("hello.docx")
        writeDocx(file, """
            <w:document><w:body>
            <w:p><w:r><w:t>第一段：Hello</w:t></w:r></w:p>
            <w:p><w:r><w:t>第二段：World &amp; more</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent())
        val extraction = DocumentTextExtractor.extractIfDocument(file)
        assertNotNull(extraction)
        assertNull(extraction!!.note)
        assertEquals(listOf("第一段：Hello", "第二段：World & more"), extraction.lines)
    }

    @Test
    fun `非文档文件返回 null 走普通路径`() {
        val file = tmp.newFile("plain.txt")
        file.writeText("just text")
        assertNull(DocumentTextExtractor.extractIfDocument(file))
    }

    // ═══════════════════════ PDF · 未压缩 ═══════════════════════

    @Test
    fun `未压缩 pdf 提取 Tj 文本`() {
        val content = "BT /F1 12 Tf 72 720 Td (Hello PDF World) Tj ET"
        val file = tmp.newFile("plain.pdf")
        file.writeBytes(buildPdf(content, compressed = false))
        val extraction = DocumentTextExtractor.extractIfDocument(file)
        assertNotNull(extraction)
        assertNull(extraction!!.note)
        assertEquals("Hello PDF World", extraction.lines.joinToString(" ").trim())
    }

    @Test
    fun `pdf 换位操作符断行`() {
        // 两次 Td 定位 → 两行输出
        val content = "BT (Line One) Tj 0 -20 Td (Line Two) Tj ET"
        val file = tmp.newFile("lines.pdf")
        file.writeBytes(buildPdf(content, compressed = false))
        val extraction = DocumentTextExtractor.extractIfDocument(file)
        assertNotNull(extraction)
        assertTrue(extraction!!.lines.size >= 2)
        assertEquals("Line One", extraction.lines[0])
        assertEquals("Line Two", extraction.lines[1])
    }

    // ═══════════════════════ PDF · FlateDecode ═══════════════════════

    @Test
    fun `flate 压缩 pdf 提取文本`() {
        val content = "BT /F1 12 Tf (Compressed Stream Text) Tj ET"
        val file = tmp.newFile("flate.pdf")
        file.writeBytes(buildPdf(content, compressed = true))
        val extraction = DocumentTextExtractor.extractIfDocument(file)
        assertNotNull(extraction)
        assertNull(extraction!!.note)
        assertTrue(extraction.lines.joinToString(" ").contains("Compressed Stream Text"))
    }

    @Test
    fun `转义与八进制字面量解码`() {
        // \\( 转义括号 + \\101 八进制 'A'
        val content = """BT (Escaped \(paren\) and \101\102\103) Tj ET"""
        val file = tmp.newFile("esc.pdf")
        file.writeBytes(buildPdf(content, compressed = false))
        val extraction = DocumentTextExtractor.extractIfDocument(file)
        assertNotNull(extraction)
        assertEquals("Escaped (paren) and ABC", extraction!!.lines.joinToString("").trim())
    }

    @Test
    fun `十六进制字符串解码`() {
        val content = "BT <48656C6C6F> Tj ET" // "Hello" 的十六进制
        val file = tmp.newFile("hex.pdf")
        file.writeBytes(buildPdf(content, compressed = false))
        val extraction = DocumentTextExtractor.extractIfDocument(file)
        assertNotNull(extraction)
        assertEquals("Hello", extraction!!.lines.joinToString("").trim())
    }

    // ═══════════════════════ 空文本 PDF（诚实降级）═══════════════════════

    @Test
    fun `无文本操作符的 pdf 返回空提取与指引提示`() {
        val content = "1 0 0 1 0 0 cm 0 0 100 100 re f" // 纯图形
        val file = tmp.newFile("draw-only.pdf")
        file.writeBytes(buildPdf(content, compressed = false))
        val extraction = DocumentTextExtractor.extractIfDocument(file)
        assertNotNull(extraction)
        assertTrue(extraction!!.isEmpty)
        assertNotNull(extraction.note)
    }

    // ═══════════════════════ 构造工具 ═══════════════════════

    /** 构造最小合法 PDF：单页 + 单 content stream（可选 Flate 压缩）。 */
    private fun buildPdf(contentStream: String, compressed: Boolean): ByteArray {
        val streamBytes: ByteArray = if (compressed) {
            val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true) // raw deflate
            deflater.setInput(contentStream.toByteArray(Charsets.ISO_8859_1))
            deflater.finish()
            val buffer = ByteArray(4096)
            val out = java.io.ByteArrayOutputStream()
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                out.write(buffer, 0, n)
            }
            deflater.end()
            out.toByteArray()
        } else {
            contentStream.toByteArray(Charsets.ISO_8859_1)
        }
        val filter = if (compressed) " /Filter /FlateDecode" else ""
        val body = """
            %PDF-1.4
            1 0 obj
            << /Type /Catalog /Pages 2 0 R >>
            endobj
            2 0 obj
            << /Type /Pages /Kids [3 0 R] /Count 1 >>
            endobj
            3 0 obj
            << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
            endobj
            4 0 obj
            << /Length ${streamBytes.size}$filter >>
            stream
        """.trimIndent().replace("\n", "\n") + "\n"
        val tail = """
            endstream
            endobj
            5 0 obj
            << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
            endobj
            trailer
            << /Size 6 /Root 1 0 R >>
            %%EOF
        """.trimIndent() + "\n"
        return body.toByteArray(Charsets.ISO_8859_1) + streamBytes + tail.toByteArray(Charsets.ISO_8859_1)
    }

    private fun writeDocx(file: File, documentXml: String) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray())
            zip.closeEntry()
        }
    }
}
