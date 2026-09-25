package com.apex.agent.core.codetools.diagnostics.providers

import com.apex.agent.core.codetools.diagnostics.Diagnostic
import com.apex.agent.core.codetools.diagnostics.DiagnosticContext
import com.apex.agent.core.codetools.diagnostics.DiagnosticProvider
import com.apex.agent.core.codetools.diagnostics.Severity
import com.apex.agent.core.codetools.diagnostics.fileExtensionOf
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * # XML 语法提供器
 *
 * 直接复用 JVM 内置的 DOM 解析器做良构性（well-formedness）判定 —— 与运行时
 * 真实解析行为同源、零额外依赖。SAXParseException 自带 1-based 行列号，无需
 * 换算；其余异常（编码问题、实体缺失等无定位信息者）按文件级（行 0）上报。
 * 错误码 xml.syntax（ERROR 级）。
 *
 * 安全：禁用 DOCTYPE 声明（防 XXE 外部实体注入）—— Android 资源与配置文件
 * 本就不含 DOCTYPE，拒绝不会误伤；个别解析器实现不识别该特性时容错忽略
 * （行为退化为默认的外部实体不可达，仍然安全）。
 *
 * 跳过：空白文件与超过 512KB 的文件（大体量 XML 多为合法生成物，扫描价值低）。
 */
class XmlDiagnosticProvider : DiagnosticProvider {

    override val id: String = "xml"

    override fun supports(filePath: String): Boolean = fileExtensionOf(filePath) == "xml"

    override fun diagnose(content: String, filePath: String, context: DiagnosticContext): List<Diagnostic> {
        if (content.isBlank() || content.length > MAX_CHARS) return emptyList()
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            // 禁用 DOCTYPE（防 XXE）；实现不认识该特性时静默忽略
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val builder = factory.newDocumentBuilder()
            // 显式接管错误处理：告警忽略（不污染 stderr），错误与致命错误即抛
            builder.setErrorHandler(object : ErrorHandler {
                override fun warning(exception: SAXParseException) {}
                override fun error(exception: SAXParseException) {
                    throw exception
                }

                override fun fatalError(exception: SAXParseException) {
                    throw exception
                }
            })
            builder.parse(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
            emptyList()
        } catch (e: SAXParseException) {
            listOf(
                Diagnostic(
                    e.lineNumber.coerceAtLeast(0),
                    e.columnNumber.coerceAtLeast(0),
                    Severity.ERROR,
                    CODE,
                    withDetail(e, "XML 语法错误")
                )
            )
        } catch (e: Exception) {
            listOf(Diagnostic(0, 0, Severity.ERROR, CODE, withDetail(e, "XML 语法错误")))
        }
    }

    // ── 辅助 ─────────────────────────────────────────────────────

    /** 异常消息取首行并限长，拼上中文前缀；空消息退回兜底文案。 */
    private fun withDetail(e: Exception, fallback: String): String {
        val detail = e.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(MAX_MESSAGE_CHARS)
            ?.trim()
            .orEmpty()
        return if (detail.isEmpty()) fallback else "XML 解析失败：$detail"
    }

    private companion object {
        const val CODE = "xml.syntax"
        const val MAX_CHARS = 512 * 1024
        const val MAX_MESSAGE_CHARS = 200
    }
}
