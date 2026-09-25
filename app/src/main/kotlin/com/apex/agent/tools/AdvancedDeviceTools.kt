package com.apex.agent.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// ═══════════════════════════ 参数读取助手（与 VaultAgentTools 同款）═══════════════════════════

/** 解析工具参数 JSON；非法 JSON / 非对象 → null（调用方返回 Error 文本）。 */
internal fun parseToolArgs(arguments: String): JsonObject? = try {
    Json.parseToJsonElement(arguments).jsonObject
} catch (e: Exception) {
    null
}

internal fun JsonObject.stringOf(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

internal fun JsonObject.intOf(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

internal fun JsonObject.doubleOf(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

/** 非法参数 JSON 的统一错误文本（保持与核心工具一致的 Error: 前缀协议）。 */
internal fun argsParseErrorMessage(raw: String): String =
    "Error: invalid arguments (expected a JSON object, got: ${raw.take(80)})"

// ═══════════════════════════ torch ═══════════════════════════

/**
 * 手电筒开关（#172 高级设备工具包）。
 *
 * 工具本体只做参数解析（JVM 可测的 [normalizeTorchOp]）；CameraManager
 * 侧由注入的 `torch(Boolean) -> String` lambda 完成 —— app 接线见
 * [CameraTorchController]。失败路径（无闪光灯单元 / 相机被占用）由
 * lambda 返回带明确指引的 Error 文本。
 */
class TorchTool(
    /** true=开 / false=关；返回结果描述或 Error 文本。 */
    private val torch: (Boolean) -> String
) : AgentTool {
    override val id = "torch"
    override val name = "Torch (Flashlight)"
    override val description = """
        Turn the device flashlight (camera flash unit) on or off.
        Input: {"op": "on"} or {"op": "off"}.
        Returns the new state; errors explain when no flash unit exists or the
        camera is busy. 手电筒开关（op: on|off）。
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{
            "op":{"type":"string","enum":["on","off"],"description":"on | off"}
        },"required":["op"]}
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SYSTEM)
        risk(ToolRisk.LOW)
        tag("torch", "flashlight", "camera", "device")
        annotations { idempotentWrite() }
    }

    override suspend fun execute(arguments: String): String {
        val json = parseToolArgs(arguments) ?: return argsParseErrorMessage(arguments)
        val op = normalizeTorchOp(json.stringOf("op"))
            ?: return "Error: 'op' must be \"on\" or \"off\"."
        return torch(op == "on")
    }

    companion object {
        /** 参数归一（JVM 可测）：null → 非法。 */
        fun normalizeTorchOp(raw: String?): String? = when (raw?.trim()?.lowercase()) {
            "on" -> "on"
            "off" -> "off"
            else -> null
        }
    }
}

/** [TorchTool] 的生产接线：遍历 cameraIdList 找闪光灯单元并 setTorchMode。 */
class CameraTorchController(private val context: Context) {
    private val cameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    operator fun invoke(on: Boolean): String {
        val flashId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            return "Error: cannot enumerate cameras (flashlight unavailable): ${e.message ?: e::class.simpleName}"
        } ?: return "Error: no flash unit on this device — flashlight is not supported. Suggest screen brightness instead."
        return try {
            cameraManager.setTorchMode(flashId, on)
            "Torch ${if (on) "ON" else "OFF"} (camera $flashId)."
        } catch (e: Exception) {
            "Error: cannot switch torch (camera may be in use): ${e.message ?: e::class.simpleName}. " +
                "Ask the user to close camera apps and retry."
        }
    }
}

// ═══════════════════════════ vibrate ═══════════════════════════

/**
 * 振动反馈（#172）。参数：duration_ms（默认 300）、pattern（可选，
 * 逗号分隔的毫秒序列，交替 静音-振动；与 duration_ms 二选一，pattern 优先）。
 * API 31+ 用 VibratorManager，低版本退回旧 Vibrator；无振动器明确报错。
 */
class VibrateTool(
    /** (durationMs, pattern?) → 结果文本。pattern 非空时忽略 durationMs。 */
    private val vibrate: (Long, LongArray?) -> String
) : AgentTool {
    override val id = "vibrate"
    override val name = "Vibrate"
    override val description = """
        Vibrate the device. Input: {"duration_ms": 300} or
        {"pattern": "0,200,100,200"} (comma-separated ms, alternate off/on).
        pattern wins over duration_ms. 振动（duration_ms 默认 300；pattern 可选）。
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{
            "duration_ms":{"type":"integer","default":300,"minimum":1,"maximum":10000,"description":"Vibration duration in ms (default 300)"},
            "pattern":{"type":"string","description":"Comma-separated ms pattern (alternate off/on), e.g. 0,200,100,200"}
        },"required":[]}
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SYSTEM)
        risk(ToolRisk.LOW)
        tag("vibrate", "haptics", "device")
        annotations { idempotentWrite() }
    }

    override suspend fun execute(arguments: String): String {
        val json = parseToolArgs(arguments) ?: return argsParseErrorMessage(arguments)
        val duration = normalizeDurationMs(json.intOf("duration_ms"))
            ?: return "Error: 'duration_ms' must be 1..$MAX_DURATION_MS ms."
        val patternRaw = parsePattern(json.stringOf("pattern"))
        if (patternRaw != null && patternRaw.isEmpty()) {
            return "Error: 'pattern' must be comma-separated milliseconds (e.g. \"0,200,100,200\"), each 0..1000."
        }
        return vibrate(duration.toLong(), patternRaw)
    }

    companion object {
        const val MAX_DURATION_MS = 10_000

        /** duration_ms 归一（JVM 可测）：未提供 → 默认 300；非法（超界）→ null。 */
        fun normalizeDurationMs(raw: Int?): Int? {
            val v = raw ?: 300
            return if (v in 1..MAX_DURATION_MS) v else null
        }

        /**
         * pattern 解析（JVM 可测）：null=未提供；空数组=格式非法（含非数字段）；
         * 非空数组=“静音,振动,静音…”毫秒序列（2..16 段，每段 0..1000）。
         */
        fun parsePattern(raw: String?): LongArray? {
            if (raw.isNullOrBlank()) return null
            val segments = raw.split(',')
            val parts = segments.mapNotNull { it.trim().toLongOrNull() }
            if (parts.size != segments.size || parts.size < 2 || parts.size > 16 || parts.any { it < 0 || it > 1000 }) {
                return LongArray(0)
            }
            return parts.toLongArray()
        }
    }
}

