package com.apex.agent.platform.terminal.io

/**
 * Who owns a piece of input / who currently controls the Session input.
 *
 * Spec ref: ATR 2.0 Final Spec §14 / §16 / §11
 *
 * CRITICAL: Agent tools CANNOT forge owner=USER. Runtime auto-injects owner based on
 * call origin:
 *   - AgentTool invocation  → AGENT
 *   - UI key/IME event      → USER
 *   - Runtime internal call → SYSTEM
 */
enum class InputOwner { FREE, USER, AGENT, SYSTEM }

/**
 * Input arbitration mode for a Session.
 *
 * Spec ref: ATR 2.0 Final Spec §11.1
 *
 *   NORMAL         — default, owner has control
 *   TAKEOVER       — a human has taken over from the agent (Agent writes are REJECTED with OwnerBusy)
 *   WAITING_INPUT  — high-confidence input-required detected (owner unchanged, mode changes)
 *   INTERRUPTED    — SIGINT just sent, writes paused until shell returns to idle
 */
enum class ControlMode { NORMAL, TAKEOVER, WAITING_INPUT, INTERRUPTED }

/**
 * InputControlState = (owner, mode). Kept separate from SessionState to avoid conflating
 * ownership with session lifecycle (Spec §16: "don't mix ownership with session state into one enum").
 *
 * Spec ref: ATR 2.0 Final Spec §11.1 / §11.2 (InputControl state machine, transitions I1-I9)
 *
 *   I1 (FREE, NORMAL)        --Agent terminal.run-->             (AGENT, NORMAL)
 *   I2 (AGENT, NORMAL)       --user starts typing (UI input)-->  (USER, TAKEOVER)
 *   I3 (USER, TAKEOVER)      --user releases (UI "release")-->   (AGENT, NORMAL)
 *   I4 (USER, TAKEOVER)      --Agent requests terminal.run-->    (USER, TAKEOVER) [REJECTED, OwnerBusy]
 *   I5 (*, *)                --InputWaiting HIGH_CONFIDENCE-->   (owner, WAITING_INPUT)
 *   I6 (owner, WAITING_INPUT)--write(input)-->                   (owner, NORMAL)
 *   I7 (*, *)                --SIGINT from USER-->               (USER, INTERRUPTED)
 *   I8 (USER, INTERRUPTED)   --shell back to idle-->             (USER, NORMAL)
 *   I9 (AGENT, *)            --Agent terminal.close-->           (FREE, NORMAL)
 */
data class InputControlState(
    val owner: InputOwner,
    val mode: ControlMode
) {
    companion object {
        val FREE = InputControlState(InputOwner.FREE, ControlMode.NORMAL)
    }

    /** Whether an Agent write/run is currently allowed (false during TAKEOVER / INTERRUPTED). */
    val agentCanWrite: Boolean
        get() = mode != ControlMode.TAKEOVER && mode != ControlMode.INTERRUPTED
}

/**
 * Special terminal keys (translated to byte sequences or ioctl signals by InputManager).
 *
 * Spec ref: ATR 2.0 Final Spec §17
 */
enum class TerminalKey {
    ENTER, TAB, BACKSPACE, ESC,
    CTRL_C, CTRL_D, CTRL_Z, CTRL_BACKSLASH,
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
    HOME, END, DELETE, PAGE_UP, PAGE_DOWN,
    INSERT,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
}

/**
 * Unix signals supported by the Runtime.
 *
 * Spec ref: ATR 2.0 Final Spec §18 (Signal semantics)
 *
 *   SIGINT   (2)   Ctrl+C       → Job INTERRUPTED, exitCode=130, UserInterrupt event
 *   SIGTERM (15)   graceful     → Job INTERRUPTED, exitCode=143
 *   SIGKILL  (9)   force        → Job INTERRUPTED, exitCode=137 (uncatchable)
 *   SIGHUP   (1)   close session→ Job EXITED, exitCode=129 + SessionClosed
 *   SIGQUIT  (3)   Ctrl+\       → Job INTERRUPTED, exitCode=131
 */
enum class UnixSignal(val number: Int) {
    SIGINT(2), SIGTERM(15), SIGKILL(9), SIGHUP(1), SIGQUIT(3)
}

/** Kind of input written (carried in InputWritten event). */
enum class InputKind { RAW, LINE, KEY, SIGNAL }
