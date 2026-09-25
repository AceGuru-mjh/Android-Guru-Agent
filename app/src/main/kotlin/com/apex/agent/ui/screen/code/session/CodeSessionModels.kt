package com.apex.agent.ui.screen.code.session

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// 编码会话 UI 快照 —— 可序列化模型与纯函数工具（Issue #152）
//
// 分层定位：引擎侧上下文已由 CodeConversationMemory 自动持久化并恢复
// （其自有 baseDir 下的 ws_<id>.json，含工具调用配对的完整 API 级历史）；
// 本文件只负责「UI 现场」三样东西 —— 消息列表、todo 清单、最近编辑文件
// —— 在 Coding 模式进程被杀后回填界面。纯 Kotlin + kotlinx.serialization，
// 零 Android 依赖，可独立单测。
//
// 与 CodeChatMessage（Compose 展示模型）的关系：那边携带枚举 role、进程内
// AtomicLong id、isStreaming 瞬态，不适合直接落盘；这里改为字符串 role +
// 全默认值字段的扁平形态 —— 旧文件读新代码（缺字段走默认值）、新文件读旧
// 代码（ignoreUnknownKeys 跳过未知字段）双向兼容。与 UI 模型的互转映射
// 在 CodeSessionMappers.kt（避免本文件引入 Compose 依赖）。
// ─────────────────────────────────────────────────────────────────────────────

/** 快照内单条消息文本的字符上限（超出截断，防超大工具输出撑爆 JSON）。 */
const val CODE_SNAPSHOT_TEXT_LIMIT = 8000

/** 单工作区快照的消息条数上限（超出丢最旧，对齐长会话的上下文经济原则）。 */
const val CODE_SNAPSHOT_MAX_MESSAGES = 400

/** 截断标记后缀。 */
const val CODE_SNAPSHOT_TRUNCATION_SUFFIX = "…(截断)"

/**
 * 可持久化的聊天条目（CodeChatMessage 的落盘形态）。
 *
 * role 取值与 CodeChatMessage.Role 枚举的 name 一一对应（USER / ASSISTANT /
 * TOOL / SYSTEM）；isStreaming 瞬态不入快照（流式中的半截消息没有恢复价值，
 * 由映射层过滤）。
 */
@Serializable
data class StorableCodeMessage(
    /** USER | ASSISTANT | TOOL | SYSTEM（与 CodeChatMessage.Role.name 对应）。 */
    val role: String,
    /** 消息正文（写入前经 [truncateForSnapshot] 截断）。 */
    val text: String,
    /** 仅工具条目：工具名（恢复时重建工具卡标题）。 */
    val toolName: String? = null,
    /** 仅工具条目：执行是否成功。 */
    val toolSuccess: Boolean = true,
    /** 仅工具条目：耗时毫秒。 */
    val durationMs: Long = 0
)

/** 可持久化的待办条目（CodeTodoTool.Todo 的落盘形态，字段同名同义）。 */
@Serializable
data class StorableTodo(
    val content: String,
    /** pending | in_progress | completed | cancelled。 */
    val status: String,
    /** high | medium | low。 */
    val priority: String
)

/**
 * 单工作区的 UI 现场快照：消息列表 + todo 清单 + 最近编辑文件。
 *
 * 恢复语义（与引擎侧分工）：本快照只回填界面；引擎上下文、工作区激活、
 * 会话记忆均走各自既有恢复路径，互不替代。
 */
@Serializable
data class CodeSessionSnapshot(
    /** 工作区 id（文件名由 CodeSessionStore 按它清洗派生）。 */
    val workspaceId: String,
    val messages: List<StorableCodeMessage> = emptyList(),
    val todos: List<StorableTodo> = emptyList(),
    /** 最近一次编辑/激活的文件相对路径（无则 null）。 */
    val lastActiveFile: String? = null,
    /** 快照落盘时间戳（epoch 毫秒，调用方负责盖戳）。 */
    val updatedAt: Long = 0
)

// ── 纯函数工具（供映射层与调用方复用） ───────────────────────────────────────

/**
 * 截断超长文本：不超过 [limit] 原样返回；超出则取前 [limit] 字符并追加
 * 「…(截断)」后缀（后缀不计入上限，保证标记可见）。
 */
fun truncateForSnapshot(text: String, limit: Int = CODE_SNAPSHOT_TEXT_LIMIT): String =
    if (text.length <= limit) text
    else text.take(limit) + CODE_SNAPSHOT_TRUNCATION_SUFFIX

/**
 * 消息条数封顶：超出 [max] 丢最旧（保留最近的尾部），不超过则原样返回。
 * 语义与 CodeConversationMemory 的记忆上限一致 —— 长会话宁可丢头也不丢尾。
 */
fun List<StorableCodeMessage>.cappedForSnapshot(max: Int = CODE_SNAPSHOT_MAX_MESSAGES): List<StorableCodeMessage> =
    if (size <= max) this else drop(size - max)

/**
 * 角色字符串合法性校验：去空白、统一大写后命中已知角色集合则返回规范名，
 * 否则返回 null。已知集合与 CodeChatMessage.Role 枚举的 name 完全一致，
 * 是字符串与枚举互转的稳定契约（容错大小写与手改文件的笔误）。
 */
fun String.toKnownCodeRoleOrNull(): String? =
    trim().uppercase().takeIf { it in KNOWN_CODE_ROLES }

/**
 * 角色字符串容错降级：合法则返回规范名，未知值降级为 SYSTEM
 * （内容保住、只损失样式 —— 适合展示层兜底；需要「跳过」语义的恢复路径
 * 请用 [toKnownCodeRoleOrNull] 判空）。
 */
fun String.toRoleOrSystem(): String =
    toKnownCodeRoleOrNull() ?: ROLE_SYSTEM

/** 已知角色集合（USER / ASSISTANT / TOOL / SYSTEM）。 */
val KNOWN_CODE_ROLES: Set<String> = setOf("USER", "ASSISTANT", "TOOL", "SYSTEM")

/** SYSTEM 角色的规范名（降级兜底用）。 */
const val ROLE_SYSTEM = "SYSTEM"
