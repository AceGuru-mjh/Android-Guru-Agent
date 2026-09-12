package com.apex.agent.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.apex.agent.platform.terminal.bridge.GuestBridgeHandler

/**
 * T82 — apexctl 桥的 Android 侧 handler 集（Termux:API 等价物的宿主实现）。
 *
 * guest 内用法（安装 rootfs 后自动可用，`/root/.apex/bin/apexctl`）：
 * ```
 * apexctl clipboard get
 * apexctl clipboard set '"hello from ubuntu"'
 * apexctl device-info
 * apexctl battery
 * ```
 *
 * Agent 侧同一 handler 集经 `terminal.bridge call` 直连（无文件往返）。
 *
 * 诚实语义：ClipboardManager / BatteryManager 拿不到（极端 ROM 限制）→
 * 抛异常 → 响应 ok:false + error（绝不编造数据）。
 */
object AndroidBridgeHandlers {

    private class ClipboardGet(private val context: Context) : GuestBridgeHandler {
        override val action = "clipboard"
        override val description = "get/set Android clipboard. args: 'get' | 'set \"<text>\"'"

        override suspend fun handle(argsJson: String): String {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: throw IllegalStateException("clipboard service unavailable")
            val arg = argsJson.trim().removeSurrounding("\"").trim()
            if (arg.equals("get", ignoreCase = true)) {
                return cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    ?: ""   // 空剪贴板 = 空串（语义清晰，非错误）
            }
            if (arg.startsWith("set", ignoreCase = true)) {
                val text = argsJson.trim()
                    .removePrefix("set")
                    .trim()
                    .removeSurrounding("\"")
                cm.setPrimaryClip(ClipData.newPlainText("apexctl", text))
                return "ok"
            }
            throw IllegalArgumentException("usage: apexctl clipboard get | apexctl clipboard set \"<text>\"")
        }
    }

    private class DeviceInfo(private val context: Context) : GuestBridgeHandler {
        override val action = "device-info"
        override val description = "Android device info (model/brand/SDK/abi) as one-line text"

        override suspend fun handle(argsJson: String): String =
            "model=${Build.MODEL} brand=${Build.BRAND} manufacturer=${Build.MANUFACTURER} " +
                "android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) " +
                "abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} " +
                "pkg=${context.packageName}"
    }

    private class Battery(private val context: Context) : GuestBridgeHandler {
        override val action = "battery"
        override val description = "battery status (termux-battery-status equivalent, one-line)"

        override suspend fun handle(argsJson: String): String {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: throw IllegalStateException("battery service unavailable")
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val statusInt = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val status = when (statusInt) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
                BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
                else -> "UNKNOWN($statusInt)"
            }
            return "level=$level% status=$status"
        }
    }

    /** 注册集（TerminalModule 注入 GuestBridgeService）。 */
    fun all(context: Context): List<GuestBridgeHandler> = listOf(
        ClipboardGet(context),
        DeviceInfo(context),
        Battery(context)
    )
}
