package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.builtin.ApkInstallResult
import com.apex.agent.core.tools.builtin.ApkInstaller
import com.apex.agent.core.tools.builtin.AppDownloadInstallTool
import com.apex.agent.core.tools.builtin.AppInstallApkTool
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * AppDownloadInstallTool / AppInstallApkTool 的纯 JVM 测试。
 *
 * 用 MockWebServer 假装一个 APK 下载源，验证：
 * - app_install_apk：参数校验（路径必须绝对 / 文件必须存在 / 扩展名）
 * - app_download_install：URL → 下载 → 哈希校验 → 安装调用链
 *
 * 验证不涉及的（需 Android）：实际 PackageInstaller 调用由 [FakeApkInstaller]
 * 替代，仅记录 install 被调用 + 参数。
 */
class AppDownloadInstallToolTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("apex-apk-test").toFile()
        httpClient = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    // ── app_install_apk ─────────────────────────────────────────────────

    @Test
    fun `install_apk rejects relative path`() = runTest {
        val installer = FakeApkInstaller()
        val tool = AppInstallApkTool(installer)
        val result = tool.execute("""{"path": "relative/path.apk"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention path: $result", result.contains("path"))
        assertEquals(0, installer.installCallCount)
    }

    @Test
    fun `install_apk rejects non-existent file`() = runTest {
        val installer = FakeApkInstaller()
        val tool = AppInstallApkTool(installer)
        val result = tool.execute("""{"path": "/tmp/nonexistent.apk"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention not found: $result", result.contains("not found") || result.contains("APK"))
        assertEquals(0, installer.installCallCount)
    }

    @Test
    fun `install_apk rejects non-apk extension`() = runTest {
        val installer = FakeApkInstaller()
        val tool = AppInstallApkTool(installer)
        val fakeFile = File(tempDir, "not-an-apk.txt").apply {
            // 修复：填充须超过 MIN_APK_BYTES(4KB)，否则先命中体积检查分支
            writeText("this is not an APK".repeat(600))
        }
        val result = tool.execute("""{"path": "${fakeFile.absolutePath}"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention extension: $result", result.contains("extension"))
        assertEquals(0, installer.installCallCount)
    }

    @Test
    fun `install_apk rejects tiny file too small to be APK`() = runTest {
        val installer = FakeApkInstaller()
        val tool = AppInstallApkTool(installer)
        val tinyFile = File(tempDir, "tiny.apk").apply {
            // 小于 MIN_APK_BYTES (4KB)
            writeBytes(ByteArray(100))
        }
        val result = tool.execute("""{"path": "${tinyFile.absolutePath}"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention too small: $result", result.contains("too small"))
        assertEquals(0, installer.installCallCount)
    }

    @Test
    fun `install_apk succeeds for valid apk file`() = runTest {
        val installer = FakeApkInstaller()
        val tool = AppInstallApkTool(installer)
        val apkFile = File(tempDir, "valid.apk").apply {
            writeBytes(ByteArray(8 * 1024))  // 8KB，足够大
        }
        val result = tool.execute("""{"path": "${apkFile.absolutePath}", "source_url": "https://example.com/app.apk"}""")
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertEquals(1, installer.installCallCount)
        assertEquals(apkFile.absolutePath, installer.lastApkPath)
        assertEquals("https://example.com/app.apk", installer.lastSourceUrl)
    }

    @Test
    fun `install_apk reports host install failure`() = runTest {
        val installer = FakeApkInstaller(success = false, message = "storage full")
        val tool = AppInstallApkTool(installer)
        val apkFile = File(tempDir, "valid.apk").apply { writeBytes(ByteArray(8 * 1024)) }
        val result = tool.execute("""{"path": "${apkFile.absolutePath}"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention storage full: $result", result.contains("storage full"))
    }

    // ── app_download_install ───────────────────────────────────────────

    @Test
    fun `download_install rejects non-http url`() = runTest {
        val installer = FakeApkInstaller()
        val tool = AppDownloadInstallTool(httpClient, tempDir, installer)
        val result = tool.execute("""{"url": "file:///local/path.apk"}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention http: $result", result.contains("http"))
        assertEquals(0, installer.installCallCount)
    }

    @Test
    fun `download_install rejects missing url`() = runTest {
        val installer = FakeApkInstaller()
        val tool = AppDownloadInstallTool(httpClient, tempDir, installer)
        val result = tool.execute("""{}""")
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention url: $result", result.contains("url"))
    }

    @Test
    fun `download_install downloads and installs from a fake server`() = runTest {
        val installer = FakeApkInstaller()
        val tool = AppDownloadInstallTool(httpClient, tempDir, installer)
        // 准备假 APK bytes（4KB+，足以通过最小校验）
        val apkBytes = ByteArray(8 * 1024) { (it and 0xFF).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setBody(Buffer().write(apkBytes))
        )
        val result = tool.execute(
            """{"url": "${server.url("/app.apk")}", "filename": "test.apk"}"""
        )
        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertTrue("should mention test.apk: $result", result.contains("test.apk"))
        assertEquals(1, installer.installCallCount)
        // 下载文件应保留在 downloadDir
        val downloadedFile = File(tempDir, "test.apk")
        assertTrue("downloaded file should persist: ${downloadedFile.absolutePath}", downloadedFile.exists())
        assertEquals(apkBytes.size.toLong(), downloadedFile.length())
    }

    @Test
    fun `download_install detects sha256 mismatch and aborts`() = runTest {
        val installer = FakeApkInstaller()
        val tool = AppDownloadInstallTool(httpClient, tempDir, installer)
        val apkBytes = ByteArray(8 * 1024) { (it and 0xFF).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setBody(Buffer().write(apkBytes))
        )
        // 故意给出一个错误的 sha256
        val result = tool.execute(
            """{"url": "${server.url("/app.apk")}", "filename": "test.apk", "sha256": "0000000000000000000000000000000000000000000000000000000000000000"}"""
        )
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention mismatch: $result", result.contains("SHA-256 mismatch"))
        // install 不应被调用
        assertEquals(0, installer.installCallCount)
        // 文件应被清理
        val downloadedFile = File(tempDir, "test.apk")
        assertTrue("mismatched file should be deleted", !downloadedFile.exists())
    }

    @Test
    fun `download_install rejects text response (HTML page instead of APK)`() = runTest {
        val installer = FakeApkInstaller()
        val tool = AppDownloadInstallTool(httpClient, tempDir, installer)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>404 page</body></html>")
        )
        val result = tool.execute(
            """{"url": "${server.url("/app.apk")}", "filename": "test.apk"}"""
        )
        assertTrue("should fail: $result", result.startsWith("Error"))
        assertTrue("should mention HTML: $result", result.contains("HTML") || result.contains("text"))
        assertEquals(0, installer.installCallCount)
    }

    @Test
    fun `download_install rejects filename with path separators`() = runTest {
        val installer = FakeApkInstaller()
        val tool = AppDownloadInstallTool(httpClient, tempDir, installer)
        val result = tool.execute(
            """{"url": "${server.url("/app.apk")}", "filename": "../escape.apk"}"""
        )
        assertTrue("should fail: $result", result.startsWith("Error"))
        // either url pre-validation succeeds and we proceed, or filename validation fails first
        // either way install must not happen
        assertEquals(0, installer.installCallCount)
    }

    @Test
    fun `download_install passes source_url to_installer`() = runTest {
        val installer = FakeApkInstaller()
        val tool = AppDownloadInstallTool(httpClient, tempDir, installer)
        val apkBytes = ByteArray(8 * 1024) { (it and 0xFF).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setBody(Buffer().write(apkBytes))
        )
        val customSource = "https://my-tracker.example.com/app/v1.2.apk"
        tool.execute(
            """{"url": "${server.url("/app.apk")}", "filename": "test.apk", "source_url": "$customSource"}"""
        )
        assertEquals(customSource, installer.lastSourceUrl)
    }

    // ── Fake installer ─────────────────────────────────────────────────

    private class FakeApkInstaller(
        private val success: Boolean = true,
        private val message: String = "install triggered"
    ) : ApkInstaller {
        @Volatile var installCallCount: Int = 0
            private set
        @Volatile var lastApkPath: String? = null
            private set
        @Volatile var lastSourceUrl: String? = null
            private set

        override suspend fun install(apkPath: String, sourceUrl: String?): ApkInstallResult {
            installCallCount++
            lastApkPath = apkPath
            lastSourceUrl = sourceUrl
            return ApkInstallResult(
                success = success,
                message = message,
                sessionId = 1,
                packageName = "com.example.app",
                requiresUserConfirmation = true
            )
        }
    }
}
