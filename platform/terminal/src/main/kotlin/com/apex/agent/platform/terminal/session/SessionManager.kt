package com.apex.agent.platform.terminal.session

import com.apex.agent.platform.terminal.errors.TerminalError
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.runtime.SpawnSpec
import kotlinx.coroutines.flow.Flow

/**
 * Manages Session lifecycle: id allocation, state machine driving, persistence metadata.
 *
 * Spec ref: ATR 2.0 Final Spec §6.2 / §9
 *
 * Single reader / single writer / single state actor per session (§16).
 * SessionManager does NOT read/write PTY directly (that is PtyOutputPump + InputManager).
 */
interface SessionManager {

    /**
     * Create a new Session. Allocates id, runs forkpty, starts PtyOutputPump.
     * Returns the new session in state READY (or BROKEN on failure).
     *
     * State transitions: CREATED → STARTING → READY | BROKEN  (S1 → S2 | S3)
     */
    suspend fun create(
        shell: String = "/system/bin/sh",
        cwd: String = "/sdcard",
        rows: Int = 24,
        cols: Int = 80,
        env: Map<String, String> = emptyMap(),
        privilege: PrivilegeLevel = PrivilegeLevel.NORMAL
    ): Result<TerminalSession>

    /**
     * T73: 从 [SpawnSpec]（ExecutionBackend.prepare 的产物）创建会话 ——
     * 生产路径：TerminalRuntime.create(backendId=…) → backend.prepare() →
     * 本方法 → forkpty(nativeCreateSessionArgv) → execv(argv[0], argv)。
     *
     * 与 [create] 的差异仅在 spawn 点：argv/env/cwd 来自后端翻译
     * （LOCAL: ["/system/bin/sh","-i"]；LINUX: [libproot.so,"-r",…,"--","/bin/bash","-i"]），
     * 其余（装配/pump/事件/状态机）完全共用。
     *
     * TerminalSession.shell/cwd 采用 spec 的展示语义（shellDisplay/cwdDisplay，
     * 回退 argv[0]/spec.cwd）；backend 元数据进入 TerminalSession.backend 并持久化。
     */
    suspend fun createFromSpec(spec: SpawnSpec, rows: Int, cols: Int, privilege: PrivilegeLevel): Result<TerminalSession>

    /** Get current Session snapshot (state may have changed since creation). */
    suspend fun get(id: Long): TerminalSession?

    /** List all non-CLOSED sessions. */
    suspend fun list(): List<TerminalSession>

    /**
     * Close a Session: SIGHUP foreground, reap child, close master fd, free resources.
     * All waiters receive SessionGone. State → CLOSED (terminal).
     * State transitions: any(ALIVE) → CLOSED (S12) | BROKEN → CLOSED (S13) | EXITED → CLOSED (S14)
     *
     * @param force if true, SIGKILL foreground job first.
     */
    suspend fun close(id: Long, force: Boolean = false): Result<Unit>

    /**
     * Subscribe to Session state changes (StateChanged events).
     * Each subscriber gets an independent flow; does NOT affect other subscribers.
     */
    fun observeState(id: Long): Flow<SessionState>

    // PR #54 §5: stop running jobs but keep Session alive (≠ close)
    suspend fun stop(id: Long): Result<SessionState>

    // PR #54 §8/§19: reconcile persisted vs actual — mark dead PTY as LOST
    suspend fun reconcile(persisted: List<Long>): List<ReconciliationResult>

    data class ReconciliationResult(
        val sessionId: Long,
        val persistedState: String,
        val actualState: SessionState,  // LOST if PTY gone
        val recoverable: Boolean
    )
}

/** Typed create failure reasons (mirrors TerminalError subset relevant to SessionManager). */
sealed class SessionCreateError {
    object PtyUnavailable : SessionCreateError()
    data class InvalidInput(val message: String) : SessionCreateError()
    object PermissionDenied : SessionCreateError()
    data class Other(val error: TerminalError) : SessionCreateError()
}
