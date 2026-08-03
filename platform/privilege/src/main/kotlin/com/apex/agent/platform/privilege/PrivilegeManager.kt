package com.apex.agent.platform.privilege

import kotlinx.coroutines.flow.StateFlow

/**
 * 权限管理器
 * 核心：Root ⊃ (Shizuku ∪ Accessibility)
 */
interface PrivilegeManager {
    /** Root是否可用 */
    val rootAvailable: StateFlow<Boolean>
    
    /** Shizuku是否可用 */
    val shizukuAvailable: StateFlow<Boolean>
    
    /** 无障碍是否可用 */
    val accessibilityAvailable: StateFlow<Boolean>
    
    /** 执行shell命令（自动选择Root或Shizuku）*/
    suspend fun executeShell(command: String, timeoutMs: Long = 30000): ShellResult
    
    /** 执行UI操作（自动选择无障碍或Root input命令）*/
    suspend fun executeUiAction(action: UiAction): UiResult
    
    /** 获取当前UI树 */
    suspend fun getUiTree(): UiTreeResult
    
    /** 截图 */
    suspend fun takeScreenshot(): ScreenshotResult
    
    /** 刷新权限状态 */
    suspend fun refreshStatus()
}

data class ShellResult(
    val success: Boolean,
    val output: String,
    val exitCode: Int,
    val executedVia: ExecutionVia  // ROOT / SHIZUKU / NONE
)

enum class ExecutionVia { ROOT, SHIZUKU, ACCESSIBILITY, NONE }

sealed interface UiAction {
    data class Click(val x: Int, val y: Int) : UiAction
    data class ClickNode(val nodeId: String) : UiAction
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Long = 300) : UiAction
    data class InputText(val text: String) : UiAction
    data class PressKey(val keyCode: Int) : UiAction
    data object Back : UiAction
    data object Home : UiAction
    data object Recents : UiAction
    data object OpenNotifications : UiAction
}

data class UiResult(val success: Boolean, val message: String = "")
data class UiTreeResult(val success: Boolean, val treeXml: String = "", val nodes: List<UiNode> = emptyList())
data class ScreenshotResult(val success: Boolean, val imageBytes: ByteArray? = null)

data class UiNode(
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: String,  // "[x1,y1][x2,y2]"
    val clickable: Boolean,
    val scrollable: Boolean,
    val children: List<UiNode> = emptyList()
)