/** [VibrateTool] 的生产接线：VibratorManager（API 31+）/ Vibrator 回退。 */
class AndroidVibrator(private val context: Context) {
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    operator fun invoke(durationMs: Long, pattern: LongArray?): String {
        val v = vibrator
            ?: return "Error: no vibrator on this device."
        if (!v.hasVibrator()) {
            return "Error: this device has no vibration motor."
        }
        return try {
            if (pattern != null && pattern.isNotEmpty()) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                "Vibrating pattern ${pattern.joinToString(",")} ms."
            } else {
                v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                "Vibrating for ${durationMs}ms."
            }
        } catch (e: Exception) {
            "Error: vibration failed: ${e.message ?: e::class.simpleName}"
        }
    }
}

// ═══════════════════════════ battery_status ═══════════════════════════

/**
 * 电池状态（#172）。读 ACTION_BATTERY_CHANGED 粘性广播：
 * level/scale/percent/status/plugged/health/temperature(°C)/voltage(mV)。
 * 无参数；纯读。
 */
class BatteryStatusTool(
    private val read: () -> String
) : AgentTool {
    override val id = "battery_status"
    override val name = "Battery Status"
    override val description = """
        Read battery status: percent, charging state, plug type, health,
        temperature (°C) and voltage (mV). No arguments. 电池状态读取（无参数）。
    """.trimIndent()
    override val parametersSchema = """{"type":"object","properties":{},"required":[]}"""

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SYSTEM)
        risk(ToolRisk.LOW)
        tag("battery", "power", "device", "status")
        annotations { readOnly() }
    }

    override suspend fun execute(arguments: String): String = read()
}

