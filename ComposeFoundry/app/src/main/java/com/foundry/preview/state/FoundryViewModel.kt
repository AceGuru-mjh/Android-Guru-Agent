package com.foundry.preview.state

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.foundry.preview.dsl.UiDocument
import com.foundry.preview.dsl.UiParser
import com.foundry.preview.dsl.UiValidator
import com.foundry.preview.engine.DiagnosticsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FoundryViewModel : ViewModel() {

    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code.asStateFlow()

    private val _document = MutableStateFlow<UiDocument?>(null)
    val document: StateFlow<UiDocument?> = _document.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticsEngine())
    val diagnostics: StateFlow<DiagnosticsEngine> = _diagnostics.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(false)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _devicePreset = MutableStateFlow("Pixel 7")
    val devicePreset: StateFlow<String> = _devicePreset.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val undoStack = mutableListOf<String>()
    private val redoStack = mutableListOf<String>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun initializeWithSample(dsl: String) {
        if (_code.value.isEmpty()) {
            _code.value = dsl
        }
    }

    fun updateCode(newCode: String) {
        undoStack.add(_code.value)
        if (undoStack.size > 50) undoStack.removeAt(0)
        redoStack.clear()
        _code.value = newCode
    }

    fun render() {
        val engine = DiagnosticsEngine()
        val parser = UiParser()
        val validator = UiValidator()

        parser.parse(_code.value).fold(
            onSuccess = { doc ->
                engine.addAll(validator.validate(doc))
                if (!engine.hasErrors) {
                    _document.value = doc
                    _statusMessage.value = "Rendered successfully"
                } else {
                    _document.value = null
                    _statusMessage.value = "Render blocked: ${engine.errorCount} error(s)"
                }
            },
            onFailure = { e ->
                engine.addError("Parse error: ${e.message}")
                _document.value = null
                _statusMessage.value = "Parse failed"
            }
        )
        _diagnostics.value = engine
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(_code.value)
            _code.value = undoStack.removeAt(undoStack.lastIndex)
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(_code.value)
            _code.value = redoStack.removeAt(redoStack.lastIndex)
        }
    }

    fun toggleTheme() {
        _isDarkTheme.value = !_isDarkTheme.value
    }

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }

    fun setDevicePreset(preset: String) {
        _devicePreset.value = preset
    }

    fun getDeviceDimensions(): Pair<Int, Int> {
        return when (_devicePreset.value) {
            "Small Phone" -> 360 to 640
            "Pixel 7" -> 412 to 915
            "Pixel Tablet" -> 800 to 1280
            else -> 412 to 915
        }
    }

    fun insertComponent(dslSnippet: String) {
        undoStack.add(_code.value)
        if (undoStack.size > 50) undoStack.removeAt(0)
        redoStack.clear()

        val currentCode = _code.value
        val lastBracket = currentCode.lastIndexOf("]")
        if (lastBracket > 0) {
            val before = currentCode.substring(0, lastBracket)
            val after = currentCode.substring(lastBracket)
            val needsComma = before.trimEnd().let {
                it.endsWith("}") || it.endsWith("]") || it.endsWith("\"")
            }
            val separator = if (needsComma) ",\n" else "\n"
            _code.value = before + separator + dslSnippet + "\n" + after
            _statusMessage.value = "Component inserted"
        } else {
            _statusMessage.value = "Cannot insert: no children array found"
        }
    }

    fun importFromUri(context: Context, uri: Uri) {
        try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            if (content != null) {
                undoStack.add(_code.value)
                redoStack.clear()
                _code.value = content
                render()
                _statusMessage.value = "Imported successfully"
            }
        } catch (e: Exception) {
            _statusMessage.value = "Import failed: ${e.message}"
        }
    }

    fun exportToUri(context: Context, uri: Uri) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(_code.value.toByteArray(Charsets.UTF_8))
            }
            _statusMessage.value = "Exported successfully"
        } catch (e: Exception) {
            _statusMessage.value = "Export failed: ${e.message}"
        }
    }

    fun clearStatus() {
        _statusMessage.value = ""
    }
}
