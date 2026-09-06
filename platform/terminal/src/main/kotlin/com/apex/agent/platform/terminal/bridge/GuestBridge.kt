package com.apex.agent.platform.terminal.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * T82 — apexctl Guest↔Host Bridge（Termux `termux-api` 的架构等价物，基线 §11.1）。
 *
 * ## Termux 的做法
 * Termux:API 是一个独立 app + `termux-battery-status` 等 CLI；CLI 与 API app 经
 * Android IPC 通信。我们没有第二个 app —— 用 **文件队列协议** 在同一个进程内
 * 打通两侧：
 *
 * ```
 * guest 脚本                          host（Android app 进程）
 * ───────────                         ─────────────────────
 * apexctl clipboard get   ──写──▶  <home>/.apex/bridge/req/<id>.json
 * （轮询 resp/<id>.json）  ◀──写── GuestBridgeService 轮询 req/ → handler → resp/
 * ```
 *
 * 关键设计：桥目录放在**持久化 home bind**（host `<filesDir>/linux/home/.apex/bridge`
 * = guest `/root/.apex/bridge`）—— 复用现有 bind，不引入第 4 个 bind 面。host 与
 * guest（PRoot，同 uid）对同一目录读写，无权限问题。
 *
 * ## 协议
 * 请求：`{"id":"<id>","action":"<name>","args":<json>}`
 * 响应：`{"id":"<id>","ok":true,"data":<string>}` 或 `{"id":"<id>","ok":false,"error":"<msg>"}`
 *
 * ## Handler（app 层注册，Android 能力宿主）
 * 生产 DI 注册：clipboard（get/set，ClipboardManager）、device-info、battery。
 * guest 侧 `apexctl <action> [argsJson]` 与 agent 侧 `terminal.bridge call`
 * 走**同一组 handler**（行为对称可测试）。
 *
 * ## 诚实降级
 * - 服务未启动 → 脚本轮询 10s 超时 → `BRIDGE_TIMEOUT`（退出码 1）；
 * - 未知 action → `BRIDGE_UNKNOWN_ACTION`；
 * - handler 失败 → `ok:false` + error。
 */
interface GuestBridgeHandler {
    /** 动作名（apexctl 第一个参数）。 */
    val action: String

    /** 一行描述（terminal.bridge status 输出用）。 */
    val description: String

    /**
     * @param argsJson 调用方传入的 JSON 参数（可能为 "null"/任意 JSON 文本；handler 自行解析）。
     * @return data 字符串（原样进响应 data）或抛异常（→ ok:false）。
     */
    suspend fun handle(argsJson: String): String
}

