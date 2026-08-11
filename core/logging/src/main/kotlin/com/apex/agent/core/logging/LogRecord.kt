package com.apex.agent.core.logging

/**
 * 一条结构化日志。
 *
 * 除了 [level]（多严重）与 [category]（哪类业务）两个主维度外，还携带
 * [tags]——一组自由字符串元数据标签。标签让调用方可以按任意自定义维度
 * 检索，例如 `tool:shell_execute`、`session:abc`、`http:429`，弥补固定枚举
 * 维度无法表达的细粒度场景。
 *
 * [approxBytes] 是写入缓冲时估算的字节占用，用于体积感知的淘汰策略。
 */
data class LogRecord(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val category: LogCategory,
    val source: String,
    val message: String,
    val tags: Set<String> = emptySet(),
    val throwable: Throwable? = null,
    val approxBytes: Int = estimateBytes(message, tags)
) {
    companion object {
        /** 粗略估算单条日志在内存缓冲中的字节开销（UTF-8 字符按 2 字节计 + 固定头）。 */
        fun estimateBytes(message: String, tags: Set<String>): Int {
            val msgBytes = message.length * 2
            val tagBytes = tags.sumOf { it.length * 2 + 4 }
            return 96 + msgBytes + tagBytes // 96 为对象头/字段基线
        }
    }

    /** 用于复制/导出的单行文本表示。 */
    fun toFlatString(): String {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(timestamp))
        val tagPart = if (tags.isEmpty()) "" else " ${tags.joinToString(" ") { "#$it" }}"
        val errPart = throwable?.let { " ${it.stackTraceToString().lines().first()}" } ?: ""
        return "[$ts] ${level.shortTag}/${category.shortCode} ${source}: ${message}${tagPart}${errPart}"
    }
}