/** [BatteryStatusTool] 的生产接线：粘性 Intent 解包。 */
object AndroidBatteryReader {
    fun read(context: Context): String {
        return try {
            val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent == null) {
                return "Error: battery status unavailable (no sticky broadcast)."
            }
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                else -> "unknown"
            }
            val plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "unplugged"
            }
            val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                else -> "unknown"
            }
            val tempTenthC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            buildString {
                appendLine("percent: ${if (percent >= 0) "$percent%" else "unknown"}")
                appendLine("level: $level / $scale")
                appendLine("status: $status")
                appendLine("plugged: $plugged")
                appendLine("health: $health")
                append("temperature: ${if (tempTenthC != Int.MIN_VALUE) "%.1f°C".format(tempTenthC / 10.0) else "unknown"}")
                if (voltageMv != Int.MIN_VALUE) append(" | voltage: ${voltageMv}mV")
            }
        } catch (e: Exception) {
            "Error: battery read failed: ${e.message ?: e::class.simpleName}"
        }
    }
}

// ═══════════════════════════ network_info ═══════════════════════════

/**
 * 网络状态（#172）。活动网络类型（wifi/cell/none/ethernet）+ 接口名/DNS
 * 列表 + 下行带宽；逐项降级（拿不到哪项就标 unknown，不整单失败）。
 * 无参数；纯读。
 */
class NetworkInfoTool(
    private val read: () -> String
) : AgentTool {
    override val id = "network_info"
    override val name = "Network Info"
    override val description = """
        Active network info: type (wifi/cell/ethernet/none), interface name,
        DNS servers, downlink bandwidth estimate. Degrades per-field instead of
        failing whole. No arguments. 网络状态读取（无参数，逐项降级）。
    """.trimIndent()
    override val parametersSchema = """{"type":"object","properties":{},"required":[]}"""

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SYSTEM)
        risk(ToolRisk.LOW)
        tag("network", "wifi", "connectivity", "dns", "device")
        annotations { readOnly() }
    }

    override suspend fun execute(arguments: String): String = read()
}

/** [NetworkInfoTool] 的生产接线：ConnectivityManager 逐项降级读取。 */
object AndroidNetworkInfoReader {
    fun read(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            if (network == null) {
                return "type: none (no active network)"
            }
            val caps: NetworkCapabilities? = try {
                cm.getNetworkCapabilities(network)
            } catch (e: Exception) {
                null
            }
            val link: LinkProperties? = try {
                cm.getLinkProperties(network)
            } catch (e: Exception) {
                null
            }
            val type = when {
                caps == null -> "unknown"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            buildString {
                appendLine("type: $type")
                appendLine("interface: ${link?.interfaceName ?: "unknown"}")
                val dns = link?.dnsServers?.map { it.hostAddress ?: "?" } ?: emptyList()
                appendLine("dns: ${if (dns.isEmpty()) "unknown" else dns.joinToString(", ")}")
                append("downlink_bandwidth: ${caps?.linkDownstreamBandwidthKbps ?: -1} kbps (estimate)")
            }
        } catch (e: Exception) {
            "Error: network info read failed: ${e.message ?: e::class.simpleName}"
        }
    }
}

// ═══════════════════════════ tts_speak ═══════════════════════════

/**
 * 文字转语音朗读（#172）。参数：text（必填）、language（可选 BCP-47 如
 * zh-CN）、pitch/rate（可选，0.5..2.0，默认 1.0）。QUEUE_FLUSH 模式；
 * TTS 引擎异步初始化——首次调用最多等待 2s，未就绪返回"可重试"提示。
 */
