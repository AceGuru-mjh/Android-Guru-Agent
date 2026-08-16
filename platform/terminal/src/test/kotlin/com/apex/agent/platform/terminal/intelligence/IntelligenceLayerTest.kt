package com.apex.agent.platform.terminal.intelligence

import com.apex.agent.platform.terminal.events.ExitCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.job.JobState
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import org.junit.Assert.*
import org.junit.Test

/**
 * Intelligence Layer Tests (Spec §12 PR #50).
 * Covers: Job State Machine, Prompt Detection, Error Classification.
 */
class JobStateMachineTest {

    @Test fun `CREATED to RUNNING is valid`() = assertTrue(JobStateMachine.isValid(JobState.CREATED, JobState.RUNNING))
    @Test fun `RUNNING to WAITING_INPUT is valid`() = assertTrue(JobStateMachine.isValid(JobState.RUNNING, JobState.WAITING_INPUT))
    @Test fun `WAITING_INPUT to RUNNING is valid`() = assertTrue(JobStateMachine.isValid(JobState.WAITING_INPUT, JobState.RUNNING))
    @Test fun `RUNNING to EXITED is valid`() = assertTrue(JobStateMachine.isValid(JobState.RUNNING, JobState.EXITED))
    @Test fun `RUNNING to FAILED is valid`() = assertTrue(JobStateMachine.isValid(JobState.RUNNING, JobState.FAILED))
    @Test fun `RUNNING to TIMED_OUT is valid`() = assertTrue(JobStateMachine.isValid(JobState.RUNNING, JobState.TIMED_OUT))

    @Test fun `EXITED to RUNNING is forbidden`() = assertFalse(JobStateMachine.isValid(JobState.EXITED, JobState.RUNNING))
    @Test fun `FAILED to CREATED is forbidden`() = assertFalse(JobStateMachine.isValid(JobState.FAILED, JobState.CREATED))
    @Test fun `TIMED_OUT to RUNNING is forbidden`() = assertFalse(JobStateMachine.isValid(JobState.TIMED_OUT, JobState.RUNNING))
    @Test fun `EXITED to WAITING_INPUT is forbidden`() = assertFalse(JobStateMachine.isValid(JobState.EXITED, JobState.WAITING_INPUT))

    @Test fun `requireValid throws on illegal transition`() {
        assertThrows(IllegalStateException::class.java) {
            JobStateMachine.requireValid(JobState.EXITED, JobState.RUNNING)
        }
    }

    @Test fun `terminal states have no outgoing`() {
        for (s in JobStateMachine.terminalStates) {
            assertFalse("$s should not transition to RUNNING", JobStateMachine.isValid(s, JobState.RUNNING))
        }
    }
}

class PromptDetectorTest {

    private fun vtWithText(text: String): RealVirtualTerminal {
        val vt = RealVirtualTerminal(24, 80)
        vt.feed(text.toByteArray())
        return vt
    }

    @Test fun `detects SHELL prompt dollar`() {
        val vt = vtWithText("output\n\$ ")
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        assertTrue(d.detected)
        assertEquals(PromptType.SHELL, d.type)
        assertTrue(d.confidence >= 0.85f)
    }

    @Test fun `detects SHELL prompt hash`() {
        val vt = vtWithText("output\n# ")
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        assertTrue(d.detected)
        assertEquals(PromptType.SHELL, d.type)
    }

    @Test fun `detects CONFIRMATION Y-n`() {
        val vt = vtWithText("Continue? [Y/n] ")
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        assertTrue(d.detected)
        assertEquals(PromptType.CONFIRMATION, d.type)
        assertTrue(d.confidence >= 0.85f)
    }

    @Test fun `detects CONFIRMATION y-N`() {
        val vt = vtWithText("Proceed? (y/N) ")
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        assertTrue(d.detected)
        assertEquals(PromptType.CONFIRMATION, d.type)
    }

    @Test fun `detects PASSWORD prompt`() {
        val vt = vtWithText("Password: ")
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        assertTrue(d.detected)
        assertEquals(PromptType.PASSWORD, d.type)
        assertTrue(d.confidence >= 0.9f)
    }

    @Test fun `detects ENTER PASSWORD prompt`() {
        val vt = vtWithText("Enter passphrase: ")
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        assertTrue(d.detected)
        assertEquals(PromptType.PASSWORD, d.type)
    }

    @Test fun `detects INPUT prompt`() {
        val vt = vtWithText("Enter project name: ")
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        assertTrue(d.detected)
        assertEquals(PromptType.INPUT, d.type)
    }

    @Test fun `detects MENU prompt`() {
        val vt = vtWithText("Select option: ")
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        assertTrue(d.detected)
        assertEquals(PromptType.MENU, d.type)
    }

    @Test fun `does not falsely detect bare question mark as prompt`() {
        // A line ending with ? but not matching confirmation pattern
        val vt = vtWithText("What is the meaning of life?")
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        // Should NOT be CONFIRMATION (no [Y/n] pattern)
        assertNotEquals(PromptType.CONFIRMATION, d.type)
    }

