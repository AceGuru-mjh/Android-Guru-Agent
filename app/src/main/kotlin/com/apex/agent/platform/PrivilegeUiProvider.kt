package com.apex.agent.platform

import android.content.Context
import com.apex.agent.core.tools.builtin.GestureAction
import com.apex.agent.core.tools.builtin.UiInteractionProvider
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.UiAction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 PrivilegeManager 的 UiInteractionProvider 实现。
 *
 * 当 AccessibilityService 就绪时，通过 AccessibilityService 执行语义手势
 * 和结构化的 UI 树捕获，代替原始的 input 命令。
 */
@Singleton
class PrivilegeUiProvider @Inject constructor(
    private val privilegeManager: PrivilegeManager,
    @ApplicationContext private val context: Context
) : UiInteractionProvider {

    override val isAvailable: Boolean
        get() = privilegeManager.accessibilityAvailable.value

    override suspend fun performGesture(action: GestureAction): String {
        val uiAction = when (action) {
            is GestureAction.Tap -> {
                UiAction.Click(action.x, action.y)
            }
            is GestureAction.LongPress -> {
                // 长按通过 swipe with same coords + long duration 模拟
                UiAction.Swipe(action.x, action.y, action.x, action.y, action.durationMs.toLong())
            }
            is GestureAction.Swipe -> {
                UiAction.Swipe(action.x1, action.y1, action.x2, action.y2, action.durationMs.toLong())
            }
            is GestureAction.DirectionalScroll -> {
                val metrics = getScreenMetrics()
                val w = metrics.first
                val h = metrics.second
                val (x1, y1, x2, y2) = directionToCoords(action.direction, w, h)
                UiAction.Swipe(x1, y1, x2, y2, action.durationMs.toLong())
            }
            is GestureAction.Back -> UiAction.Back
            is GestureAction.Home -> UiAction.Home
        }

        val result = privilegeManager.executeUiAction(uiAction)
        return if (result.success) {
            "OK: ${describeAction(action)}"
        } else {
            "Error: ${result.message}"
        }
    }

    override suspend fun dumpUiTree(maxDepth: Int): String {
        val result = privilegeManager.getUiTree()
        if (!result.success || result.nodes.isEmpty()) return ""

        val sb = StringBuilder(4096)
        sb.appendLine("=== UI Tree (${result.nodes.size} root nodes) ===")
        for (root in result.nodes.take(20)) {
            dumpNode(sb, root, depth = 0, maxDepth = maxDepth)
        }
        return sb.toString()
    }

    private fun dumpNode(
        sb: StringBuilder,
        node: com.apex.agent.platform.privilege.UiNode,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return

        val indent = "  ".repeat(depth)
        val roleIcon = buildString {
            if (node.clickable) append("[C]")
            if (node.scrollable) append("[S]")
            if (node.text.isNotBlank()) append("[T]")
            if (isEmpty()) append("   ")
        }
        val text = when {
            node.text.isNotBlank() -> "\"${node.text.take(60)}\""
            node.contentDescription.isNotBlank() -> "\"${node.contentDescription.take(60)}\""
            else -> ""
        }
        val resId = node.resourceId.ifBlank { "" }
        sb.appendLine("$indent$roleIcon ${node.className} $resId $text [${node.bounds}]")
    }

    private fun getScreenMetrics(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun directionToCoords(
        direction: String,
        screenWidth: Int,
        screenHeight: Int
    ): List<Int> {
        val cx = screenWidth / 2
        val cy = screenHeight / 2
        return when (direction) {
            "up"    -> listOf(cx, (screenHeight * 0.75).toInt(), cx, (screenHeight * 0.25).toInt())
            "down"  -> listOf(cx, (screenHeight * 0.25).toInt(), cx, (screenHeight * 0.75).toInt())
            "left"  -> listOf((screenWidth * 0.8).toInt(), cy, (screenWidth * 0.2).toInt(), cy)
            "right" -> listOf((screenWidth * 0.2).toInt(), cy, (screenWidth * 0.8).toInt(), cy)
            else    -> listOf(cx, (screenHeight * 0.75).toInt(), cx, (screenHeight * 0.25).toInt())
        }
    }

    private fun describeAction(action: GestureAction): String = when (action) {
        is GestureAction.Tap -> "tapped at (${action.x}, ${action.y})"
        is GestureAction.LongPress -> "long-pressed at (${action.x}, ${action.y})"
        is GestureAction.Swipe -> "swiped (${action.x1},${action.y1})→(${action.x2},${action.y2})"
        is GestureAction.DirectionalScroll -> "scrolled ${action.direction}"
        is GestureAction.Back -> "pressed back"
        is GestureAction.Home -> "pressed home"
    }
}