class TtsSpeakTool(
    /** (text, language?, pitch?, rate?) → 结果文本。 */
    private val speak: (String, String?, Float, Float) -> String
) : AgentTool {
    override val id = "tts_speak"
    override val name = "Text To Speech"
    override val description = """
        Speak text aloud via the Android TTS engine (flushes previous speech).
        Input: {"text": "hello", "language": "zh-CN", "pitch": 1.0, "rate": 1.0}
        language: optional BCP-47 tag (zh-CN, en-US...); pitch/rate: 0.5..2.0.
        First call may wait up to 2s for engine init — retry on "not ready".
        文字转语音（text 必填；language/pitch/rate 可选）。
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{
            "text":{"type":"string","description":"The text to speak (required)"},
            "language":{"type":"string","description":"BCP-47 language tag, e.g. zh-CN, en-US (optional)"},
            "pitch":{"type":"number","minimum":0.5,"maximum":2.0,"description":"Pitch 0.5..2.0 (default 1.0)"},
            "rate":{"type":"number","minimum":0.5,"maximum":2.0,"description":"Speech rate 0.5..2.0 (default 1.0)"}
        },"required":["text"]}
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SYSTEM)
        risk(ToolRisk.LOW)
        tag("tts", "speech", "audio", "voice")
        annotations { idempotentWrite() }
    }

    override suspend fun execute(arguments: String): String {
        val json = parseToolArgs(arguments) ?: return argsParseErrorMessage(arguments)
        val text = json.stringOf("text")
            ?: return "Error: 'text' is required and must be non-empty."
        val language = json.stringOf("language")
        val pitch = normalizeTtsParam(json.doubleOf("pitch"))
            ?: return "Error: 'pitch' must be a number in 0.5..2.0."
        val rate = normalizeTtsParam(json.doubleOf("rate"))
            ?: return "Error: 'rate' must be a number in 0.5..2.0."
        return speak(text, language, pitch, rate)
    }

    companion object {
        /** pitch/rate 归一（JVM 可测）：未提供 → 默认 1.0；非法（超 0.5..2.0）→ null。 */
        fun normalizeTtsParam(raw: Double?): Float? {
            val v = raw ?: 1.0
            return if (v in 0.5..2.0) v.toFloat() else null
        }
    }
}

/** [TtsSpeakTool] 的生产接线：TextToSpeech 单例惰性初始化 + latch 等待。 */
class TtsSpeaker(private val context: Context) {

    private val engineRef = AtomicReference<TextToSpeech?>(null)
    private val initLatch = CountDownLatch(1)
    @Volatile private var initStarted = false
    @Volatile private var initError: String? = null

    /**
     * 惰性启动引擎（线程安全：仅首个调用者真正 init）。
     *
     * TextToSpeech 构造不阻塞，onInit 回调由框架投递（主线程），因此在
     * 工具执行线程（IO）构造 + [initLatch] 跨线程等待最多 2s 即可，无
     * 死锁风险。未就绪返回 false → 工具层转“可重试”提示。
     */
    private fun ensureEngine(): Boolean {
        if (initLatch.count == 0L) return initError == null
        synchronized(this) {
            if (!initStarted) {
                initStarted = true
                try {
                    engineRef.set(
                        TextToSpeech(context.applicationContext) { status ->
                            if (status != TextToSpeech.SUCCESS) {
                                initError = "engine init status=$status"
                            }
                            initLatch.countDown()
                        }
                    )
                } catch (e: Exception) {
                    initError = e.message ?: e::class.simpleName
                    initLatch.countDown()
                }
            }
        }
        return initLatch.await(2, TimeUnit.SECONDS) && initError == null
    }

    operator fun invoke(text: String, language: String?, pitch: Float, rate: Float): String {
        if (!ensureEngine()) {
            val reason = initError ?: "engine still initializing"
            return "Error: TTS engine not ready ($reason). Retry in a moment — the engine initializes in the background."
        }
        val engine = engineRef.get()
            ?: return "Error: TTS engine unavailable. Retry in a moment."
        return try {
            if (!language.isNullOrBlank()) {
                val parts = language.split('-', '_')
                val locale = if (parts.size >= 2) {
                    Locale(parts[0].lowercase(), parts[1].uppercase())
                } else {
                    Locale.forLanguageTag(language)
                }
                val result = engine.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    return "Error: language '$language' not supported by the TTS engine (code $result). Try en-US or install the language data."
                }
            }
            engine.setPitch(pitch)
            engine.setSpeechRate(rate)
            // 队列模式 QUEUE_FLUSH：新朗读打断旧朗读（agent 语义：最新指令优先）。
            val utteranceId = "apex-tts-${System.currentTimeMillis()}"
            val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (queued == TextToSpeech.SUCCESS) {
                "Speech queued (flush mode, ${text.length} chars, language=${language ?: "default"}, pitch=$pitch, rate=$rate)."
            } else {
                "Error: TTS speak rejected (code $queued)."
            }
        } catch (e: Exception) {
            "Error: TTS speak failed: ${e.message ?: e::class.simpleName}"
        }
    }

    /** 释放引擎（进程内单例，通常不调用；测试/诊断用）。 */
    fun shutdown() {
        runCatching { engineRef.get()?.shutdown() }
        engineRef.set(null)
    }
}
