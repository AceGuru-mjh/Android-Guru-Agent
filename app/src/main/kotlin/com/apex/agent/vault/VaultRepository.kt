package com.apex.agent.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * ═══ 金库仓库（#167 核心，纯 Kotlin 可 JVM 单测）═══
 *
 * 职责：
 * - 持有全部条目（[VaultEntry]，含 secret）的内存快照 + [VaultStore] 持久化；
 * - CRUD：label 唯一 —— 冲突时**更新**既有条目（保留 id / 创建时间 / 用量统计）；
 * - 对外只提供两类读视图：
 *   - UI（人类）：[entriesFlow]（完整条目，人类有权看明文）；
 *   - Agent：[snapshots]（[VaultSnapshot] 脱敏快照，secret 永不出库）；
 * - 与 [SecretRedactor] 联动：每次写操作后全量重建登记表，
 *   保证删除/覆写后的旧密钥立即退出脱敏清单（登记表与条目集恒一致）。
 *
 * 线程安全：所有状态读写收敛到 [lock] 互斥（写频极低：人工储放 + 偶发 paste）；
 * **所有写操作立即同步持久化**（store.saveRaw 内部 commit），返回即落盘。
 *
 * @param store 持久化边界（Android = [EncryptedPrefsVaultStore]，测试 = [InMemoryVaultStore]）
 * @param redactor 全局脱敏器（DI 单例；默认自建便于纯 JVM 测试）
 */
class VaultRepository(
    private val store: VaultStore,
    private val redactor: SecretRedactor = SecretRedactor()
) {

    private val lock = Any()

    private val _entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entriesFlow: StateFlow<List<VaultEntry>> = _entries.asStateFlow()

    /** 暴露给执行器装饰层的全局脱敏器（与仓库登记表同源）。 */
    val secretRedactor: SecretRedactor get() = redactor

    init {
        synchronized(lock) {
            _entries.value = loadFromStore()
            resyncRedactor()
        }
    }

    // ───────────────────────── 读 ─────────────────────────

    /** 脱敏列表（vault_list / UI 统计专用，绝不含 secret）。 */
    fun snapshots(): List<VaultSnapshot> =
        synchronized(lock) { _entries.value.map(VaultSnapshot::of) }

    fun get(id: String): VaultEntry? =
        synchronized(lock) { _entries.value.firstOrNull { it.id == id } }

    fun getByLabel(label: String): VaultEntry? =
        synchronized(lock) { _entries.value.firstOrNull { it.label == label } }

    /**
     * 仅内部使用（vault_paste 直投 / UI 显示）：按标签取回明文。
     * 返回值不得进入任何工具输出或日志。
     */
    fun resolveSecret(label: String): String? = getByLabel(label)?.secret

    fun entryCount(): Int = synchronized(lock) { _entries.value.size }

    // ───────────────────────── 写 ─────────────────────────

    /**
     * 新增或更新条目（upsert 语义）：
     * - 同 id 或同 label → 更新（label 冲突时保留既有条目的
     *   id/createdAt/lastUsedAt/usageCount，仅覆写 label/note/secret/origin）；
     * - 全新条目 → 追加。
     *
     * @return 实际落库的条目（含合并后的字段）。
     */
    fun save(entry: VaultEntry): VaultEntry = synchronized(lock) {
        val existing = _entries.value.firstOrNull { it.id == entry.id || it.label == entry.label }
        val merged = if (existing != null && existing.id != entry.id) {
            entry.copy(
                id = existing.id,
                createdAt = existing.createdAt,
                lastUsedAt = existing.lastUsedAt,
                usageCount = existing.usageCount
            )
        } else if (existing != null) {
            // 同 id 编辑：保留创建时刻与用量统计，其余字段以本次提交为准。
            entry.copy(createdAt = existing.createdAt, lastUsedAt = existing.lastUsedAt, usageCount = existing.usageCount)
        } else {
            entry
        }
        commit(_entries.value.filter { it.id != merged.id && it.label != merged.label } + merged)
        merged
    }

    /** 按 id 删除。@return 是否真的删除了条目。 */
    fun delete(id: String): Boolean = synchronized(lock) {
        val target = _entries.value.firstOrNull { it.id == id } ?: return false
        commit(_entries.value.filter { it.id != id })
        true
    }

    /** 按标签删除（vault_delete 工具入口）。@return 是否真的删除了条目。 */
    fun deleteByLabel(label: String): Boolean = synchronized(lock) {
        val target = _entries.value.firstOrNull { it.label == label } ?: return false
        commit(_entries.value.filter { it.label != label })
        true
    }

    /**
     * 记录一次成功使用（vault_paste 投递成功后调用）：
     * usageCount+1、lastUsedAt=now，立即持久化。
     * suspend 仅为调用侧调度自由（仓库内部不挂起，任意调度器安全）。
     */
    suspend fun recordUsage(id: String) {
        synchronized(lock) {
            val entry = _entries.value.firstOrNull { it.id == id } ?: return
            val updated = entry.copy(
                usageCount = entry.usageCount + 1,
                lastUsedAt = System.currentTimeMillis()
            )
            commit(_entries.value.map { if (it.id == id) updated else it })
        }
    }

    // ──────────────────────── 内部 ────────────────────────

    /** 应用新条目集：更新内存快照 → 持久化 → 重建脱敏登记表。 */
    private fun commit(next: List<VaultEntry>) {
        _entries.value = next
        val json = try {
            VaultJson.encodeToString(VaultEntryListSerializer, next)
        } catch (e: Exception) {
            // 序列化失败几乎不可能（纯 data class）；防御性兜底为空库，
            // 绝不让异常带着密钥栈帧冒泡到工具层。
            "[]"
        }
        store.saveRaw(json)
        resyncRedactor()
    }

    /** 登记表与条目集强制对齐（处理密钥复用/覆写等共享值边界）。 */
    private fun resyncRedactor() {
        redactor.clear()
        redactor.register(_entries.value.map { it.secret })
    }

    /** 从存储层加载；坏数据（手改 XML / 半写文件）兜底为空库而不是崩溃。 */
    private fun loadFromStore(): List<VaultEntry> = try {
        val raw = store.loadRaw()
        if (raw.isBlank()) emptyList()
        else VaultJson.decodeFromString(VaultEntryListSerializer, raw)
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        /** 新条目工厂（id/createdAt 自动生成）。 */
        fun newEntry(
            label: String,
            note: String,
            secret: String,
            origin: VaultOrigin
        ): VaultEntry = VaultEntry(
            id = UUID.randomUUID().toString(),
            label = label,
            note = note,
            secret = secret,
            origin = origin,
            createdAt = System.currentTimeMillis()
        )
    }
}
