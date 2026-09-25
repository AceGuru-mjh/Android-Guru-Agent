package com.apex.agent.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 保险库页 ViewModel（#167）。
 *
 * 数据面：[entries] 直通 [VaultRepository.entriesFlow]（UI 面向**人类**，
 * 有权查看/编辑明文；Agent 侧只能经工具看脱敏快照）。
 *
 * 事件面：一次性操作结果用 [VaultEvent] 枚举上报，由 Screen 经
 * stringResource 映射 i18n 文案（VM 不持硬编码字符串）。
 */
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: VaultRepository
) : ViewModel() {

    /** 完整条目列表（含明文 —— 仅 UI 渲染层使用）。 */
    val entries: StateFlow<List<VaultEntry>> = repository.entriesFlow

    /** 一次性操作事件（UI 消费后调 [consumeEvent] 清空）。 */
    private val _event = MutableStateFlow<VaultEvent?>(null)
    val event: StateFlow<VaultEvent?> = _event.asStateFlow()

    /**
     * 新增 / 编辑保存。
     *
     * 校验：label 非空、secret 非空、label 不与其他条目冲突
     * （编辑自身保持原 label 合法）。编辑时沿用既有条目的 id 与
     * 用量统计；新增自动生成 id / createdAt，origin 恒为 HUMAN
     * （人工经 UI 储放）。
     */
    fun saveEntry(label: String, note: String, secret: String, editingId: String?) {
        val trimmedLabel = label.trim()
        when {
            trimmedLabel.isEmpty() -> { _event.value = VaultEvent.LABEL_EMPTY; return }
            secret.isEmpty() -> { _event.value = VaultEvent.SECRET_EMPTY; return }
            else -> {
                val conflict = repository.entriesFlow.value.any {
                    it.label == trimmedLabel && it.id != editingId
                }
                if (conflict) { _event.value = VaultEvent.LABEL_EXISTS; return }
            }
        }
        viewModelScope.launch {
            val entry = if (editingId != null) {
                val existing = repository.get(editingId)
                if (existing == null) {
                    _event.value = VaultEvent.ERROR
                    return@launch
                }
                // 人工经 UI 编辑 → 最后写入者是 HUMAN（与 vault_save 覆写时
                // 置 AGENT 对称，origin 恒指“当前密文的写入者”）。
                existing.copy(
                    label = trimmedLabel,
                    note = note.trim(),
                    secret = secret,
                    origin = VaultOrigin.HUMAN
                )
            } else {
                VaultRepository.newEntry(
                    label = trimmedLabel,
                    note = note.trim(),
                    secret = secret,
                    origin = VaultOrigin.HUMAN
                )
            }
            repository.save(entry)
            _event.value = VaultEvent.SAVED
        }
    }

    /** 删除条目（UI 已二次确认后调用）。 */
    fun deleteEntry(id: String) {
        viewModelScope.launch {
            val deleted = repository.delete(id)
            _event.value = if (deleted) VaultEvent.DELETED else VaultEvent.ERROR
        }
    }

    fun consumeEvent() {
        _event.value = null
    }
}

/** 一次性 UI 事件（Screen 映射 i18n 文案）。 */
enum class VaultEvent {
    SAVED,
    DELETED,
    LABEL_EMPTY,
    LABEL_EXISTS,
    SECRET_EMPTY,
    ERROR
}
