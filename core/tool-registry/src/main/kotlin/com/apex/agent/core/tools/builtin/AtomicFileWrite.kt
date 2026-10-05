package com.apex.agent.core.tools.builtin

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File

/**
 * #258：文件工具原子写（AGENTS「持久化一律 tmp+renameTo」纪律在工具层的落地）。
 *
 * write_file / edit_file 的最终落盘此前是直接 writeText 覆盖目标 —— 写一半
 * 崩溃/断电/进程被杀会留下**截断的用户文件**（源码/笔记被截掉且无备份可回）。
 * 本助手改为：同目录写 `.tmp_<name>` 临时文件 → renameTo 原子替换。
 *
 * 细节：
 * - 同目录 tmp 与目标几乎必然同一文件系统（rename 原子性前提）；
 * - renameTo 返回 false（个别 ROM / 跨挂载点）→ 回退直接写目标并记 WARN
 *   日志（宁可承担截断风险也不让写入彻底失败）；
 * - 写 tmp 前先删旧 tmp（上次失败残留），避免脏内容被误当新写入；
 * - tmp 写入抛出的 IO 异常原样上抛（磁盘满/权限由调用方统一折叠为错误输出）。
 */
object AtomicFileWrite {

    /**
     * 原子写入全文。
     *
     * @return true = 经 tmp+rename 原子落盘；false = rename 失败已回退直写目标
     */
    fun writeText(file: File, content: String): Boolean {
        val dir = file.parentFile
        if (dir == null) {
            // 无父目录（根路径等奇异形态）：没有同目录 tmp 可言，直接写。
            file.writeText(content)
            return false
        }
        val tmp = File(dir, ".tmp_${file.name}")
        if (tmp.exists()) tmp.delete()
        tmp.writeText(content)
        if (tmp.renameTo(file)) return true
        // rename 失败（跨文件系统/目标被占用）：回退直写 + 留痕，tmp 尽力清理。
        AppLogger.instance.warn(
            LogCategory.TOOL, "AtomicFileWrite",
            "atomic rename failed for ${file.path}, falling back to direct write"
        )
        file.writeText(content)
        if (!tmp.delete() && tmp.exists()) {
            AppLogger.instance.warn(
                LogCategory.TOOL, "AtomicFileWrite",
                "orphan tmp file left behind: ${tmp.path}"
            )
        }
        return false
    }
}
