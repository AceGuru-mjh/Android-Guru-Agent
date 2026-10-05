package com.apex.agent.ui.screen.agent

import com.apex.agent.R
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.loop.LoopConfig
import com.apex.agent.loop.LoopScheduler
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// S2 — LOOP 循环控制（AgentChatViewModel 的 internal 扩展，God-file 预算拆分）
//
// 组织方式同 AgentChatModeController.kt / AgentChatHistoryController.kt：
// 依赖成员已开放 internal，调用点（init 的 setupLoopController / Screen 的
// startLoop 等）零感知。VM 本体只加了 loopScheduler 构造参数 + 一行 init 接线。
//
// 职责边界：dueEvents 收集与会话注入、activeLoop 状态同步（含会话恢复回填）、
// 调度器心跳复活、首条消息拦截转配置、手动触发。引擎侧提示词（LOOP 模式段）
// 与工具计划已在核心层就绪（Task 2 提交），本文件不碰引擎配置。
//
// 设计决策（与 LoopScheduler KDoc 对齐）：
// - 切走 LOOP 模式不停止循环：activeLoop 独立于 mode 存活；
//   mode != LOOP 时到期的轮次只记 runLog，App 在后台且 notifyOnRun 时补通知。
// - 会话匹配 = sessionTag 相同 或 事件属于当前 activeLoop（恢复/换会话双通道）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * LOOP 控制接线（VM init 调用一次）：
 * 1. 调度器启动 + 60s 心跳复活（服务宿主被停后 VM 接管驱动）；
 * 2. store 状态流 → activeLoop 同步（轮次推进 / 终态清除 / 会话恢复回填）；
 * 3. dueEvents 收集 → 会话注入。
 */
internal fun AgentChatViewModel.setupLoopController() {
    // 1) 心跳：start() 幂等 —— 首跳立即执行，之后每 60s 复活一次
    //    （ApexCoreService.onDestroy stop 过调度器后，会话屏还活着就接管驱动）。
    viewModelScope.launch {
        while (isActive) {
            loopScheduler.start()
            delay(HEARTBEAT_MS)
        }
    }

    // 2) activeLoop 状态同步：store 是唯一真源。
    //    - activeLoop 非空：跟随同 id 循环（轮次推进 / 停用 → 清除）；
    //    - activeLoop 为空：按当前会话 tag 回填（App 重启 / 会话恢复自愈）。
    viewModelScope.launch {
        loopScheduler.schedules.collect { state ->
            val current = _uiState.value.activeLoop
            val next: LoopConfig? = when {
                current != null -> state.loops.firstOrNull { it.id == current.id }?.takeIf { it.enabled }
                else -> state.loops.firstOrNull { it.enabled && it.sessionTag == currentSessionTag() }
            }
            if (next != _uiState.value.activeLoop) {
                _uiState.update { it.copy(activeLoop = next) }
            }
        }
    }

    // 3) 到期轮次收集（调度器 persist-then-emit：事件载荷已标记并落盘）。
    viewModelScope.launch {
        loopScheduler.dueEvents.collect { config -> onLoopDue(config) }
    }
}

/** 当前会话标识（历史库会话 id；新会话未归档时用 "default"）。 */
internal fun AgentChatViewModel.currentSessionTag(): String =
    currentHistorySessionId ?: "default"

/**
 * LOOP 模式下用户第一次发送消息：拦截并转循环配置 Sheet（Screen 据返回值
 * 打开 LoopSetupSheet 预填该文本），不直接发送。返回 true = 已拦截。
 *
 * 斜杠指令不拦截（走既有管线）；会话已有活跃循环但 UI 态丢失（重启/恢复）
 * 时先回填 activeLoop 并放行正常发送，避免重复开配置。
 */
internal fun AgentChatViewModel.maybeInterceptLoopFirstSend(text: String): Boolean {
    val state = _uiState.value
    if (state.mode != AgentMode.LOOP || state.activeLoop != null) return false
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("/")) return false
    rehydrateActiveLoopForSession()
    return _uiState.value.activeLoop == null
}

/** 启动一条循环（Sheet 保存入口）：upsert + 调度器保活 + UI 态激活 + 清草稿。 */
internal fun AgentChatViewModel.startLoop(config: LoopConfig) {
    loopScheduler.upsert(config)
    loopScheduler.start()
    _uiState.update { it.copy(activeLoop = config) }
    // 拦截路径的草稿已成为循环提示词（Sheet 持有），发送框清空避免双份
    updateInputText("")
}

