package com.apex.agent.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * ═══ 加密剪切板金库（Issue #167）—— 数据模型 ═══
 *
 * 安全红线：[VaultEntry.secret] 只存在于存储层与进程内存，
 * **永不**进入任何工具输出 / 日志 / LLM 上下文。面向 Agent 的
 * 列表视图一律使用脱敏快照 [VaultSnapshot]（仅标签与备注可见）。
 */
@Serializable
data class VaultEntry(
    /** 条目 id（uuid），跨存储稳定。 */
    val id: String,
    /** 唯一标签（如 "github-token"）—— Agent 可见的唯一定位键。 */
    val label: String,
    /** 备注（Agent 可见，用于描述用途，不得写入敏感信息）。 */
    val note: String = "",
    /** 密文/明文仅在存储层（EncryptedSharedPreferences 已整库加密）。 */
    val secret: String,
    /** 条目来源：人工经 UI 储放 / Agent 经工具写入。 */
    val origin: VaultOrigin,
    /** 创建时间（epoch ms）。 */
    val createdAt: Long,
    /** 最近一次被 vault_paste 使用的时刻（epoch ms；0 = 从未使用）。 */
    val lastUsedAt: Long = 0,
    /** 累计使用次数（vault_paste 成功投递 +1）。 */
    val usageCount: Int = 0
)

/** 条目来源。 */
enum class VaultOrigin { HUMAN, AGENT }

/**
 * 脱敏快照 —— `vault_list` 与 UI 统计的唯一视图：
 * id/label/note/origin/时间戳/用量/密文长度，**绝不含 secret**。
 */
@Serializable
data class VaultSnapshot(
    val id: String,
    val label: String,
    val note: String,
    val origin: VaultOrigin,
    val createdAt: Long,
    val lastUsedAt: Long,
    val usageCount: Int,
    /** 密文长度（字符数）—— 仅供 UI 展示“内容规模”，无法反推内容。 */
    @SerialName("secretLength") val secretLength: Int
) {
    companion object {
        /** 从完整条目构造脱敏视图（secret 在此被永久丢弃）。 */
        fun of(entry: VaultEntry): VaultSnapshot = VaultSnapshot(
            id = entry.id,
            label = entry.label,
            note = entry.note,
            origin = entry.origin,
            createdAt = entry.createdAt,
            lastUsedAt = entry.lastUsedAt,
            usageCount = entry.usageCount,
            secretLength = entry.secret.length
        )
    }
}

/** 金库专用 Json 实例：容忍未知字段（前向兼容），显式编码默认值（往返无损）。 */
val VaultJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 条目列表的序列化器（存储层 JSON 数组 ↔ List<VaultEntry>）。 */
val VaultEntryListSerializer = ListSerializer(VaultEntry.serializer())
