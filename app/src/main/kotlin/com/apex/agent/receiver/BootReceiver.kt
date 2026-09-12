package com.apex.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.apex.agent.service.ApexCoreService

/**
 * 开机 / 应用更新后的核心服务自恢复。
 *
 * 秒闪退防御（构建前修复项）：
 * Android 12+ 对后台启动前台服务有严格豁免清单 —— MY_PACKAGE_REPLASSED 广播
 * 不在豁免内，Android 14/15 上 BOOT_COMPLETED 也仅对部分 FGS 类型放行
 * （specialUse 不一定命中）。此时 startForegroundService 会抛
 * ForegroundServiceStartNotAllowedException，BroadcastReceiver.onReceive
 * 内未捕获即进程级崩溃 —— 表现为「更新完 APK 一开机就闪退」。
 * 这里 runCatching 兜底：启动失败仅记日志，等用户主动打开 App 再拉起服务
 * （MainActivity/前台路径不受豁免限制）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val serviceIntent = Intent(context, ApexCoreService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }.onFailure { e ->
                // 不外抛：FGS 后台启动限制属预期拦截，非缺陷
                Log.w(
                    "ApexAgent",
                    "Boot auto-start blocked by system FGS policy: ${e.javaClass.simpleName}"
                )
            }
        }
    }
}
