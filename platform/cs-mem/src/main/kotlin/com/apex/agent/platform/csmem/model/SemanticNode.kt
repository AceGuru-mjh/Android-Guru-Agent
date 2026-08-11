package com.apex.agent.platform.csmem.model

import android.graphics.Rect
import com.apex.agent.platform.csmem.fingerprint.NodeFingerprint

/**
 * 语义交互图中的节点 —— 从原始 AccessibilityNodeInfo 降维后得到。
 *
 * @param fingerprint 基于 (ClassName, ResourceId, TextHint, ParentHash) 的稳定 SHA-256 指纹
 * @param role 节点交互角色
 * @param textHint 文本或 contentDescription 内容
 * @param resourceId Android View 的 resource-id
 * @param className Android View 类名
 * @param bounds 相对于设备屏幕的坐标边界
 * @param domDepth 修剪后在语义树中的深度（根为 0）
 * @param isInteractive 是否可交互（可点击/可编辑/可滚动等）
 * @param children 修剪后的子节点列表
 */
data class SemanticNode(
    val fingerprint: String,
    val role: NodeRole,
    val textHint: String?,
    val resourceId: String?,
    val className: String?,
    val bounds: Rect,
    val domDepth: Int,
    val isInteractive: Boolean,
    val children: List<SemanticNode> = emptyList()
) {
    companion object {
        /**
         * 从原始 AccessibilityNodeInfo 数据创建 SemanticNode，自动生成指纹。
         */
        fun fromRaw(
            className: String,
            text: String,
            contentDescription: String,
            resourceId: String,
            bounds: String,
            clickable: Boolean,
            scrollable: Boolean,
            isEditable: Boolean = false,
            isVisibleToUser: Boolean = true,
            parentFingerprint: String? = null,
            domDepth: Int = 0
        ): SemanticNode? {
            // 基础过滤：不可见节点直接丢弃
            if (!isVisibleToUser) return null

            val role = classifyRole(className, clickable, scrollable, isEditable, text, contentDescription)

            // 纯装饰节点过滤：不可点击、不可编辑、不可滚动且无文本
            if (role == NodeRole.UNKNOWN && text.isBlank() && contentDescription.isBlank()) {
                return null
            }

            val textHint = when {
                text.isNotBlank() -> text
                contentDescription.isNotBlank() -> contentDescription
                else -> null
            }

            val fingerprint = NodeFingerprint.compute(
                className = className,
                resourceId = resourceId,
                textHint = textHint,
                role = role,
                parentHash = parentFingerprint
            )

            val boundsRect = parseBounds(bounds)

            return SemanticNode(
                fingerprint = fingerprint,
                role = role,
                textHint = textHint,
                resourceId = resourceId.ifBlank { null },
                className = className.ifBlank { null },
                bounds = boundsRect,
                domDepth = domDepth,
                isInteractive = clickable || scrollable || isEditable
            )
        }

        private fun classifyRole(
            className: String,
            clickable: Boolean,
            scrollable: Boolean,
            editable: Boolean,
            text: String,
            contentDescription: String
        ): NodeRole {
            val cls = className.lowercase()

            return when {
                // EditText, AutoCompleteTextView, SearchView
                editable || cls.contains("edit") -> NodeRole.INPUT

                // Switch, CheckBox, ToggleButton, RadioButton
                cls.contains("switch") || cls.contains("toggle") ||
                    cls.contains("checkbox") || cls.contains("radio") -> NodeRole.TOGGLE

                // RecyclerView, ListView, ScrollView, ViewPager, NestedScrollView
                scrollable && (cls.contains("recycler") || cls.contains("list") ||
                    cls.contains("scroll") || cls.contains("pager")) -> NodeRole.SCROLLABLE

                // Button, ImageButton, MaterialButton
                clickable && (cls.contains("button") || cls.contains("btn")) -> NodeRole.BUTTON

                // TabLayout, BottomNavigationView, NavigationView
                cls.contains("tab") || cls.contains("navigation") || cls.contains("bottomnav") ->
                    NodeRole.NAVIGATION

                // Dialog, AlertDialog, BottomSheet, PopupWindow
                cls.contains("dialog") || cls.contains("popup") || cls.contains("bottomsheet") ->
                    NodeRole.DIALOG

                // ImageView, ImageButton (有 contentDescription)
                cls.contains("image") && contentDescription.isNotBlank() -> NodeRole.IMAGE

                // 有文本但不可交互
                (text.isNotBlank() || contentDescription.isNotBlank()) && !clickable && !scrollable ->
                    NodeRole.TEXT

                // 可点击的元素归为BUTTON
                clickable -> NodeRole.BUTTON

                // 不可分类但有contentDescription的类图片元素
                contentDescription.isNotBlank() -> NodeRole.IMAGE

                else -> NodeRole.UNKNOWN
            }
        }

        private fun parseBounds(boundsStr: String): Rect {
            return try {
                // 格式: "Rect(x1, y1 - x2, y2)" 或 "[x1,y1][x2,y2]"
                val nums = Regex("-?\\d+").findAll(boundsStr).map { it.value.toInt() }.toList()
                if (nums.size >= 4) {
                    Rect(nums[0], nums[1], nums[2], nums[3])
                } else {
                    Rect(0, 0, 0, 0)
                }
            } catch (_: Exception) {
                Rect(0, 0, 0, 0)
            }
        }
    }
}
