package com.apex.agent.platform.csmem.dream

import com.apex.agent.platform.csmem.model.NodeRole
import com.apex.agent.platform.csmem.model.SemanticNode
import com.apex.agent.platform.csmem.store.MigrationMap

/**
 * 拓扑同胚迁移器 —— 轻量属性相似度映射（非完整 VF2 子图同构）。
 *
 * 思路：App 版本演进后 UI 拓扑变化（resourceId / className / 文案），旧节点指纹失效。
 * 对每个"旧版本"节点，在**同 appPackage + 同 role** 的"新版本"节点中按相似度打分，
 * 选最高分候选；分数 ≥ [MATCH_THRESHOLD] 视为同胚映射，建立指纹别名桥。
 *
 * 为什么不用完整 VF2：
 * - VF2 需构建完整子图并做回溯匹配，计算重、易误匹配（相似布局干扰）；
 * - 记忆复用只关心"哪个旧按钮等价于哪个新按钮"，属性相似度已足够；
 * - 不重写旧指纹（指纹是 stable key），仅建别名，风险可控。
 *
 * 打分权重：
 * - resourceId 归一化（去包名前缀、转小写）相等：+0.5
 * - textHint 包含/被包含 或 Levenshtein 相似度 > 0.8：+0.4
 * - 末级 className 相等：+0.1
 */
object TopologyMigrator {

    /** 映射置信度阈值（低于此分数不建立别名桥） */
    private const val MATCH_THRESHOLD = 0.7f

    /**
     * 生成旧→新版本的节点映射。
     *
     * @param oldNodes 上一已知版本的全部节点
     * @param newNodes 当前新版本的全部节点
     * @param fromVersion 旧版本号
     * @param toVersion 新版本号
     * @return 置信度达标的映射列表（一对一，每个旧节点至多一条）
     */
    fun migrate(
        oldNodes: List<SemanticNode>,
        newNodes: List<SemanticNode>,
        fromVersion: String,
        toVersion: String
    ): List<MigrationMap> {
        if (oldNodes.isEmpty() || newNodes.isEmpty()) return emptyList()

        return oldNodes.mapNotNull { old ->
            // 候选限定：同 role（跨角色无意义；SemanticNode 不携带包名，包级隔离无需比较）
            val candidates = newNodes.filter {
                it.role == old.role
            }
            if (candidates.isEmpty()) return@mapNotNull null

            val best = candidates.maxByOrNull { score(old, it) } ?: return@mapNotNull null
            val s = score(old, best)
            if (s < MATCH_THRESHOLD) return@mapNotNull null

            MigrationMap(
                oldFingerprint = old.fingerprint,
                newFingerprint = best.fingerprint,
                matchScore = s,
                fromVersion = fromVersion,
                toVersion = toVersion
            )
        }
    }

    /**
     * 旧→新单节点相似度打分（0~1）。
     */
    private fun score(old: SemanticNode, new: SemanticNode): Float {
        var s = 0f
        if (normalizeResourceId(old.resourceId) == normalizeResourceId(new.resourceId)
            && old.resourceId != null && new.resourceId != null
        ) {
            s += 0.5f
        }
        if (textSimilar(old.textHint, new.textHint)) {
            s += 0.4f
        }
        if (lastClassName(old.className) == lastClassName(new.className)
            && old.className != null && new.className != null
        ) {
            s += 0.1f
        }
        return s.coerceAtMost(1f)
    }

    /** 去包名前缀 + 转小写，使 resourceId 跨包名前缀仍可比对 */
    private fun normalizeResourceId(resId: String?): String? {
        if (resId.isNullOrBlank()) return null
        val colon = resId.indexOf(':')
        val local = if (colon >= 0) resId.substring(colon + 1) else resId
        val slash = local.indexOf('/')
        val name = if (slash >= 0) local.substring(slash + 1) else local
        return name.lowercase()
    }

    /** 文案相似：包含关系 或 Levenshtein 相似度 > 0.8 */
    private fun textSimilar(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a.contains(b, ignoreCase = true) || b.contains(a, ignoreCase = true)) return true
        return levenshteinSimilarity(a, b) > 0.8f
    }

    /** 取类名末级（如 "android.widget.Button" → "Button"） */
    private fun lastClassName(className: String?): String? {
        if (className.isNullOrBlank()) return null
        val dot = className.lastIndexOf('.')
        return if (dot >= 0) className.substring(dot + 1) else className
    }

    /** Levenshtein 相似度（0~1，1 表示完全相同） */
    private fun levenshteinSimilarity(a: String, b: String): Float {
        val m = a.length
        val n = b.length
        if (m == 0 && n == 0) return 1f
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        val maxLen = maxOf(m, n)
        return (maxLen - dp[m][n]) / maxLen.toFloat()
    }
}
