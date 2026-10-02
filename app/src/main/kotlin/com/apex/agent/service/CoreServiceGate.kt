package com.apex.agent.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * ═══ #236（Keep Alive 真接线）+ P0-1（TerminalRuntime 生命周期宿主）═══
 *
 * 问题背景（Issue #236）：设置里的 Keep Alive 开关此前只影响 BootReceiver 的
 * 开机自启 —— 运行期关掉开关后前台服务照常跑（全仓没有一处 stopService）；
 * 拉起侧还有三股无视开关的力（MainActivity 无条件启动 / START_STICKY /
 * 无障碍心跳）。
 *
 * 本对象统一承载：
 *  - [startedThisProcess]：进程存活期的服务首启标记（MainActivity 原地内联
 *    标记迁出 —— 开关关闭服务后，用户再次进入前台时可重新拉起）；
 *  - [keepAliveEnabled]：apex_settings/agent_settings_v2 的 keepAlive 布尔
 *    快读（BootReceiver / 无障碍心跳 / MainActivity.onDestroy 三方共用，
 *    单一实现点）；
 *  - [apply]：开关切换的运行时接线 —— 开 = startForegroundService（前台
 *    Activity 发起不受 Android 12+ 后台 FGS 限制），关 = stopService
 *    （服务 onDestroy → TerminalRuntime 优雅收尾，见 ApexCoreService）。
 */
object CoreServiceGate {

    /** 进程存活期的 ApexCoreService 首启标记（避免旋转重建反复 onStartCommand）。 */
    @Volatile
    var startedThisProcess: Boolean = false
        private set

    /** MainActivity 首启拉起前标记（幂等门）。 */
    fun markStarted() {
        startedThisProcess = true
    }

    /** 服务被显式停掉（开关关闭 / Activity 收尾）后允许下次再拉起。 */
    fun markStopped() {
        startedThisProcess = false
    }

    /** 快读 agent_settings_v2 JSON 的 keepAlive 布尔（缺省/损坏 → true）。
     * 双端实现注意：platform:privilege 的 ApexAccessibilityService 因模块
     * 依赖方向无法引用本类，保留了一份同款正则实现（KEEP_ALIVE_OFF_REGEX），
     * 两处需同步修改。 */
    fun keepAliveEnabled(context: Context): Boolean {
        val raw = runCatching {
            context.getSharedPreferences("apex_settings", Context.MODE_PRIVATE)
                .getString("agent_settings_v2", null)
        }.getOrNull() ?: return true
        // "keepAlive":false 才是关；true / 缺字段 / 格式异常都视为开
        return !KEEP_ALIVE_OFF_REGEX.containsMatchIn(raw ?: "")
    }

    /** keepAlive 开关切换的运行时接线（#236）。失败不外抛（开关 UI 不因
     * ROM 差异崩溃），仅记日志。 */
    fun apply(context: Context, enabled: Boolean) {
        val intent = Intent(context, ApexCoreService::class.java)
        runCatching {
            if (enabled) {
                // 设置页是前台 Activity —— 不受后台 FGS 启动豁免限制。
                ContextCompat.startForegroundService(context, intent)
                markStarted()
                Log.i("CoreServiceGate", "Keep Alive enabled → service started")
            } else {
                // 关开关 = 立即停掉正在运行的前台服务（此前是"假开关"的直接根因）。
                // 服务 onDestroy 里若 app 已不在前台，会顺带优雅收尾 TerminalRuntime。
                context.stopService(intent)
                markStopped()
                Log.i("CoreServiceGate", "Keep Alive disabled → service stopped")
            }
        }.onFailure {
            Log.w("CoreServiceGate", "keepAlive toggle failed: ${it.javaClass.simpleName}")
        }
    }

    private val KEEP_ALIVE_OFF_REGEX = Regex("\"keepAlive\"\\s*:\\s*false")
}
