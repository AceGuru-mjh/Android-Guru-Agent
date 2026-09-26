package com.apex.agent.terminalemulator

/**
 * ═══ OSC 8 超链接注册表（激活 RenderCell.link —— Termux 对齐）═══
 *
 * 现代 CLI 工具（ls --hyperlink、gh、ghq、jq、bat）通过 OSC 8 把 URL
 * 嵌进输出文本，终端负责把它变成可点链接：
 *
 * ```
 * guest: printf '\e]8;;https://example.com\e\\link text\e]8;;\e\\'
 *           └── 开链（URI 进表，返回 id=1）     └── 闭链（此后 link=0）
 *   │
 *   ▼ TerminalStyle.linkIndex = 1（开链后所有落屏 cell 携带）
 * host: RenderCell.link = 1 → UI 查 [HyperlinkRegistry.uriOf](1) → 点击打开
 * ```
 *
 * 会话语义：URI 表按「显式 id」与「裸 URI」两种形态共存 ——
 * - `ESC]8;id=foo;uri` → 相同 id 的开链**复用**同一表项（kitty/WezTerm 语义）；
 * - `ESC]8;;uri` → 自动递增 id；
 * - `ESC]8;;`（空 URI）→ 闭链，样式 linkIndex 归 0（表项保留，历史行仍可点）。
 *
 * 有界防泄漏：长跑会话里 guest 可能无限开链（每行一个链接），表超上限时
 * 淘汰最旧未引用项（全表扫描一次 O(n)，n ≤ 上限 256，触发频率低）。
 * 被淘汰表项的 link 编号**不复用**（单调递增 id 生成器），旧 cell 的悬空
 * 编号查表返回 null —— UI 按无链接渲染，绝不张冠李戴。
 *
 * 纯 JVM、无 Android 依赖。
 */
class HyperlinkRegistry(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        /** 默认表容量（Termux 相同量级；足够整屏 ls --hyperlink 输出）。 */
        const val DEFAULT_CAPACITY = 256
    }

    /** 表项：URI + 分配的编号（对 UI 稳定；编号全局单调不复用）。 */
    private data class Entry(val linkId: Int, val uri: String)

    /** id= 显式键 → 表项（复用语义）。 */
    private val byExplicitId = HashMap<String, Entry>()

    /** 分配编号 → 表项（UI 查询路径）。 */
    private val byLinkId = HashMap<Int, Entry>()

    /** 插入顺序（容量淘汰用）。 */
    private val insertionOrder = ArrayDeque<Entry>()

    private var nextAutoId = 1

    /** 当前活跃链接编号（0 = 无 —— 闭链状态）。 */
    var activeLinkId: Int = 0
        private set

    /** 当前活跃 URI（null = 闭链）。 */
    val activeUri: String? get() = byLinkId[activeLinkId]?.uri

    /**
     * 开链：`OSC 8 ; params ; uri`（params 形如 `id=foo` 或空）。
     *
     * @param params OSC 第 1 参数（`id=...`；空串 = 自动编号）
     * @param uri 目标 URI；**空串 = 闭链**（归零活跃编号）
     * @return 新的活跃链接编号（0 = 闭链）
     */
    fun open(params: String, uri: String): Int {
        if (uri.isEmpty()) {
            activeLinkId = 0
            return 0
        }
        val explicitKey = params.substringAfter("id=", "").takeIf { it.isNotEmpty() }
        if (explicitKey != null) {
            val existing = byExplicitId[explicitKey]
            if (existing != null) {
                activeLinkId = existing.linkId
                return existing.linkId
            }
        }
        // 容量守卫：满表淘汰最旧
        while (byLinkId.size >= capacity) {
            val oldest = insertionOrder.removeFirstOrNull() ?: break
            byLinkId.remove(oldest.linkId)
            byExplicitId.values.removeAll { it.linkId == oldest.linkId }
        }
        val entry = Entry(nextAutoId++, uri)
        byLinkId[entry.linkId] = entry
        insertionOrder.addLast(entry)
        if (explicitKey != null) byExplicitId[explicitKey] = entry
        activeLinkId = entry.linkId
        return entry.linkId
    }

    /** 闭链（等价 `open("", "")`）。 */
    fun close() {
        activeLinkId = 0
    }

    /**
     * 编号 → URI；悬空编号（被淘汰/未分配）返回 null。
     * [linkId] 0 一律 null（保留值 = 无链接）。
     */
    fun uriOf(linkId: Int): String? = if (linkId <= 0) null else byLinkId[linkId]?.uri

    /** 当前注册表快照（id 升序）—— [TerminalEngine.links] 契约实现。 */
    fun allUris(): List<String> =
        byLinkId.entries.sortedBy { it.key }.map { it.value.uri }

    /** 会话重置（RIS）：清表、编号归零（历史 cell 编号随之悬空，安全）。 */
    fun reset() {
        byExplicitId.clear()
        byLinkId.clear()
        insertionOrder.clear()
        nextAutoId = 1
        activeLinkId = 0
    }

    /** 当前表大小（诊断/测试用）。 */
    val size: Int get() = byLinkId.size
}