    @Test fun `does not falsely detect bare colon as prompt`() {
        val vt = vtWithText("Chapter 1: The Beginning")
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        assertNotEquals(PromptType.INPUT, d.type)
    }

    @Test fun `does not falsely detect bare greater-than as prompt`() {
        // A comparison line, not a REPL prompt
        val vt = vtWithText("result > expected")
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        // "result > expected" doesn't end with ">" alone, so shouldn't match SHELL
        assertNotEquals(PromptType.SHELL, d.type)
    }

    @Test fun `alternate screen returns UNKNOWN not WAITING_INPUT`() {
        val vt = RealVirtualTerminal(24, 80)
        vt.feed(byteArrayOf(0x1B, '['.code.toByte(), '?'.code.toByte(), '1'.code.toByte(), '0'.code.toByte(), '4'.code.toByte(), '9'.code.toByte(), 'h'.code.toByte()))  // enter alt screen
        vt.feed("vim content".toByteArray())
        val d = PromptDetector.detect(vt, foregroundCommand = "vim")
        assertFalse(d.detected)
        assertEquals(PromptType.UNKNOWN, d.type)
    }

    @Test fun `blank screen returns UNKNOWN`() {
        val vt = RealVirtualTerminal(24, 80)
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        assertFalse(d.detected)
    }

    @Test fun `password detection never returns input content`() {
        // Even if "password" appears in output, the detection only returns type, not user input
        val vt = vtWithText("Password: ")
        val d = PromptDetector.detect(vt, foregroundCommand = null)
        // text field is the prompt label "Password:", NOT any user input
        assertEquals("Password:", d.text)
        assertNotEquals("123456", d.text)  // never the actual password
    }
}

class ErrorClassifierTest {

    private fun exitEvent(exitCode: Int?, signal: UnixSignal? = null, cause: ExitCause = ExitCause.NORMAL): TerminalEvent.ProcessExited {
        val sig = signal
        val realCause = if (signal == UnixSignal.SIGKILL && cause == ExitCause.NORMAL) ExitCause.PROCESS_KILLED else cause
        return TerminalEvent.ProcessExited(
            id = 1, sessionId = 1, timestamp = System.currentTimeMillis(), cursor = -1,
            jobId = 1, pid = 123, exitCode = exitCode, signal = sig, cause = realCause
        )
    }

    @Test fun `classifies COMMAND_NOT_FOUND from output pattern`() {
        val ev = exitEvent(exitCode = 127)
        val err = ErrorClassifier.classify(ev, recentOutput = "/bin/sh: foo: not found")
        assertEquals(TerminalErrorCode.COMMAND_NOT_FOUND, err.code)
        assertEquals(127, err.exitCode)
    }

    @Test fun `classifies PERMISSION_DENIED from output pattern`() {
        val ev = exitEvent(exitCode = 126)
        val err = ErrorClassifier.classify(ev, recentOutput = "permission denied")
        assertEquals(TerminalErrorCode.PERMISSION_DENIED, err.code)
    }

    @Test fun `classifies FILE_NOT_FOUND from output pattern`() {
        val ev = exitEvent(exitCode = 1)
        val err = ErrorClassifier.classify(ev, recentOutput = "No such file or directory")
        assertEquals(TerminalErrorCode.FILE_NOT_FOUND, err.code)
    }

    @Test fun `classifies PROCESS_KILLED on SIGKILL`() {
        val ev = exitEvent(exitCode = 137, signal = UnixSignal.SIGKILL)
        val err = ErrorClassifier.classify(ev, recentOutput = null)
        assertEquals(TerminalErrorCode.PROCESS_KILLED, err.code)
        assertEquals(9, err.signal)
    }

    @Test fun `classifies TIMEOUT on SIGKILL with TIMEOUT cause`() {
        val ev = exitEvent(exitCode = 137, signal = UnixSignal.SIGKILL, cause = ExitCause.TIMEOUT)
        val err = ErrorClassifier.classify(ev, recentOutput = null)
        assertEquals(TerminalErrorCode.TIMEOUT, err.code)
    }

    @Test fun `classifies SIGNALLED on SIGINT`() {
        val ev = exitEvent(exitCode = 130, signal = UnixSignal.SIGINT)
        val err = ErrorClassifier.classify(ev, recentOutput = null)
        assertEquals(TerminalErrorCode.SIGNALLED, err.code)
    }

    @Test fun `classifies PROCESS_FAILED for generic non-zero exit`() {
        val ev = exitEvent(exitCode = 1)
        val err = ErrorClassifier.classify(ev, recentOutput = "some build error")
        assertEquals(TerminalErrorCode.PROCESS_FAILED, err.code)
    }

    @Test fun `classifies UNKNOWN for exit 0`() {
        val ev = exitEvent(exitCode = 0)
        val err = ErrorClassifier.classify(ev, recentOutput = null)
        assertEquals(TerminalErrorCode.UNKNOWN, err.code)
    }
}
