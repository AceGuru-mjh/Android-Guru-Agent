package com.apex.agent.terminalemulator

/**
 * ═══ 焦点报告（DECSET 1004 — Focus In/Out）═══
 *
 * vim（`set termguicolors` + FocusGained/FocusLost autocommand）、tmux
 * （`set -g focus-events on`）、以及其他 TUI 程序靠它感知前台/后台切换：
 *
 * ```
 * guest: printf '\e[?1004h'          ← 请求焦点报告
 * host: 窗口获得焦点 → ESC[I         （CSI I）
 * host: 窗口失去焦点 → ESC[O         （CSI O）
 * guest: vim 触发 FocusGained → 恢复 syntax 刷新 / 光标闪烁
 * ```
 *
 * 宿主职责：监听宿主窗口焦点变化（Compose：ON_FOCUS / lifecycle），仅在
 * [FocusReporting.enabled] 时把 [FocusReporting.focusIn] / [focusOut] 的
 * 字节经 RAW 通道写入 PTY。未开启时编码函数返回 null，UI 零成本跳过。
 *
 * 注意：这两个序列**不是用户输入**，不走输入策略门禁（与 DA1/CPR 应答同级）。
 */
data class FocusReporting(val enabled: Boolean = false) {

    companion object {
        /** DECSET 1004 h/l 的状态迁移（不可变，宿主直接替换）。 */
        fun apply(current: FocusReporting, enable: Boolean): FocusReporting =
            current.copy(enabled = enable)
    }
}

/**
 * 焦点事件 → guest 字节序列；未开启时 null（UI 不应发送）。
 *
 * 语义：获得焦点 → `CSI I`（ESC[I）；失去焦点 → `CSI O`（ESC[O）。
 * 这两个序列不是用户输入，不走输入策略门禁（与 DA1/CPR 应答同级）。
 */
fun encodeFocusEvent(gained: Boolean, mode: FocusReporting): ByteArray? {
    if (!mode.enabled) return null
    return if (gained) "\u001B[I".toByteArray(Charsets.US_ASCII)
    else "\u001B[O".toByteArray(Charsets.US_ASCII)
}

/** 便捷扩展：焦点获得序列（enabled=false → null）。 */
fun FocusReporting.focusIn(): ByteArray? = encodeFocusEvent(gained = true, mode = this)

/** 便捷扩展：焦点丢失序列（enabled=false → null）。 */
fun FocusReporting.focusOut(): ByteArray? = encodeFocusEvent(gained = false, mode = this)
