package com.apex.agent.loop

import com.apex.agent.core.tools.builtin.VixieCron
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * S2 — app 侧 cron 薄适配器（epoch 毫秒 ↔ 墙钟 LocalDateTime 换算层）。
 *
 * 解析与下次运行搜索**不在此复制**：全部委托 core:tool-registry 的
 * [VixieCron]（cron_next 工具同源实现，S2 起抽为公开对象）。本对象只做
 * 三件事：系统时区换算、epoch ms 语义、防御式折叠（任何失败 → null，
 * 不上抛——AGENTS.md 纪律 4）。
 */
object CronSchedule {

    /**
     * 表达式在 nowMs 之后的下一次触发时刻（epoch ms）；解析失败 / 4 年内
     * 不可达 / 空白输入一律返回 null。调用方（调度器/配置 UI）按 null 折叠：
     * - LoopScheduler.nextRunAt：null → Long.MAX_VALUE（永不到期，静默躺平）；
     * - LoopSetupSheet：null → 红字提示「表达式无效」。
     */
    fun nextAfter(cronExpr: String, nowMs: Long): Long? {
        if (cronExpr.isBlank()) return null
        return try {
            val zone = ZoneId.systemDefault()
            val startWall = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            val nextWall = VixieCron.nextAfter(cronExpr, startWall) ?: return null
            nextWall.atZone(zone).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null // 防御式：任何形状的坏输入都折叠为 null + 不上抛（留痕见调用方日志）
        }
    }
}
