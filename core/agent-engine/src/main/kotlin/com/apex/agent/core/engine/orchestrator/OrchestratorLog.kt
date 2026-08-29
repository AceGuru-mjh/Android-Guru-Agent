package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.logging.LogLevel

/**
 * Logging facade for the orchestrator subsystem.
 *
 * Single place that adapts [LogLevel] to the [AppLogger] singleton. Extracted
 * from [DefaultTaskOrchestrator] (and shared with the batch execution engine)
 * so every orchestrator component logs with one consistent
 * `[Orchestrator] <message>` format.
 *
 * Logging is strictly best-effort: a broken logger must never take down the
 * agent loop, hence the swallowed [Throwable].
 */
internal object OrchestratorLog {

    private const val SOURCE = "Orchestrator"

    fun log(level: LogLevel, message: String) {
        try {
            val msg = "[$SOURCE] $message"
            val category = LogCategory.ENGINE
            when (level) {
                LogLevel.VERBOSE -> AppLogger.instance.verbose(category, SOURCE, msg)
                LogLevel.DEBUG -> AppLogger.instance.debug(category, SOURCE, msg)
                LogLevel.INFO -> AppLogger.instance.info(category, SOURCE, msg)
                LogLevel.WARN -> AppLogger.instance.warn(category, SOURCE, msg)
                LogLevel.ERROR -> AppLogger.instance.error(category, SOURCE, msg, null)
                LogLevel.FATAL -> AppLogger.instance.fatal(category, SOURCE, msg, null)
                LogLevel.SILENT -> { /* no-op */ }
            }
        } catch (e: Throwable) {
            // Logging is best-effort — swallow to avoid breaking the orchestrator loop
        }
    }
}
