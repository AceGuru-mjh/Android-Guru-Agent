package com.apex.agent.platform.terminal.io

import com.apex.agent.platform.terminal.errors.TerminalError
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBus
import com.apex.agent.platform.terminal.events.TerminalEventLog
import com.apex.agent.platform.terminal.policy.Decision
import com.apex.agent.platform.terminal.policy.InputRequest
import com.apex.agent.platform.terminal.policy.TerminalPolicy
import com.apex.agent.platform.terminal.state.SemanticStateReducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Concrete InputManager. The ONLY entry point for nativeWrite.
 *
 * Spec ref: ATR 2.0 Final Spec §6.5 / §11 / §17
 *
 *   - Serializes writes via a per-session Channel (single Writer coroutine).
 *   - Enforces InputControlState (TAKEOVER → reject Agent; INTERRUPTED → reject all).
 *   - Consults PolicyEngine before writing.
 *   - Emits InputWritten / SignalSent events.
 *   - Owner is auto-injected by the Runtime (TerminalRuntimeImpl) before calling here;
 *     this class trusts the owner passed in (it does NOT re-derive it).
 */
class InputManagerImpl(
    override val policy: TerminalPolicy,
    private val native: com.apex.agent.platform.terminal.pty.NativePty,
    private val eventLog: TerminalEventLog,
    private val eventBus: TerminalEventBus,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : InputManager {

    /**
     * P70-4: runtime sessionId (Long) → native session id (Int) 的真实映射解析器。
     *
     * 旧实现直接 `sessionId.toInt()` 并假设两套计数器 1:1 —— 但 Kotlin 端
     * SessionManagerImpl.idCounter 随 Runtime 实例重建归零，而 native 端
     * PtyEngine::nextId_ 是进程级单例永不归零。Runtime 在同一进程内重建后映射失步，
     * write/signal 会打到错误的 native session（串 session）。
     *
     * TerminalRuntimeImpl 在构造完成后接线为
     * `{ sid -> sessionManager.assembly(sid)?.nativeSessionId }`。
     * 解析不到（会话不存在/已关闭）→ 拒绝写入（绝不落到“猜来的” native id 上）。
     */
    @Volatile
    internal var nativeIdResolver: (Long) -> Int? = { null }

    /**
     * T82：会话 VT 模式提供者（DECCKM/括号粘贴）—— TerminalRuntimeImpl 构造后
     * 接线为 `{ sid -> assembly(sid)?.virtualTerminal … }`。未接线（测试直构）→
 *     恒 null：按 CSI 翻译方向键、粘贴不包裹（历史行为）。
     */
    @Volatile
    internal var vtModeProvider: (Long) -> VtInputModes? = { null }

    /** Per-session writer state. */
    private data class SessionWriter(
        val channel: Channel<WriteOp.WriteBytes>,
        val control: MutableStateFlow<InputControlState>
    )

    private sealed class WriteOp {
        data class WriteBytes(val owner: InputOwner, val bytes: ByteArray, val kind: InputKind,
                              val text: String?, val key: TerminalKey?, val signal: UnixSignal?,
                              /** T82：SIGNAL 的目标范围 —— true = 仅前台作业组（shell 存活）。 */
                              val fgScope: Boolean = false,
                              /** T82：SIGNAL 携带的 jobId（SignalSent 事件不再丢弃目标 —— E-17）。 */
                              val jobId: Long? = null,
                              /** T82：LINE 策略检查基准（marker 包装行 → 原命令；null = 用 text）。 */
                              val policyBasis: String? = null,
                              val result: kotlinx.coroutines.CompletableDeferred<Result<WriteResult>>)
    }

    private val writers = ConcurrentHashMap<Long, SessionWriter>()
    private val regLock = Mutex()

    private fun writerFor(sessionId: Long): SessionWriter = writers.computeIfAbsent(sessionId) {
        val ch = Channel<WriteOp.WriteBytes>(Channel.UNLIMITED)
        val control = MutableStateFlow(InputControlState.FREE)
        SessionWriter(ch, control)
    }.also { writer ->
        // start the writer coroutine lazily on first access
        startWriter(sessionId, writer)
    }

    private val started = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    /** TM4 (P83)：未映射按键一次性告警的去重集合。 */
    private val warnedUnmappedKeys = java.util.Collections.newSetFromMap(ConcurrentHashMap<TerminalKey, Boolean>())


    private fun startWriter(sessionId: Long, writer: SessionWriter) {
        if (!started.add(sessionId)) return  // already started
        scope.launch {
            for (op in writer.channel) {
                try {
                    val r = doWrite(sessionId, writer.control.value, op)
                    op.result.complete(r)
                } catch (e: Throwable) {
                    op.result.complete(Result.failure(e))
                }
            }
        }
    }

    private suspend fun doWrite(sessionId: Long, control: InputControlState, op: WriteOp.WriteBytes): Result<WriteResult> {
        // 1. ControlMode check
        if (op.owner == InputOwner.AGENT && !control.agentCanWrite) {
            return Result.failure(RuntimeException("TerminalError:OwnerBusy"))
        }
        // 2. Policy check (only for LINE/RAW that look like commands)
        //    T82：检查基准 = policyBasis（marker 包装时为原命令）—— 对 Agent 意图判定。
        if (op.kind == InputKind.LINE && op.text != null) {
            val basis = op.policyBasis ?: op.text
            val req = InputRequest(sessionId, command = basis, bytes = null, owner = op.owner)
            when (policy.check(req)) {
                is Decision.Deny -> return Result.failure(RuntimeException("TerminalError:PermissionDenied"))
                Decision.Allow -> {}
            }
        }
        // 3. Resolve the REAL native session id (P70-4 — never guess via sessionId.toInt()).
        //    Unresolvable = session does not exist / already closed → refuse the write.
        val nativeId = nativeIdResolver(sessionId)
            ?: return Result.failure(RuntimeException("TerminalError:WriteFailed"))
        // 4. Native write
        val written: Int = when (op.kind) {
            InputKind.SIGNAL -> {
                if (op.fgScope) {
                    // T82：仅前台作业组 —— 无前台作业时返回 0（非错误：调用方退化到
                    // session 级信号）。shell 不受影响。
                    val ok = native.nativeSignalForegroundGroup(nativeId, (op.signal ?: UnixSignal.SIGINT).number)
                    if (ok) 1 else 0
                } else {
                    val ok = native.nativeSendSignal(nativeId, (op.signal ?: UnixSignal.SIGINT).number)
                    if (ok) 0 else -1
                }
            }
            InputKind.KEY -> {
                val bytes = keyToBytes(op.key ?: TerminalKey.ENTER, sessionId)
                native.nativeWrite(nativeId, bytes, 0, bytes.size)
            }
            InputKind.RAW, InputKind.LINE, InputKind.PASTE -> {
                val payload = op.bytes ?: op.text?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                native.nativeWrite(nativeId, payload, 0, payload.size)
            }
        }
        if (written < 0) {
            return Result.failure(RuntimeException("TerminalError:WriteFailed"))
        }
        // 5. Emit InputWritten event
        val ev = TerminalEvent.InputWritten(
            id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(), cursor = -1,
            owner = op.owner, kind = op.kind, byteCount = written,
            text = op.text, key = op.key?.name, signal = op.signal
        )
        val id = eventLog.append(ev)
        eventBus.emit(ev.copy(id = id))

        // 6. If signal, also emit SignalSent
        if (op.kind == InputKind.SIGNAL && op.signal != null) {
            val sev = TerminalEvent.SignalSent(
                id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(), cursor = -1,
                owner = op.owner, signal = op.signal,
                // T82 (E-17)：不再丢弃 jobId —— 事件可审计「哪个 job 被信号」。
                jobId = op.jobId
            )
            val sid = eventLog.append(sev)
            eventBus.emit(sev.copy(id = sid))
            // UserInterrupt semantic alias
            if (op.signal == UnixSignal.SIGINT && op.owner == InputOwner.USER) {
                val uev = TerminalEvent.UserInterrupt(
                    id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(), cursor = -1, jobId = null
                )
                val uid = eventLog.append(uev)
                eventBus.emit(uev.copy(id = uid))
            }
        }
        return Result.success(WriteResult(written = true, bytesWritten = written, cursor = 0L, inputOwner = op.owner))
    }

    override fun controlState(sessionId: Long): StateFlow<InputControlState> =
        writerFor(sessionId).control.asStateFlow()

    override suspend fun requestTakeover(sessionId: Long): Result<Unit> {
        val w = writerFor(sessionId)
        val cur = w.control.value
        if (cur.mode == ControlMode.INTERRUPTED) return Result.failure(RuntimeException("TerminalError:OwnerBusy"))
        w.control.value = InputControlState(InputOwner.USER, ControlMode.TAKEOVER)
        return Result.success(Unit)
    }

    override suspend fun releaseTakeover(sessionId: Long): Result<Unit> {
        val w = writerFor(sessionId)
        w.control.value = InputControlState(InputOwner.AGENT, ControlMode.NORMAL)
        return Result.success(Unit)
    }

    override suspend fun write(sessionId: Long, owner: InputOwner, bytes: ByteArray): Result<WriteResult> {
        return writeInternal(sessionId, owner, bytes = bytes, kind = InputKind.RAW)
    }

    /**
     * T81 (D-2)：显式覆写 sendLine —— 此前沿用 [TerminalInput] 接口 default 实现
     * `write(text+"\n".toByteArray())`，全部降级为 RAW 类型落盘，而 doWrite 的
     * PolicyEngine 门禁只对 `kind == LINE` 生效 → **生产路径所有命令全部绕过策略**
     *（门禁死代码）。覆写后 LINE 语义（+ 恰好一次 '\n' + policy 检查）恢复。
     */
    override suspend fun sendLine(
        sessionId: Long, owner: InputOwner, text: String,
        policyCommand: String?
    ): Result<WriteResult> {
        // T82：策略基准 = 调用方指定的原命令（marker 包装行对策略不可见——插桩非意图）。
        return writeInternal(
            sessionId, owner, text = text + "\n", kind = InputKind.LINE,
            policyBasis = policyCommand ?: text
        )
    }

    override suspend fun sendKey(sessionId: Long, owner: InputOwner, key: TerminalKey): Result<WriteResult> {
        return writeInternal(sessionId, owner, key = key, kind = InputKind.KEY)
    }

    /**
     * T82：括号粘贴（mode 2004）。VT 开启 → 包裹 ESC[200~ … ESC[201~；未开启 →
     * 原样字节（与 RAW 一致，但**不追加换行** —— 粘贴语义）。策略检查与 LINE
     * 同口径（粘贴可携带命令文本）。
     */
    override suspend fun sendPaste(sessionId: Long, owner: InputOwner, text: String): Result<WriteResult> {
        val policy = InputRequest(sessionId, command = text.take(4096), bytes = null, owner = owner)
        when (this.policy.check(policy)) {
            is Decision.Deny -> return Result.failure(RuntimeException("TerminalError:PermissionDenied"))
            Decision.Allow -> {}
        }
        val modes = vtModeProvider(sessionId)
        val payload = if (modes?.bracketedPaste == true) {
            val body = text.toByteArray(Charsets.UTF_8)
            ByteArray(PASTE_PREFIX.size + body.size + PASTE_SUFFIX.size).also {
                System.arraycopy(PASTE_PREFIX, 0, it, 0, PASTE_PREFIX.size)
                System.arraycopy(body, 0, it, PASTE_PREFIX.size, body.size)
                System.arraycopy(PASTE_SUFFIX, 0, it, PASTE_PREFIX.size + body.size, PASTE_SUFFIX.size)
            }
        } else {
            text.toByteArray(Charsets.UTF_8)
        }
        return writeInternal(sessionId, owner, bytes = payload, text = text, kind = InputKind.PASTE)
    }

    override suspend fun sendSignal(sessionId: Long, owner: InputOwner, signal: UnixSignal, jobId: Long?): Result<Unit> {
        return writeInternal(sessionId, owner, signal = signal, kind = InputKind.SIGNAL, jobId = jobId).map { }
    }

    /**
     * T82：只向控制终端前台作业组（tcgetpgrp）发信号 —— shell 存活（Ctrl-C 语义）。
     *
     * @return Result.success(WriteResult(bytesWritten>0))=已送达；bytesWritten==0 =
     * 无前台作业（shell 自身在前台 —— 调用方可退化到 session 级信号或直接放弃）。
     */
    override suspend fun sendForegroundSignal(sessionId: Long, owner: InputOwner, signal: UnixSignal, jobId: Long?): Result<WriteResult> {
        return writeInternal(sessionId, owner, signal = signal, kind = InputKind.SIGNAL, fgScope = true, jobId = jobId)
    }

    // PR #52 §1: stdin lifecycle — EOF via Ctrl+D (byte 0x04). Distinct from close() (PTY teardown).
    override suspend fun closeStdin(sessionId: Long, owner: InputOwner): Result<Unit> {
        // Ctrl+D = EOT (0x04) — signals EOF to the foreground process reading stdin.
        // This does NOT close the PTY; the shell continues running and can accept new commands.
        return write(sessionId, owner, byteArrayOf(0x04)).map { }
    }

    private suspend fun writeInternal(
        sessionId: Long,
        owner: InputOwner,
        bytes: ByteArray? = null,
        text: String? = null,
        key: TerminalKey? = null,
        signal: UnixSignal? = null,
        kind: InputKind,
        fgScope: Boolean = false,
        jobId: Long? = null,
        policyBasis: String? = null
    ): Result<WriteResult> {
        val w = writerFor(sessionId)
        val deferred = kotlinx.coroutines.CompletableDeferred<Result<WriteResult>>()
        val effectiveText = text ?: (if (kind == InputKind.LINE && bytes != null) String(bytes, Charsets.UTF_8) else null)
        val effectiveBytes = bytes ?: text?.toByteArray(Charsets.UTF_8)
        w.channel.send(WriteOp.WriteBytes(owner, effectiveBytes ?: ByteArray(0), kind, effectiveText, key, signal, fgScope, jobId, policyBasis, deferred))
        return deferred.await()
    }

    /**
     * Translate a TerminalKey to its byte sequence.
     *
     * T82（Termux 基线 §1.15/§1.16）：
     * • F1–F12 映射补齐（F1–F4 = SS3，F5–F12 = CSI ~ 序列 —— xterm 标准）；
     * • DECCKM 感知 —— 会话 VT 开启应用光标模式时，方向键/Home/End 发 SS3
     *   （ESC O A/B/C/D/H/F），否则发 CSI 序列。vim/less 等在 DECCKM 下只认 SS3。
     */
    private fun keyToBytes(key: TerminalKey, sessionId: Long = 0L): ByteArray {
        val appMode = vtModeProvider(sessionId)?.applicationCursorKeys == true
        return when (key) {
            TerminalKey.ENTER -> byteArrayOf(0x0D)
            TerminalKey.TAB -> byteArrayOf(0x09)
            TerminalKey.BACKSPACE -> byteArrayOf(0x7F)
            TerminalKey.ESC -> byteArrayOf(0x1B)
            TerminalKey.CTRL_C -> byteArrayOf(0x03)
            TerminalKey.CTRL_D -> byteArrayOf(0x04)
            TerminalKey.CTRL_Z -> byteArrayOf(0x1A)
            TerminalKey.CTRL_BACKSLASH -> byteArrayOf(0x1C)
            TerminalKey.ARROW_UP -> if (appMode) ss3('A') else byteArrayOf(0x1B, 0x5B, 0x41)
            TerminalKey.ARROW_DOWN -> if (appMode) ss3('B') else byteArrayOf(0x1B, 0x5B, 0x42)
            TerminalKey.ARROW_RIGHT -> if (appMode) ss3('C') else byteArrayOf(0x1B, 0x5B, 0x43)
            TerminalKey.ARROW_LEFT -> if (appMode) ss3('D') else byteArrayOf(0x1B, 0x5B, 0x44)
            TerminalKey.HOME -> if (appMode) ss3('H') else byteArrayOf(0x1B, 0x5B, 0x48)
            TerminalKey.END -> if (appMode) ss3('F') else byteArrayOf(0x1B, 0x5B, 0x46)
            TerminalKey.DELETE -> byteArrayOf(0x1B, 0x5B, 0x33, 0x7E)
            TerminalKey.PAGE_UP -> byteArrayOf(0x1B, 0x5B, 0x35, 0x7E)
            TerminalKey.PAGE_DOWN -> byteArrayOf(0x1B, 0x5B, 0x36, 0x7E)
            TerminalKey.INSERT -> byteArrayOf(0x1B, 0x5B, 0x32, 0x7E)
            // T82：F 键补齐 —— F1–F4（SS3 P/Q/R/S）与 F5–F12（CSI 15/17/18/19/20/21/23/24 ~）
            TerminalKey.F1 -> ss3('P')
            TerminalKey.F2 -> ss3('Q')
            TerminalKey.F3 -> ss3('R')
            TerminalKey.F4 -> ss3('S')
            TerminalKey.F5 -> csiTilde(15)
            TerminalKey.F6 -> csiTilde(17)
            TerminalKey.F7 -> csiTilde(18)
            TerminalKey.F8 -> csiTilde(19)
            TerminalKey.F9 -> csiTilde(20)
            TerminalKey.F10 -> csiTilde(21)
            TerminalKey.F11 -> csiTilde(23)
            TerminalKey.F12 -> csiTilde(24)
            // TM4 (P83)：未映射键绝不能静默发 ENTER —— 否则可能替 Agent 确认
            // 破坏性确认框（"Remove file? [y/N]"）。不发字节并按键告警一次。
            else -> {
                warnUnmappedKeyOnce(key)
                byteArrayOf()
            }
        }
    }

    /**
     * TM4 (P83)：未映射按键一次性告警 —— 每个未知 TerminalKey 只提示一次，
     * 便于诊断而不过载 stderr。键映射表保持穷举 + else 双保险：
     * 枚举新增值时第一时间可见，且绝不静默退化成 ENTER。
     */
    private fun warnUnmappedKeyOnce(key: TerminalKey) {
        if (warnedUnmappedKeys.add(key)) {
            // This module has no logging framework dependency; System.err is the
            // lightest diagnostic channel (consistent with the existing diagnostic
            // prints elsewhere in the IO layer).
            System.err.println(
                "[InputManagerImpl] WARN: unmapped TerminalKey '$key' — no bytes sent " +
                    "(was previously silently ENTER, which could confirm destructive prompts)"
            )
        }
    }

    /** ESC O <c> — SS3 序列（应用光标模式/F1–F4）。 */
    private fun ss3(c: Char): ByteArray = byteArrayOf(0x1B, 'O'.code.toByte(), c.code.toByte())

    /** ESC [ <n> ~ — CSI 波浪线序列（F5–F12/编辑键）。 */
    private fun csiTilde(n: Int): ByteArray = "\u001b[${n}~".toByteArray(Charsets.US_ASCII)

    companion object {
        /** T82：括号粘贴包裹符（xterm 2004 模式）。 */
        internal val PASTE_PREFIX = byteArrayOf(0x1B, 0x5B, 0x32, 0x30, 0x30, 0x7E)   // ESC [ 2 0 0 ~
        internal val PASTE_SUFFIX = byteArrayOf(0x1B, 0x5B, 0x32, 0x30, 0x31, 0x7E)   // ESC [ 2 0 1 ~
    }

    /** Drop writer state for a session (called on Session close). */
    fun drop(sessionId: Long) {
        // TM3: close the channel so the writer coroutine's `for (op in channel)` loop
        // terminates naturally — otherwise the writer stays suspended on the channel
        // forever (one leaked coroutine per closed session). Channel.close() drains
        // remaining elements then completes the iterator, letting the writer exit.
        val w = writers.remove(sessionId)
        started.remove(sessionId)
        w?.channel?.close()
    }
}

/** WriteResult carried back to the Runtime. */
data class WriteResult(
    val written: Boolean,
    val bytesWritten: Int,
    val cursor: Long,
    val inputOwner: InputOwner
)
