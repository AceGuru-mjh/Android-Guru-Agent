package com.apex.agent.platform.csmem.fingerprint

import com.apex.agent.platform.csmem.model.NodeRole
import java.security.MessageDigest

/**
 * 稳定节点指纹生成器。
 *
 * 指纹不依赖 AccessibilityNodeInfo 的内存地址或系统运行时ID，
 * 而是基于语义特征 (ClassName, ResourceId, TextHint, Role, ParentHash) 计算 SHA-256，
 * 确保 App 重启或 UI 刷新后同一逻辑按钮的指纹保持不变。
 */
object NodeFingerprint {

    private val digest = MessageDigest.getInstance("SHA-256")

    /**
     * 计算节点的稳定指纹。
     *
     * @param className Android View 类名（如 "android.widget.Button"）
     * @param resourceId 资源 ID（如 "com.example:id/btn_login"）
     * @param textHint 文本或 contentDescription
     * @param role 节点角色
     * @param parentHash 父节点指纹（用于区分不同上下文中的相同元素）
     * @return 64字符的十六进制指纹字符串
     */
    fun compute(
        className: String,
        resourceId: String,
        textHint: String?,
        role: NodeRole,
        parentHash: String?
    ): String {
        val sb = StringBuilder()
        sb.append("cls:").append(className).append('|')
        sb.append("res:").append(resourceId).append('|')
        sb.append("text:").append(textHint ?: "").append('|')
        sb.append("role:").append(role.name).append('|')
        sb.append("parent:").append(parentHash ?: "root")

        val hash = synchronized(digest) {
            digest.reset()
            digest.digest(sb.toString().toByteArray(Charsets.UTF_8))
        }
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算根节点指纹（无父节点）。
     */
    fun computeRoot(
        className: String,
        resourceId: String,
        textHint: String?
    ): String {
        return compute(className, resourceId, textHint, NodeRole.UNKNOWN, parentHash = null)
    }
}
