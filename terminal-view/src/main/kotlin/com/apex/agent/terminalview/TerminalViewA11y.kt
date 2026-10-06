package com.apex.agent.terminalview

import android.os.SystemClock
import android.view.View
import com.apex.agent.terminalemulator.RenderRuns
import com.apex.agent.terminalemulator.TerminalRenderSnapshot

/**
 * T92：无障碍播报（从 TerminalView 抽出 —— SRP 行预算）。
 *
 * - [View.contentDescription] 随最新输出行更新（TalkBack 朗读面板）；
 * - 输出增长播报：2s 限速（连续刷屏不轰炸 TalkBack），仅附着窗口时派发。
 *
 * T95：最新行优先取 run 投影（快照直供，零派生）；未提供 run 的第三方
 * 引擎退回 cell 行单行派生（[RenderRuns.deriveRow] —— 同一折叠真源）。
 */
internal class TerminalViewA11y(private val view: View) {
    private var lastAnnounceUptime = 0L

    /** 快照到达时调用（View 持有方接线）。 */
    fun updateContent(s: TerminalRenderSnapshot) {
        val lastRuns = s.runLines?.lastOrNull()
        val lastLine = (lastRuns?.let(RenderRuns::runsToText)
            ?: s.lines.lastOrNull()?.let(RenderRuns::deriveRow)?.let(RenderRuns::runsToText))
            ?.takeLast(120)
        view.contentDescription = if (lastLine.isNullOrBlank()) "Terminal" else "Terminal: $lastLine"
        val now = SystemClock.uptimeMillis()
        if (view.isAttachedToWindow && now - lastAnnounceUptime > 2000L && !lastLine.isNullOrBlank()) {
            lastAnnounceUptime = now
            view.announceForAccessibility(lastLine.take(80))
        }
    }
}
