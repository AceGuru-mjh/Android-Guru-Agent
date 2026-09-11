package com.apex.agent.platform

import android.view.accessibility.AccessibilityEvent
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.logging.LogLevel
import com.apex.agent.core.tools.ToolEnvironmentState
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.accessibility.ApexAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # Tool System v3 — 环境状态桥（app 层遥测 → core 门控）
 *
 * [ToolEnvironmentState] 是 core 层的纯数据快照；本类是把 Android 世界
 * 的真实信号灌进去的唯一入口（Mobile-Agent 环境态门控范式的设备侧）：
 *
 * - **accessibility_ready**：收集 [PrivilegeManager.accessibilityAvailable]
 *   StateFlow —— 服务连接/断开即时反映；
 * - **keyboard_active**：注册 [ApexAccessibilityService] 事件监听，
 *   TYPE_VIEW_FOCUSED 且事件源可编辑（EditText 焦点）→ 置 true 并带
 *   45 秒 TTL。事件驱动信号天然会过期（用户可能随时收起键盘），
 *   TTL 到期回退 unknown（fail-open），绝不误报 false。
 *
 * 未知态语义见 [ToolEnvironmentState]：门控对未知放行 —— 桥没跑起来
 * （老进程升级、服务未启用）时工具行为与 v1 完全一致，零回归。
 *
 * 幂等：[start] 可安全多次调用（Hilt 单例 + App 重建场景）。
 */
@Singleton
class EnvironmentStateUpdater @Inject constructor(
    private val privilegeManager: PrivilegeManager,
    private val environmentState: ToolEnvironmentState
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    /** 无障碍事件监听（注册/反注册时切换，主线程回调直达）。 */
    private val keyboardListener: (AccessibilityEvent) -> Unit = { event ->
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && isEditableEvent(event)) {
            // TTL：焦点事件是"刚刚发生"的信号，45 秒后视为未知而非过期否定。
            environmentState.setWithTtl(
                ToolEnvironmentState.Flags.KEYBOARD_ACTIVE,
                true,
                ttlMs = KEYBOARD_TTL_MS
            )
        }
    }

    /** 启动遥测桥（App onCreate 调用一次；重复调用为 no-op）。 */
    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }

        scope.launch {
            privilegeManager.accessibilityAvailable.collect { available ->
                environmentState.set(ToolEnvironmentState.Flags.ACCESSIBILITY_READY, available)
                if (available) {
                    runCatching {
                        ApexAccessibilityService.instance
                            ?.addEventListener(keyboardListener)
                    }.onFailure {
                        AppLogger.instance.fromAndroid(
                            level = LogLevel.WARN,
                            category = LogCategory.SYSTEM,
                            source = "EnvironmentStateUpdater",
                            message = "keyboard listener registration failed: ${it.message}",
                            tags = arrayOf("env-state")
                        )
                    }
                } else {
                    // 服务断开：键盘遥测一并失效（否则 TTL 之外的旧值
                    // 会让门控放行一个已经不可能成立的前置条件）。
                    environmentState.clear(ToolEnvironmentState.Flags.KEYBOARD_ACTIVE)
                    runCatching {
                        ApexAccessibilityService.instance
                            ?.removeEventListener(keyboardListener)
                    }
                }
            }
        }

        AppLogger.instance.fromAndroid(
            level = LogLevel.INFO,
            category = LogCategory.SYSTEM,
            source = "EnvironmentStateUpdater",
            message = "environment telemetry bridge started",
            tags = arrayOf("env-state", "tool-v3")
        )
    }

    /** 停止并复位（测试用；生产进程随生命周期销毁）。 */
    fun stop() {
        scope.cancel()
        started = false
        environmentState.clearAll()
    }

    /** 焦点事件是否落在可编辑节点上（源节点缺失时退回类名启发）。 */
    private fun isEditableEvent(event: AccessibilityEvent): Boolean {
        event.source?.let { node ->
            val editable = node.isEditable
            node.recycle()
            if (editable) return true
        }
        val className = event.className?.toString() ?: return false
        return className.contains("EditText") || className.contains("AutoCompleteTextView")
    }

    private companion object {
        /** 键盘活跃信号的保鲜窗口：超过即回退 unknown（fail-open）。 */
        const val KEYBOARD_TTL_MS: Long = 45_000L
    }
}
