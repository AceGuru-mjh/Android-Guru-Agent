package com.apex.agent.platform.terminal.io

import com.apex.agent.platform.terminal.errors.TerminalError

/**
 * All input to a Session goes through TerminalInput. There is NO direct nativeWrite.
 *
 * Spec ref: ATR 2.0 Final Spec §14 / §17
 *
 * Pipeline:
 *   InputRequest (sessionId, owner, bytes/key/signal)
 *       ↓
 *   InputManager
 *       ├── 1. validate Session state ∈ {READY, RUNNING, WAITING_INPUT}
 *       ├── 2. InputControlState arbitration (TAKEOVER → reject Agent)
 *       ├── 3. PolicyEngine.check()
 *       ├── 4. serialize → Writer coroutine → nativeWrite / nativeSendSignal
 *       └── 5. emit InputWritten / SignalSent → EventLog + EventBus
 *
 * Owner is AUTO-INJECTED by Runtime based on call origin. Agent tools CANNOT pass owner=USER.
 */
interface TerminalInput {

    /** Write raw bytes. owner is assigned by the Runtime, not by the caller. Returns bytes actually written to the PTY. */
    suspend fun write(sessionId: Long, owner: InputOwner, bytes: ByteArray): Result<WriteResult>

    /** Convenience: write text as UTF-8 bytes, NO newline appended. */
    suspend fun writeRaw(sessionId: Long, owner: InputOwner, text: String): Result<WriteResult> =
        write(sessionId, owner, text.toByteArray(Charsets.UTF_8))

    /**
     * Convenience: write text + "\n" (LINE mode). Most common for running commands.
     *
     * T82：[policyCommand] —— 策略检查的基准命令。Runtime 的 marker 协议会把写入行
     * 包装为 `cmd; printf …`（结构上被判「complex」）—— 策略必须针对 **Agent 请求的
     * 原命令** 判定，而不是 runtime 自己的插桩。null（默认）= 以 [text] 为基准（历史行为）。
     */
    suspend fun sendLine(
        sessionId: Long, owner: InputOwner, text: String,
        policyCommand: String? = null
    ): Result<WriteResult> =
        write(sessionId, owner, (text + "\n").toByteArray(Charsets.UTF_8))

    /** Send a special key (ENTER, CTRL_C, ARROW_UP, ...). */
    suspend fun sendKey(sessionId: Long, owner: InputOwner, key: TerminalKey): Result<WriteResult>

    /** Send a Unix signal (SIGINT/SIGTERM/SIGKILL/...). */
    suspend fun sendSignal(sessionId: Long, owner: InputOwner, signal: UnixSignal, jobId: Long? = null): Result<Unit>

    /**
     * T82：括号粘贴写入（xterm 2004）。VT 开启括号粘贴模式时应包裹
     * ESC[200~/ESC[201~（实现类查询会话 VT 模式）；默认退化 = 原样字节、
     * **不追加换行**（与 LINE 的区别）。粘贴的多行文本由 guest 自行处理。
     */
    suspend fun sendPaste(sessionId: Long, owner: InputOwner, text: String): Result<WriteResult> =
        write(sessionId, owner, text.toByteArray(Charsets.UTF_8))

    // PR #52 §1: stdin lifecycle — closeStdin sends EOF (Ctrl+D), distinct from close() (kills PTY) and signal.
    suspend fun closeStdin(sessionId: Long, owner: InputOwner): Result<Unit>

    /** Convenience: send EOF (Ctrl+D) — equivalent to closeStdin. */
    suspend fun sendEof(sessionId: Long, owner: InputOwner): Result<Unit> = closeStdin(sessionId, owner)
}

/** Common failure reasons for input operations. */
sealed class InputFailure {
    data class TerminalError(val error: com.apex.agent.platform.terminal.errors.TerminalError) : InputFailure()
    data class OwnerBusy(val message: String) : InputFailure()
    data class PolicyDenied(val reason: String) : InputFailure()
}
