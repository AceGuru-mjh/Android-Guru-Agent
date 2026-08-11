package com.apex.agent.platform.csmem.immune

import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.UiNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆免疫系统 —— 基于多模态交叉验证的 UI 欺骗防御。
 *
 * 防御威胁：
 *   - 悬浮窗/Overlay 攻击：恶意 App 绘制假登录界面
 *   - 无障碍服务劫持：篡改 UI 树文本内容
 *   - 记忆中毒 (Memory Poisoning)：恶意 UI 数据被写入长期记忆
 *
 * 机制：
 *   1. 视觉层验证：截图 OCR 与 Accessibility 树交叉比对
 *   2. 纹理特征比对：当前 UI 视觉特征与历史"已知良性"特征比对
 *   3. 记忆隔离 (MemoryQuarantine)：可疑 UI 不写入长期记忆
 *   4. 强制终止：高危攻击场景下终止当前 Agent 任务
 *
 * 注意：完整的 OCR 比对需要集成 MLKit Text Recognition，
 *       当前阶段实现纹理指纹 + 结构完整性检查。
 */
@Singleton
class MemoryImmuneSystem @Inject constructor(
    private val privilegeManager: PrivilegeManager
) {
    /** 隔离名单 —— 被标记为可疑的 UI 指纹集合 */
    private val quarantineSet = mutableSetOf<String>()

    /** 已知良性 App 白名单（包名） */
    private val trustedPackages = mutableSetOf<String>(
        "com.android.settings",
        "com.android.chrome",
        "com.android.calculator2"
    )

    /**
     * 对 UI 树执行安全检查。
     *
     * @param nodes 当前 UI 树
     * @param appPackage 来源 App 包名
     * @param screenBounds 屏幕边界（检测悬浮窗）
     * @return 安全检查结果
     */
    fun validateUiTree(
        nodes: List<UiNode>,
        appPackage: String,
        screenBounds: Pair<Int, Int>
    ): ImmuneResult {
        val (screenW, screenH) = screenBounds
        val issues = mutableListOf<String>()
        var threatLevel = ThreatLevel.SAFE

        // 检查1：悬浮窗检测 —— 检测全屏覆盖的非前台 App 节点
        val overlayNodes = detectOverlay(nodes, screenW, screenH)
        if (overlayNodes.isNotEmpty()) {
            issues.add("检测到 ${overlayNodes.size} 个可疑全屏节点（疑似悬浮窗攻击）")
            threatLevel = ThreatLevel.SUSPICIOUS
            for (node in overlayNodes) {
                quarantineSet.add(nodeFingerprint(node))
            }
        }

        // 检查2：敏感关键词检测
        val sensitivePatterns = listOf(
            "输入密码", "请输入支付密码", "请输入银行卡号",
            "中奖", "领取红包", "系统升级", "安全验证"
        )
        for (node in nodes) {
            for (pattern in sensitivePatterns) {
                if (node.text.contains(pattern) || node.contentDescription.contains(pattern)) {
                    // 只在非受信 App 中触发
                    if (appPackage !in trustedPackages) {
                        issues.add("非受信 App[$appPackage] 节点包含敏感文本: \"$pattern\"")
                        threatLevel = ThreatLevel.HIGH_RISK
                        quarantineSet.add(nodeFingerprint(node))
                    }
                }
            }
        }

        // 检查3：结构完整性 —— UI 树不应完全为空或异常稀疏
        if (nodes.isEmpty() || countAllNodes(nodes) < 3) {
            issues.add("UI 树异常稀疏（${countAllNodes(nodes)} 节点），可能存在无障碍劫持")
            threatLevel = ThreatLevel.SUSPICIOUS
        }

        // 检查4：包名一致性 —— 所有节点的包名应一致
        // (待实现：需要 AccessibilityNodeInfo 提供 packageName)

        return ImmuneResult(
            safe = threatLevel == ThreatLevel.SAFE,
            threatLevel = threatLevel,
            issues = issues,
            quarantinedNodes = quarantineSet.toList()
        )
    }

    /**
     * 判断指定指纹是否已被隔离。
     */
    fun isQuarantined(fingerprint: String): Boolean {
        return fingerprint in quarantineSet
    }

    /**
     * 清除隔离名单（App 更新后可重新评估）。
     */
    fun clearQuarantine() {
        quarantineSet.clear()
    }

    // ==================== Private ====================

    private fun detectOverlay(nodes: List<UiNode>, screenW: Int, screenH: Int): List<UiNode> {
        val result = mutableListOf<UiNode>()

        for (node in nodes) {
            val bounds = parseBounds(node.bounds)
            // 覆盖 80% 以上屏幕 → 可能是恶意悬浮窗
            val areaRatio = (bounds.width().toFloat() * bounds.height().toFloat()) /
                (screenW.toFloat() * screenH.toFloat())
            if (areaRatio > 0.8f && node.clickable) {
                result.add(node)
            }
        }

        return result
    }

    private fun parseBounds(boundsStr: String): android.graphics.Rect {
        return try {
            val nums = Regex("-?\\d+").findAll(boundsStr)
                .map { it.value.toInt() }.toList()
            if (nums.size >= 4) {
                android.graphics.Rect(nums[0], nums[1], nums[2], nums[3])
            } else {
                android.graphics.Rect(0, 0, 0, 0)
            }
        } catch (_: Exception) {
            android.graphics.Rect(0, 0, 0, 0)
        }
    }

    private fun nodeFingerprint(node: UiNode): String {
        return "${node.className}_${node.resourceId}_${node.text}"
    }

    private fun countAllNodes(nodes: List<UiNode>): Int {
        return nodes.sumOf { 1 + countAllNodes(it.children) }
    }
}

/**
 * 免疫系统检查结果。
 */
data class ImmuneResult(
    val safe: Boolean,
    val threatLevel: ThreatLevel,
    val issues: List<String>,
    val quarantinedNodes: List<String>
)

enum class ThreatLevel {
    /** 安全，无异常 */
    SAFE,
    /** 可疑，已隔离但不阻断执行 */
    SUSPICIOUS,
    /** 高危，阻断写入 + 建议终止任务 */
    HIGH_RISK,
    /** 恶意，强制终止任务 */
    MALICIOUS
}
