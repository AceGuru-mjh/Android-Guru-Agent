package com.apex.agent.core.logging

/**
 * 日志严重级别，与 Android 原生 Log 级别对齐，便于从系统日志平滑迁移。
 *
 * 每个级别带一个 ARGB 颜色，供日志查看器做分级着色；`ordinal` 数值越大代表
 * 越严重，过滤时可直接用 `level.ordinal >= threshold.ordinal` 做门槛筛选。
 */
enum class LogLevel(
    val priority: Int,
    val shortTag: String,
    val colorArgb: Int
) {
    VERBOSE(2, "V", 0xFF9E9E9E.toInt()),
    DEBUG(3, "D", 0xFF64B5F6.toInt()),
    INFO(4, "I", 0xFF81C784.toInt()),
    WARN(5, "W", 0xFFFFB74D.toInt()),
    ERROR(6, "E", 0xFFE57373.toInt()),
    FATAL(7, "F", 0xFFBA68C8.toInt()),
    SILENT(8, "S", 0xFF000000.toInt());

    /** 是否达到给定门槛（含本级别）。 */
    fun atLeast(threshold: LogLevel): Boolean = ordinal >= threshold.ordinal

    companion object {
        /** 由 Android Log 的 int 级别（Log.VERBOSE..Log.ASSERT）映射到本枚举。 */
        fun fromAndroidPriority(priority: Int): LogLevel = when (priority) {
            in Int.MIN_VALUE..2 -> VERBOSE
            3 -> DEBUG
            4 -> INFO
            5 -> WARN
            6 -> ERROR
            7 -> FATAL
            else -> SILENT
        }
    }
}
