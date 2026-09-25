package com.apex.agent.vault

import java.util.concurrent.CopyOnWriteArraySet

/**
 * ═══ 全局密钥脱敏器（#167 纵深防御层）═══
 *
 * 与 [VaultRepository] 联动：条目 save/delete 时同步登记/注销密钥；
 * [com.apex.agent.vault.SecretRedactingExecutor] 用它兜底擦洗**所有**工具输出——
 * 即使某工具意外回显了金库密钥，到达模型前也会被替换为掩码。
 *
 * 设计取舍：
 * - 金库条目数少（人工管理的密钥量级），`contains + replace` 逐条扫描
 *   足够快，无需 Aho-Corasick；
 * - 替换按**长度降序**执行：若 secret A 是 secret B 的子串（如前缀），
 *   先替换长的，避免长密钥被短密钥“腰斩”后残留碎片；
 * - [redact] 幂等：掩码 `«vault:***»` 本身不含任何已登记密钥，
 *   已脱敏文本再次过 redact 结果不变。
 *
 * 线程安全：登记集合用 [CopyOnWriteArraySet]（读多写少：登记仅在
 * 金库变更时发生，redact 在每次工具输出时发生）。
 */
class SecretRedactor {

    /** 已登记的密钥集合（长度 ≥ [MIN_SECRET_LENGTH] 的非空串）。 */
    private val secrets = CopyOnWriteArraySet<String>()

    /** 批量登记密钥；只接受长度 ≥ 6 且非空白的条目（过短的串误杀率太高）。 */
    fun register(secrets: List<String>) {
        secrets.forEach { s ->
            if (s.length >= MIN_SECRET_LENGTH) this.secrets.add(s)
        }
    }

    /** 注销单个密钥（条目删除 / 密钥被覆写时调用）。 */
    fun unregister(secret: String) {
        secrets.remove(secret)
    }

    /** 清空登记（整库重载 / 重建登记表时调用）。 */
    fun clear() {
        secrets.clear()
    }

    /** 当前已登记密钥数（诊断用）。 */
    fun registeredCount(): Int = secrets.size

    /**
     * 把文本中所有已登记密钥替换为 `«vault:***»`。
     * 幂等：对已脱敏文本重复调用不会二次改写。
     */
    fun redact(text: String): String {
        if (text.isEmpty() || secrets.isEmpty()) return text
        var result = text
        // 快速预检：常见路径（未登记任何命中）零分配直接返回。
        if (secrets.none { result.contains(it) }) return result
        // 长度降序替换：防长密钥被短密钥前缀肢解后漏脱敏。
        for (secret in secrets.sortedByDescending { it.length }) {
            if (result.contains(secret)) {
                result = result.replace(secret, MASK)
            }
        }
        return result
    }

    /**
     * Flow 集成用：返回稳定的脱敏函数引用
     * （`flow.map(redactor.redactFn)` / 事件 copy 时复用同一引用）。
     */
    val redactFn: (String) -> String
        get() = { redact(it) }

    companion object {
        /** 脱敏掩码（含特殊书名号避免与常见密钥字符集撞形）。 */
        const val MASK = "«vault:***»"

        /** 参与登记的最小密钥长度：过短的串（如 "abc"）误杀普通文本概率高。 */
        const val MIN_SECRET_LENGTH = 6
    }
}