/** 停止当前循环（状态卡「停止」按钮）：停用保留配置与 runLog，UI 态清除。 */
internal fun AgentChatViewModel.stopLoop() {
    val id = _uiState.value.activeLoop?.id ?: return
    loopScheduler.setEnabled(id, false)
    _uiState.update { it.copy(activeLoop = null) }
}

/**
 * 立即触发一轮（状态卡「立即触发」按钮）：markRunNow 落盘后**直接注入**
 * 会话（不走 dueEvents —— 用户显式点击不该被 mode 过滤吞掉）。
 */
internal fun AgentChatViewModel.triggerLoopNow() {
    val config = _uiState.value.activeLoop ?: return
    if (_uiState.value.isLoading) return
    viewModelScope.launch {
        val marked = loopScheduler.markRunNow(config.id) ?: return@launch
        _uiState.update { it.copy(activeLoop = marked.takeIf { it.enabled }) }
        submitLoopTurn(loopTurnText(marked))
    }
}

/** 状态卡倒计时数据源（INTERVAL/CRON → 相对倒计时；ONCE → 绝对时刻）。 */
internal fun AgentChatViewModel.loopNextRunAt(config: LoopConfig): Long =
    loopScheduler.nextRunAt(config, System.currentTimeMillis())

/** 轮次注入文本：「[loop 第N轮] 提示词」（runsDone 已是标记后的值）。 */
private fun loopTurnText(config: LoopConfig): String =
    "[loop 第${config.runsDone}轮] ${config.prompt}"

/**
 * dueEvent 处置：会话匹配且 LOOP 模式 → 注入；否则记账 + 后台通知。
 * 终轮（标记后 enabled=false）也注入 —— runLog 已记这一轮，不注入即幽灵轮。
 */
private fun AgentChatViewModel.onLoopDue(config: LoopConfig) {
    val state = _uiState.value
    val sessionMatch = config.sessionTag == currentSessionTag() ||
        state.activeLoop?.id == config.id
    if (state.mode == AgentMode.LOOP && sessionMatch) {
        // activeLoop 跟随（终轮置 null → 状态卡消失）
        _uiState.update { it.copy(activeLoop = config.takeIf { it.enabled }) }
        if (state.isLoading) {
            // 上一轮未跑完：本轮跳过（调度器已记账触发；诚实告知用户）
            _uiState.update { s ->
                s.copy(messages = s.messages + AgentUiMessage.System(str(R.string.loop_skip_busy)))
            }
        } else {
            submitLoopTurn(loopTurnText(config))
        }
    } else {
        // 模式切走 / 会话不匹配：循环继续跑（runLog 已在调度器侧记账）；
        // 用户没在屏幕前且开了通知 → 静默补一条「本轮已跳过」。
        if (config.notifyOnRun && !foregroundTracker.isForeground) {
            notifications.notifyGeneral(
                context,
                str(R.string.loop_notif_bg_title),
                str(R.string.loop_notif_bg_text)
            )
        }
        if (!config.enabled && state.activeLoop?.id == config.id) {
            _uiState.update { it.copy(activeLoop = null) }
        }
    }
}

/**
 * 循环轮次注入 —— 与 sendMessage 同一条引擎管线（taskController.cancel →
 * executeNormalMessage），但不吞用户草稿/附件/chip（sendMessage 会清空它们，
 * 循环轮次不该替用户做输入区清洁）。
 */
private fun AgentChatViewModel.submitLoopTurn(text: String) {
    currentJob?.cancel()
    finishActiveBanner()
    currentJob = viewModelScope.launch {
        // P0 同款：先等在途任务取消完成再执行（TaskRuntime 互斥锁串行化）
        taskController.cancel()
        executeNormalMessage(text, emptyList())
    }
}

/** 会话 tag 回填（store 快照同步读；找不到即 no-op）。 */
private fun AgentChatViewModel.rehydrateActiveLoopForSession() {
    if (_uiState.value.activeLoop != null) return
    val tag = currentSessionTag()
    val found = loopScheduler.schedules.value.loops.firstOrNull { it.enabled && it.sessionTag == tag }
    if (found != null) {
        _uiState.update { it.copy(activeLoop = found) }
    }
}

private const val HEARTBEAT_MS: Long = 60_000L
