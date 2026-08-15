package com.foundry.preview.engine

import com.foundry.preview.dsl.Diagnostic
import com.foundry.preview.dsl.DiagnosticLevel

class DiagnosticsEngine {
    private val _diagnostics = mutableListOf<Diagnostic>()
    val diagnostics: List<Diagnostic> get() = _diagnostics.toList()

    val errorCount: Int get() = _diagnostics.count { it.level == DiagnosticLevel.ERROR }
    val warningCount: Int get() = _diagnostics.count { it.level == DiagnosticLevel.WARNING }
    val infoCount: Int get() = _diagnostics.count { it.level == DiagnosticLevel.INFO }

    val hasErrors: Boolean get() = errorCount > 0
    val hasWarnings: Boolean get() = warningCount > 0

    fun addError(message: String, path: String = "root") {
        _diagnostics.add(Diagnostic(DiagnosticLevel.ERROR, path, message))
    }

    fun addWarning(message: String, path: String = "root") {
        _diagnostics.add(Diagnostic(DiagnosticLevel.WARNING, path, message))
    }

    fun addInfo(message: String, path: String = "root") {
        _diagnostics.add(Diagnostic(DiagnosticLevel.INFO, path, message))
    }

    fun addAll(diagnostics: List<Diagnostic>) {
        _diagnostics.addAll(diagnostics)
    }

    fun clear() {
        _diagnostics.clear()
    }
}
