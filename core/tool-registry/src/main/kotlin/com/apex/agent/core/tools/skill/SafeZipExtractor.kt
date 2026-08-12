package com.apex.agent.core.tools.skill

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 安全 ZIP 解压工具。
 *
 * 从失败项目 [Apex-agent] SkillManager.unzipToDirectory 改写而来。
 * 保留其核心防护（路径穿越攻击防御：entry 规范路径必须落在目标目录内），
 * 并补充解压炸弹防护（文件数上限、单文件大小上限）。纯 JVM 标准库，无 Android 依赖。
 */
object SafeZipExtractor {

    /** 默认单文件解压后最大字节数（50 MB），防止 zip bomb。 */
    const val DEFAULT_MAX_ENTRY_BYTES: Long = 50L * 1024 * 1024

    /** 默认总条目上限（10k），防止海量小文件耗尽资源。 */
    const val DEFAULT_MAX_ENTRIES: Int = 10_000

    /**
     * 将 zip 解压到目标目录（目标目录会被创建）。
     * @throws IllegalArgumentException 任一条目试图跳出目标目录（路径穿越）
     * @throws SecurityException 超出文件数或大小上限（疑似 zip bomb）
     */
    fun extract(zipFile: File, destinationDir: File, maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES, maxEntries: Int = DEFAULT_MAX_ENTRIES) {
        val destCanonical = destinationDir.canonicalFile
        destinationDir.mkdirs()

        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            val buffer = ByteArray(64 * 1024)
            var entries = 0

            while (true) {
                val entry = zis.nextEntry ?: break

                if (++entries > maxEntries) {
                    throw SecurityException("Zip entry count exceeds limit ($maxEntries); possible zip bomb")
                }

                // 路径穿越防御：规范路径必须落在目标目录内（相对路径不得含 ".."）。
                val outFile = File(destinationDir, entry.name).canonicalFile
                val rel = destCanonical.toPath().relativize(outFile.toPath())
                if (rel.toString().contains("..")) {
                    throw IllegalArgumentException("Zip entry escapes target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                    zis.closeEntry()
                    continue
                }

                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    var total = 0L
                    while (true) {
                        val read = zis.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > maxEntryBytes) {
                            throw SecurityException("Zip entry '${entry.name}' exceeds $maxEntryBytes bytes; possible zip bomb")
                        }
                        fos.write(buffer, 0, read)
                    }
                }
                zis.closeEntry()
            }
        }
    }
}
