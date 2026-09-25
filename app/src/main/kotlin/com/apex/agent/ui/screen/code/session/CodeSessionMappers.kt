package com.apex.agent.ui.screen.code.session

import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.ui.screen.code.CodeChatMessage

// ─────────────────────────────────────────────────────────────────────────────
// 编码会话快照映射层（Issue #152）—— UI 模型与落盘模型的互转。
//
// 单独成文件的原因：CodeChatMessage 定义在 CodeUiState.kt（同包上层），该
// 文件连带 Compose 依赖（CodeWorkspace 等）；把互转集中在此，使
// CodeSessionModels / CodeSessionStore / 单测三者保持纯 JVM 可编译可测，
// 本文件仅随 app 模块编译（映射逻辑极薄，静态自查 + stub 树编译覆盖）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * UI 消息列表 → 落盘形态。入快照前做三道收敛：
 *
 * 1. 过滤 isStreaming = true 的条目 —— 流式中的半截消息没有恢复价值
 *    （引擎侧 ws 记忆里有权威全文，UI 层不必留残影）；
 * 2. 文本经 [truncateForSnapshot] 截断（默认 8000 字符 + 截断标记）；
 * 3. 条数经 [cappedForSnapshot] 封顶（默认 400，超出丢最旧）。
 */
fun List<CodeChatMessage>.toStorable(): List<StorableCodeMessage> =
    asSequence()
        .filterNot { it.isStreaming }
        .map { msg ->
            StorableCodeMessage(
                role = msg.role.name,
                text = truncateForSnapshot(msg.text),
                toolName = msg.toolName,
                toolSuccess = msg.toolSuccess,
                durationMs = msg.durationMs
            )
        }
        .toList()
        .cappedForSnapshot()

/**
 * 落盘消息 → UI 消息：id 由调用方传入起始值顺序递增（进程内 id 是
 * AtomicLong 现场值，无跨进程意义，恢复时统一换新号）。
 *
 * 容错：role 字符串无法识别（手改文件 / 未来版本新增角色）时跳过该条，
 * 不让单条脏数据炸掉整个恢复。
 *
 * CodeViewModel 接线要点（主控参考）：
 * - 恢复时以 nextId = 1 起步（新进程 idGen 从 0 重新计数）；
 * - 拿到结果后把 idGen 回拨到已用最大号，后续 incrementAndGet 才不碰撞：
 *   idGen.set(restored.lastOrNull()?.id ?: 0L)
 * - 恢复出的条目恒为 isStreaming = false（流态不该被持久化）。
 */
fun List<StorableCodeMessage>.withFreshIds(nextId: Long): List<CodeChatMessage> {
    val restored = ArrayList<CodeChatMessage>(size)
    var id = nextId
    for (msg in this) {
        val roleName = msg.role.toKnownCodeRoleOrNull() ?: continue
        restored += CodeChatMessage(
            id = id,
            role = CodeChatMessage.Role.valueOf(roleName),
            text = msg.text,
            toolName = msg.toolName,
            toolSuccess = msg.toolSuccess,
            durationMs = msg.durationMs,
            isStreaming = false
        )
        id++
    }
    return restored
}

/**
 * UI 待办清单 → 落盘形态（字段同名直拷，CodeTodoTool.Todo 本身就是纯数据）。
 *
 * 消息版 toStorable 泛型擦除后 JVM 签名相同（均收 List 返 List），靠
 * JvmName 区分 —— Kotlin 调用方仍写 todos.toStorable()，不受影响。
 */
@JvmName("toStorableTodos")
fun List<CodeTodoTool.Todo>.toStorable(): List<StorableTodo> =
    map { StorableTodo(content = it.content, status = it.status, priority = it.priority) }

/** 落盘待办 → UI 待办清单（CodeTodoTool 是全量覆盖式更新，直接整体回填）。 */
fun List<StorableTodo>.toCodeTodos(): List<CodeTodoTool.Todo> =
    map { CodeTodoTool.Todo(content = it.content, status = it.status, priority = it.priority) }
