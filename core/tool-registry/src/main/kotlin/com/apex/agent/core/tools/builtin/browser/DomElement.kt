@file:JvmName("DomModel")

package com.apex.agent.core.tools.builtin.browser

import kotlinx.serialization.Serializable

/**
 * 浏览器页面中的单个可交互 / 可索引元素。
 *
 * 相比 Operit 的 [data-bid] 方案，这里额外保留 [rect]（相对 WebView 的像素矩形）、
 * [isVisible]、[depth]（DOM 深度），使 Agent 既能做「DOM 级精确点击」，也能在
 * 需要时用坐标兜底，并能用 [depth] 控制摘要压缩层级。
 */
@Serializable
data class DomElement(
    /** 展示用顺序索引（1..n），仅作人类可读编号，不作定位主键 */
    val bid: Int,
    /** 语义哈希稳定引用（data-apex-hash），如 "r_3k9f"：基于 role+text+tag+相对位置计算，
     *  刷新/局部变动后稳定；所有点击、输入、选择操作均优先用本字段定位（对标 Operit 的 aria-ref）。 */
    val ref: String = "",
    val tag: String,
    val text: String = "",
    /** 可读的稳定描述，例如 "BUTTON 搜索" 或 "A 新闻标题" */
    val label: String = "",
    val attributes: Map<String, String> = emptyMap(),
    /** 相对页面内容区的像素矩形（x, y, width, height） */
    val rect: Rect = Rect(0, 0, 0, 0),
    val isVisible: Boolean = true,
    val isInteractive: Boolean = false,
    /** DOM 深度，用于摘要压缩时优先裁剪深层非交互节点 */
    val depth: Int = 0,
    /** 直接子节点数量，用于判断容器 */
    val childCount: Int = 0,
)

@Serializable
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * 一帧页面的结构化快照，发送给 Agent。
 *
 * [interactiveElements] 是经过裁剪、带 [DomElement.bid] 的可交互元素列表；
 * [domSummary] 是面向 LLM 的紧凑文本树（已压缩），用于整体理解页面。
 */
@Serializable
data class PageSnapshot(
    val url: String,
    val title: String,
    val scrollY: Int,
    val scrollHeight: Int,
    val viewportHeight: Int,
    val interactiveCount: Int,
    /** 紧凑的可读文本树，已按 token 预算裁剪，直接进 prompt */
    val domSummary: String,
    /** 完整可交互元素列表，供工具按 bid 精准操作 */
    val interactiveElements: List<DomElement>,
)

/** 从 JS 注入点拿到的原始元素（序列化自 injected JS） */
@Serializable
internal data class RawDomElement(
    val tag: String,
    val text: String?,
    val attributes: Map<String, String>,
    val rect: Rect,
    val isVisible: Boolean,
    val isInteractive: Boolean,
    val depth: Int,
    val childCount: Int,
)