/** 一次桥调用的响应。 */
data class BridgeResponse(
    val id: String,
    val ok: Boolean,
    val data: String? = null,
    val error: String? = null
) {
    fun toJson(): String {
        val d = data?.let { ",\"data\":" + jsonString(it) } ?: ""
        val e = error?.let { ",\"error\":" + jsonString(it) } ?: ""
        return """{"id":${jsonString(id)},"ok":$ok$d$e}"""
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/**
 * Host 侧桥服务：轮询请求目录 → 分发 → 写响应。同时提供 agent 直连入口
 * [dispatch]（同一 handler 集，无文件往返）。
 */
class GuestBridgeService(
    /** host 侧桥根目录（= 持久化 home 下的 .apex/bridge；调用方保证 home 已 ensureReady）。 */
    private val bridgeRoot: File,
    private val handlers: List<GuestBridgeHandler>,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 500L
) {
    private val handlersByAction = handlers.associateBy { it.action }
    private var pollJob: Job? = null

    val requestDir: File get() = File(bridgeRoot, "req")
    val responseDir: File get() = File(bridgeRoot, "resp")

    /** 已注册动作（诊断/工具输出）。 */
    fun supportedActions(): Map<String, String> = handlersByAction.mapValues { it.value.description }

    /** 确保目录 + apexctl 脚本就位（幂等）。返回动作报告。 */
    fun ensureInstalled(): List<String> {
        val actions = mutableListOf<String>()
        if (!requestDir.isDirectory && requestDir.mkdirs()) actions.add("created ${requestDir.relativeToOrSelf(bridgeRoot.parentFile)}")
        if (!responseDir.isDirectory && responseDir.mkdirs()) actions.add("created resp/")
        val script = File(bridgeRoot.parentFile, "bin/$SCRIPT_NAME")
        if (!script.isFile || script.readText() != ApexBridgeScript.SCRIPT) {
            script.parentFile.mkdirs()
            script.writeText(ApexBridgeScript.SCRIPT)
            script.setExecutable(true, false)
            actions.add("installed $SCRIPT_NAME (guest: /root/.apex/bin/$SCRIPT_NAME)")
        }
        return actions
    }

    /** 启动轮询（幂等）。 */
    fun start(): Boolean {
        ensureInstalled()
        if (pollJob?.isActive == true) return false
        pollJob = scope.launch {
            while (isActive) {
                runCatching { pollOnce() }
                delay(pollIntervalMs)
            }
        }
        return true
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    val isRunning: Boolean get() = pollJob?.isActive == true

    /** 单轮：处理 req/ 下全部请求文件。 */
    internal suspend fun pollOnce(): Int {
        val files = requestDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return 0
        var handled = 0
        for (f in files) {
            val raw = runCatching { f.readText() }.getOrNull()
            if (raw == null) {
                runCatching { f.delete() }
                continue
            }
            val parsed = parseRequest(raw)
            val response = if (parsed == null) {
                BridgeResponse(id = "unknown", ok = false, error = "BRIDGE_MALFORMED_REQUEST")
            } else {
                dispatch(parsed.second, parsed.third)
            }
            val respFile = File(responseDir, "${parsed?.first ?: "unknown"}.json")
            runCatching { respFile.writeText(response.toJson()) }
            runCatching { f.delete() }
            handled++
        }
        return handled
    }

    /**
     * 分发一次调用（agent 直连与 guest 文件队列共用语义）。
     * @return 响应（id 由调用方填充或 unknown）。
     */
    suspend fun dispatch(action: String, argsJson: String): BridgeResponse {
        val handler = handlersByAction[action]
            ?: return BridgeResponse(id = "unknown", ok = false, error = "BRIDGE_UNKNOWN_ACTION")
        return try {
            BridgeResponse(id = "unknown", ok = true, data = handler.handle(argsJson))
        } catch (e: Exception) {
            BridgeResponse(id = "unknown", ok = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** 解析 {"id":..,"action":..,"args":..}（宽松：无 id/args 也可）。 */
    internal fun parseRequest(raw: String): Triple<String, String, String>? {
        val id = Regex(""""id"\s*:\s*"([^"]*)"""").find(raw)?.groupValues?.getOrNull(1) ?: return null
        val action = Regex(""""action"\s*:\s*"([^"]*)"""").find(raw)?.groupValues?.getOrNull(1) ?: return null
        // 125 = closing-brace codepoint（CI 原始字符计数门禁 —— 字面量会破坏平衡）
        val args = Regex(""""args"\s*:\s*(.*)""").find(raw)?.groupValues?.getOrNull(1)
            ?.trim()?.trimEnd(125.toChar())?.trim()?.removeSurrounding("\"")
        return Triple(id, action, args?.takeIf { it.isNotEmpty() } ?: "null")
    }

    companion object {
        const val SCRIPT_NAME = "apexctl"
    }
}

/**
 * guest 侧 `apexctl` 脚本（POSIX sh —— Ubuntu base 的 dash/bash 均可执行）。
 * 写到 host `<home>/.apex/bin/apexctl`，经 home bind 在 guest 内为
 * `/root/.apex/bin/apexctl`。
 */
object ApexBridgeScript {
    val SCRIPT = """
        #!/bin/sh
        # apexctl — Android capability bridge (Android-Guru-Agent T82)
        # Usage: apexctl <action> [argsJson]
        #   e.g. apexctl clipboard get
        #        apexctl clipboard set '"hello from ubuntu"'
        REQ=${'$'}HOME/.apex/bridge/req
        RESP=${'$'}HOME/.apex/bridge/resp
        action="${'$'}1"; args="${'$'}2"; [ -n "${'$'}args" ] || args=null
        id="$$-$(date +%s%N 2>/dev/null || date +%s)"
        mkdir -p "${'$'}REQ" "${'$'}RESP" 2>/dev/null
        printf '{"id":"%s","action":"%s","args":%s}' "${'$'}id" "${'$'}action" "${'$'}args" > "${'$'}REQ/${'$'}id.json" 2>/dev/null || {
          echo '{"ok":false,"error":"BRIDGE_REQ_DIR_UNAVAILABLE"}' >&2; exit 1; }
        i=0
        while [ ${'$'}i -lt 100 ]; do
          if [ -f "${'$'}RESP/${'$'}id.json" ]; then
            cat "${'$'}RESP/${'$'}id.json"; rm -f "${'$'}RESP/${'$'}id.json"; exit 0
          fi
          sleep 0.1 2>/dev/null || sleep 1
          i=$((i+1))
        done
        echo '{"ok":false,"error":"BRIDGE_TIMEOUT"}' >&2
        exit 1
    """.trimIndent()
}
