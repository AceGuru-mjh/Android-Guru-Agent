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

    /** 默认全部条目解压后的累计字节数上限（256 MB），防止"每个文件都刚好卡在
     *  单文件上限以下、但累计 GB 级"的 zip bomb（10 000 × 49 MB ≈ 490 GB）。 */
    const val DEFAULT_MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024

    /**
     * 将 zip 解压到目标目录（目标目录会被创建）。
     * @throws IllegalArgumentException 任一条目试图跳出目标目录（路径穿越）
     * @throws SecurityException 超出文件数或大小上限（疑似 zip bomb）
     */
    fun extract(
        zipFile: File,
        destinationDir: File,
        maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
    ) {
        val destCanonical = destinationDir.canonicalFile
        destinationDir.mkdirs()

        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            val buffer = ByteArray(64 * 1024)
            var entries = 0
            var totalBytes = 0L

            while (true) {
                val entry = zis.nextEntry ?: break

                // Check the entry-count limit BEFORE incrementing: previously
                // `++entries > maxEntries` allowed maxEntries+1 nextEntry() calls
                // before throwing, which is needlessly permissive and confusing.
                if (entries >= maxEntries) {
                    throw SecurityException("Zip entry count exceeds limit ($maxEntries); possible zip bomb")
                }
                entries++

                // Zip-slip / absolute-path defense — explicit fast-fail. The
                // canonicalization relativize check below still catches anything
                // that slips past this (e.g. symlink escape), but rejecting the
                // obvious cases up-front is faster and easier to audit.
                if (entry.name.startsWith("/") || entry.name.contains("..")) {
                    throw IllegalArgumentException(
                        "Zip entry escapes target dir (absolute or contains '..'): ${entry.name}"
                    )
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
                        // Running total of uncompressed bytes across all entries —
                        // stops a zip bomb of many mid-size entries (10 000 × 49 MB).
                        totalBytes += read
                        if (totalBytes > maxTotalBytes) {
                            throw SecurityException(
                                "Zip uncompressed total exceeds limit ($maxTotalBytes bytes); possible zip bomb"
                            )
                        }
                        fos.write(buffer, 0, read)
                    }
                }
                zis.closeEntry()
            }
        }
    }
}
