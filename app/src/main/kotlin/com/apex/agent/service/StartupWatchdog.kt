package com.apex.agent.service

import android.content.Context
import android.util.Log

/**
 * ═══════════════════════════════════════════════════════════════
 *  StartupWatchdog —— 主路径连续崩溃自愈看门狗（v1.4.9）
 * ═══════════════════════════════════════════════════════════════
 *
 * 背景（秒闪退锁死问题）：onboardingCompleted 持久化后 MainActivity 门控
 * 直接走 ApexRoot 主路径。该路径首次触发 AgentChatViewModel 全依赖栈构造、
 * 十余个 init 协程、玻璃管线首帧、崩溃恢复扫描等重活 —— 任一环节的未捕获
 * 异常都会杀死进程；而引导标记已落盘，下次启动**依然直接走主路径**再崩一次，
 * 用户被锁死在「秒闪退循环」里（唯一出路是卸载或 pm clear）。
 *
 * 机制（进程纪元 + 连续崩溃计数 + 三级递进自愈）：
 *  - [noteProcessStart]（ApexApp.onCreate）：为每个进程写入唯一纪元；
 *  - [noteMainUiAttempt]（MainActivity 门控判定主路径时，每进程一次）：
 *    若上个进程进入主界面后从未达到稳定窗口（[noteMainUiStable]），
 *    连续崩溃计数 +1；
 *  - [noteMainUiStable]（ApexRoot 存活 [STABLE_MS] 后）：清零计数；
 *  - 自愈梯度（宁可保守、绝不删数据）：
 *    · 连续 ≥ [TASKSTORE_QUARANTINE_AT]（2）：taskstore 整目录 rename 隔离
 *      —— 崩溃恢复扫描的最可疑 IO 输入，rename 保留现场可人工恢复；
 *    · 连续 ≥ [ONBOARDING_RESET_AT]（3）：返回 true，MainActivity 据此回滚
 *      onboardingCompleted —— 用户重看一遍可跳过的引导（代价低），
 *      彻底打破锁死；本进程启动即回引导页（引导页本身不依赖主路径组件）。
 *
 * 全部动作留痕：Logcat + filesDir/watchdog.txt（与 crash/ 目录同级，
 * 诊断中心可一并查看），便于事后定位是哪一级自愈救的场。
 */
object StartupWatchdog {

    private const val TAG = "StartupWatchdog"

    /** 看门狗专用 SharedPreferences（与业务设置隔离）。 */
    private const val PREFS = "apex_watchdog"

    /** 当前进程纪元（App.onCreate 写入；commit 同步落盘防进程暴毙丢失）。 */
    private const val KEY_EPOCH = "process_epoch"

    /** true = 主界面已启动但未活过稳定窗口（下个进程据此判定崩溃续跑）。 */
    private const val KEY_PENDING = "main_ui_pending"

    /** 连续未稳定计数（稳定即清零）。 */
    private const val KEY_CONSECUTIVE = "consecutive_unstable"

    /** 主界面存活该时长即视为「稳定」（多数首帧/初始化崩溃都发生在前几秒）。 */
    const val STABLE_MS = 20_000L

    /** 连续未稳定达到该值 → 隔离 taskstore（恢复扫描输入，rename 不删）。 */
    private const val TASKSTORE_QUARANTINE_AT = 2

    /** 连续未稳定达到该值 → 回滚 onboarding（打破秒闪退锁死的最终出口）。 */
    private const val ONBOARDING_RESET_AT = 3

    /** taskstore 目录名（与 AgentModule.provideTaskStore 一致）。 */
    private const val TASKSTORE_DIR = "taskstore"

    /** 进程内一次性守卫：MainActivity 旋转/recreate 不重复计数。 */
    @Volatile
    private var attemptedThisProcess = false

    /**
     * 进程纪元登记（ApexApp.onCreate 调用，每进程一次）。
     * 同步 commit：进程若在写入前死亡，宁可丢一次计数也不误判。
     */
    fun noteProcessStart(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_EPOCH, "${android.os.Process.myPid()}-${System.nanoTime()}")
                .commit()
        }
    }

    /**
     * 主路径启动登记（MainActivity onCreate 判定走 ApexRoot 时调用）。
     *
     * @return true = 连续崩溃达到第三级，调用方应回滚 onboardingCompleted
     *         并本次直接进引导页（打破秒闪退锁死）。
     */
    fun noteMainUiAttempt(context: Context): Boolean {
        if (attemptedThisProcess) return false // recreate：同进程不重复计数
        attemptedThisProcess = true
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pending = prefs.getBoolean(KEY_PENDING, false)
            val count = (if (pending) prefs.getInt(KEY_CONSECUTIVE, 0) else 0) + 1
            prefs.edit()
                .putBoolean(KEY_PENDING, true)
                .putInt(KEY_CONSECUTIVE, count)
                .commit()
            logWatchdog(context, "main-ui attempt: consecutive=$count (pendingWas=$pending)")

            // 第二级：隔离崩溃恢复扫描的最可疑输入（rename 保留现场，不删数据）
            if (count == TASKSTORE_QUARANTINE_AT) {
                quarantineTaskstore(context)
            }

            // 第三级：回滚引导（调用方接线），打破锁死
            count >= ONBOARDING_RESET_AT
        }.getOrDefault(false)
    }

    /**
     * 主界面稳定标记（ApexRoot 存活 [STABLE_MS] 后调用）：计数清零。
     * 稳定后崩溃属于普通崩溃（非首屏锁死），不再触发自愈梯度。
     */
    fun noteMainUiStable(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING, false)
                .putInt(KEY_CONSECUTIVE, 0)
                .commit()
        }
        Log.i(TAG, "main-ui stable after ${STABLE_MS}ms — watchdog counter cleared")
    }

    /** taskstore 整目录隔离：rename 到带时间戳的旁名（可人工恢复，不删数据）。 */
    private fun quarantineTaskstore(context: Context) {
        runCatching {
            val dir = java.io.File(context.filesDir, TASKSTORE_DIR)
            if (!dir.exists()) return
            val target = java.io.File(
                context.filesDir,
                "$TASKSTORE_DIR.quarantined.${System.currentTimeMillis()}"
            )
            val ok = dir.renameTo(target)
            logWatchdog(
                context,
                if (ok) "taskstore quarantined → ${target.name} (recovery-scan input isolated)"
                else "taskstore quarantine FAILED (rename raced; will retry next attempt)"
            )
        }
    }

    /** 看门狗动作留痕：Logcat + filesDir/watchdog.txt（保留最近 200 行）。 */
    private fun logWatchdog(context: Context, message: String) {
        Log.w(TAG, message)
        runCatching {
            val file = java.io.File(context.filesDir, "watchdog.txt")
            file.appendText(
                "[${java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
                ).format(java.util.Date())}] $message\n"
            )
            // 简单保留上限：超 200 行只留后 150
            if (file.length() > 64 * 1024) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(150).joinToString("\n", postfix = "\n"))
            }
        }
    }
}
