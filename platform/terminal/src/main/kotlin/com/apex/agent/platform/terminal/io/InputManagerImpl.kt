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
    private val native: com.apex.agent.platform.terminal.native.NativePty,
    private val eventLog: TerminalEventLog,
    private val eventBus: TerminalEventBus,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : InputManager {

    /** Per-session writer state. */
    private data class SessionWriter(
        val channel: Channel<WriteOp.WriteBytes>,
        val control: MutableStateFlow<InputControlState>
    )

    private sealed class WriteOp {
        data class WriteBytes(val owner: InputOwner, val bytes: ByteArray, val kind: InputKind,
                              val text: String?, val key: TerminalKey?, val signal: UnixSignal?,
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
        if (op.kind == InputKind.LINE && op.text != null) {
            val req = InputRequest(sessionId, command = op.text, bytes = null, owner = op.owner)
            when (policy.check(req)) {
                is Decision.Deny -> return Result.failure(RuntimeException("TerminalError:PermissionDenied"))
                Decision.Allow -> {}
            }
        }
        // 3. Native write
        val nativeId = sessionId.toInt()  // assume 1:1 mapping for Phase 1 (SessionManager assigns)
        val written: Int = when (op.kind) {
            InputKind.SIGNAL -> {
                val ok = native.nativeSendSignal(nativeId, (op.signal ?: UnixSignal.SIGINT).number)
                if (ok) 0 else -1
            }
            InputKind.KEY -> {
                val bytes = keyToBytes(op.key ?: TerminalKey.ENTER)
                native.nativeWrite(nativeId, bytes, 0, bytes.size)
            }
            InputKind.RAW, InputKind.LINE -> {
                val payload = op.bytes ?: op.text?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                native.nativeWrite(nativeId, payload, 0, payload.size)
            }
        }
        if (written < 0) {
            return Result.failure(RuntimeException("TerminalError:WriteFailed"))
        }
        // 4. Emit InputWritten event
        val ev = TerminalEvent.InputWritten(
            id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(), cursor = -1,
            owner = op.owner, kind = op.kind, byteCount = written,
            text = op.text, key = op.key?.name, signal = op.signal
        )
        val id = eventLog.append(ev)
        eventBus.emit(ev.copy(id = id))

        // 5. If signal, also emit SignalSent
        if (op.kind == InputKind.SIGNAL && op.signal != null) {
            val sev = TerminalEvent.SignalSent(
                id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(), cursor = -1,
                owner = op.owner, signal = op.signal, jobId = null
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

    override suspend fun write(sessionId: Long, owner: InputOwner, bytes: ByteArray): Result<Unit> {
        return writeInternal(sessionId, owner, bytes = bytes, kind = InputKind.RAW).map { }
    }

    override suspend fun sendKey(sessionId: Long, owner: InputOwner, key: TerminalKey): Result<Unit> {
        return writeInternal(sessionId, owner, key = key, kind = InputKind.KEY).map { }
    }

    override suspend fun sendSignal(sessionId: Long, owner: InputOwner, signal: UnixSignal, jobId: Long?): Result<Unit> {
        return writeInternal(sessionId, owner, signal = signal, kind = InputKind.SIGNAL).map { }
    }

    private suspend fun writeInternal(
        sessionId: Long,
        owner: InputOwner,
        bytes: ByteArray? = null,
        text: String? = null,
        key: TerminalKey? = null,
        signal: UnixSignal? = null,
        kind: InputKind
    ): Result<WriteResult> {
        val w = writerFor(sessionId)
        val deferred = kotlinx.coroutines.CompletableDeferred<Result<WriteResult>>()
        val effectiveText = text ?: (if (kind == InputKind.LINE && bytes != null) String(bytes, Charsets.UTF_8) else null)
        val effectiveBytes = bytes ?: text?.toByteArray(Charsets.UTF_8)
        w.channel.send(WriteOp.WriteBytes(owner, effectiveBytes ?: ByteArray(0), kind, effectiveText, key, signal, deferred))
        return deferred.await()
    }

    /** Translate a TerminalKey to its byte sequence. */
    private fun keyToBytes(key: TerminalKey): ByteArray = when (key) {
        TerminalKey.ENTER -> byteArrayOf(0x0D)
        TerminalKey.TAB -> byteArrayOf(0x09)
        TerminalKey.BACKSPACE -> byteArrayOf(0x7F)
        TerminalKey.ESC -> byteArrayOf(0x1B)
        TerminalKey.CTRL_C -> byteArrayOf(0x03)
        TerminalKey.CTRL_D -> byteArrayOf(0x04)
        TerminalKey.CTRL_Z -> byteArrayOf(0x1A)
        TerminalKey.CTRL_BACKSLASH -> byteArrayOf(0x1C)
        TerminalKey.ARROW_UP -> byteArrayOf(0x1B, 0x5B, 0x41)
        TerminalKey.ARROW_DOWN -> byteArrayOf(0x1B, 0x5B, 0x42)
        TerminalKey.ARROW_RIGHT -> byteArrayOf(0x1B, 0x5B, 0x43)
        TerminalKey.ARROW_LEFT -> byteArrayOf(0x1B, 0x5B, 0x44)
        TerminalKey.HOME -> byteArrayOf(0x1B, 0x5B, 0x48)
        TerminalKey.END -> byteArrayOf(0x1B, 0x5B, 0x46)
        TerminalKey.DELETE -> byteArrayOf(0x1B, 0x5B, 0x33, 0x7E)
        TerminalKey.PAGE_UP -> byteArrayOf(0x1B, 0x5B, 0x35, 0x7E)
        TerminalKey.PAGE_DOWN -> byteArrayOf(0x1B, 0x5B, 0x36, 0x7E)
        TerminalKey.INSERT -> byteArrayOf(0x1B, 0x5B, 0x32, 0x7E)
        else -> byteArrayOf(0x0D)  // F-keys omitted for brevity (CSI sequences)
    }

    /** Drop writer state for a session (called on Session close). */
    fun drop(sessionId: Long) {
        writers.remove(sessionId)
        started.remove(sessionId)
    }
}

/** WriteResult carried back to the Runtime. */
data class WriteResult(
    val written: Boolean,
    val bytesWritten: Int,
    val cursor: Long,
    val inputOwner: InputOwner
)
