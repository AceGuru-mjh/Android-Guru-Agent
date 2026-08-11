package com.apex.agent.platform.csmem.model

/**
 * 语义节点的交互角色分类。
 * 修剪后只保留对 Agent 交互有意义的节点类型。
 */
enum class NodeRole(val description: String) {
    /** 可点击/可交互的按钮类元素 */
    BUTTON("Clickable interactive element"),

    /** 文本输入区域 */
    INPUT("Editable text input field"),

    /** 可滚动容器（列表、RecyclerView等） */
    SCROLLABLE("Scrollable container"),

    /** 有意义的文本标签（非纯装饰文本） */
    TEXT("Semantically meaningful text label"),

    /** 列表项（位于可滚动容器内的子元素） */
    LIST_ITEM("Item inside a scrollable list"),

    /** 开关/Toggle/Checkbox */
    TOGGLE("Switch, checkbox or toggle"),

    /** 图片/图标（有contentDescription的ImageView） */
    IMAGE("Image or icon with description"),

    /** 导航栏元素（Tab、底部栏按钮等） */
    NAVIGATION("Navigation element"),

    /** 对话框/弹窗 */
    DIALOG("Dialog or popup window"),

    /** 未知/其他（兜底分类） */
    UNKNOWN("Unclassified element")
}
