package com.apex.agent.update

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 发布仓库补丁全量索引（`patches.json`）—— 跨版本增量更新的数据底座。
 *
 * `version.json` 只携带「上一版 → 本版」单个补丁，跨两个以上小版本时
 * 客户端无从得知中间补丁的存在，只能回退 300~900MB 全量包。本索引由
 * 开发仓库 CI 在每次发布时**累积**维护（老条目永不清除），客户端由此
 * 把「本地版本 → 最新版本」解析成一条补丁链，逐段应用即跨越任意多版。
 *
 * 文件布局（与发布仓库 main 分支同源，raw CDN 可读）：
 * ```json
 * {
 *   "generatedAt": "2026-09-30T15:04:25Z",
 *   "latestTag": "v1.4.4.5",
 *   "patches": [
 *     {"variant":"arm64","fromTag":"v1.4.4.4","toTag":"v1.4.4.5",
 *      "url":"https://github.com/.../patch_arm64_v1.4.4.4_to_v1.4.4.5.vcdiff",
 *      "sizeBytes":11147826,"sha256":"493c…"}
 *   ]
 * }
 * ```
 *
 * 设计约束：
 * - **前向兼容**：全部字段可选 + `ignoreUnknownKeys` —— 索引 schema 演进
 *   （新增字段）不崩老客户端；缺 sha256 时跳过校验（与既有 APK 下载同策）；
 * - **零鉴权**：与 version.json 同走 raw.githubusercontent 公开 CDN；
 * - **容错折叠**：解析失败返回 null，调用方回退 version.json 单补丁或
 *   全量包，更新链路永不因索引问题而中断。
 */
object PatchIndex {

    /** 发布仓库 main 分支上的补丁索引固定地址（CI 自动维护）。 */
    const val PATCH_INDEX_URL =
        "https://raw.githubusercontent.com/Ultra-Guru/Android-Guru-Agent-Release/main/patches.json"

    /** 链上补丁数量上限 —— 防御环形索引（CI bug / 被篡改）导致死循环。 */
    private const val MAX_CHAIN_LENGTH = 64

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 单条补丁：variant ∈ {arm64, universal}。 */
    @Serializable
    data class Entry(
        val variant: String,
        val fromTag: String,
        val toTag: String,
        val url: String,
        val sizeBytes: Long = 0L,
        val sha256: String? = null
    )

    /** 索引根模型。 */
    @Serializable
    data class Model(
        val generatedAt: String? = null,
        val latestTag: String? = null,
        val patches: List<Entry> = emptyList()
    )

    /** 解析出的补丁链（有序，可逐段应用）与总体积。 */
    data class Chain(
        val steps: List<Entry>,
        val totalBytes: Long
    )

    /** 宽松解析：任何形状异常折叠为 null（防御式 IO 纪律）。 */
    fun parse(text: String): Model? = runCatching { json.decodeFromString<Model>(text) }
        .onFailure {
            AppLogger.instance.warn(
                LogCategory.SYSTEM, "PatchIndex", "补丁索引解析失败：${it.message}"
            )
        }
        .getOrNull()

    /**
     * 解析「本地版本 → 目标 tag」的**最短**补丁链（BFS）。
     *
     * 图结构：fromTag → toTag 有向边（按 variant 过滤）。多基底发布后同一
     * fromTag 会有多条出边（相邻版边 + 跨版直达边）—— 逐跳推走第一条边的
     * 旧算法会错过「v1.4.4.21 → v1.4.5」直达边而绕行走两段相邻边。BFS
     * 保证：存在直达边必选直达（跳数最少）；同跳数回溯时贪心取每段体积
     * 最小的前驱边（真实索引出边稀疏，贪心即近优）。
     *
     * 断链（本地版本无任何出边可达目标）→ null，调用方回退全量。
     *
     * @param index 索引模型
     * @param localVersionName 本地 versionName（如 "1.4.4.3"）
     * @param targetTag 目标 tag（如 "v1.4.4.5"）；空白时回退 index.latestTag
     * @param variant 设备 ABI 变体（"arm64" / "universal"）
     */
    fun resolveChain(
        index: Model,
        localVersionName: String,
        targetTag: String,
        variant: String
    ): Chain? {
        val from = "v$localVersionName"
        val target = targetTag.ifBlank { index.latestTag.orEmpty() }
        if (target.isBlank() || from == target) return null

        // 邻接表：fromTag → 出边（多基底 = 多条）
        val outgoing = HashMap<String, MutableList<Entry>>()
        for (entry in index.patches) {
            if (entry.variant != variant) continue
            if (entry.url.isBlank()) continue
            outgoing.getOrPut(entry.fromTag) { ArrayList(1) }.add(entry)
        }

        // ── BFS 求最短跳数 ────────────────────────────────────────────
        // dist[fromTag] = 最少补丁段数；队列逐层扩张，命中 target 即停。
        val dist = HashMap<String, Int>()
        dist[from] = 0
        val queue = ArrayDeque<String>()
        queue.add(from)
        while (queue.isNotEmpty()) {
            val cursor = queue.removeFirst()
            val d = dist.getValue(cursor)
            if (d >= MAX_CHAIN_LENGTH) continue
            for (edge in outgoing[cursor].orEmpty()) {
                if (edge.toTag == target) {
                    dist[edge.toTag] = d + 1
                    queue.clear()
                    break
                }
                if (!dist.containsKey(edge.toTag)) {
                    dist[edge.toTag] = d + 1
                    queue.add(edge.toTag)
                }
            }
        }
        val hops = dist[target] ?: return null
        if (hops <= 0 || hops > MAX_CHAIN_LENGTH) return null

        // ── 回溯重构路径：同跳数前驱中取体积最小（下载量最优）────────────
        val steps = ArrayList<Entry>(hops)
        var cursor = target
        while (cursor != from) {
            val nextHop = dist.getValue(cursor)
            val predecessor = outgoing.entries
                .asSequence()
                .filter { dist.getValueOrDefault(it.key, Int.MAX_VALUE) == nextHop - 1 }
                .flatMap { it.value.asSequence() }
                .filter { it.toTag == cursor }
                .minByOrNull { it.sizeBytes.coerceAtLeast(0L) }
                ?: return null
            steps.add(predecessor)
            cursor = predecessor.fromTag
        }
        steps.reverse()
        if (steps.isEmpty()) return null
        val total = steps.fold(0L) { acc, entry -> acc + entry.sizeBytes.coerceAtLeast(0L) }
        return Chain(steps, total)
    }
}

/** [HashMap.getValueOrDefault] —— Kotlin/JVM 无此内联（getOrDefault 是 JRE 方法，
 *  MinSdk 26 可用；此处显式包装避免可空读取歧义）。 */
private fun <K, V> Map<K, V>.getValueOrDefault(key: K, default: V): V =
    if (containsKey(key)) getValue(key) else default
