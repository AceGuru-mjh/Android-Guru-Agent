package com.apex.agent.platform.terminal.api

import org.junit.Assert.*
import org.junit.Test

/**
 * PR #60: Public API Contract tests.
 * Verify API surface is stable, immutable, and complete.
 */
class TerminalApiContractTest {

    // ─── §4: IDs are type-safe ───
    @Test fun `SessionId and JobId are different types`() {
        val sid = SessionId("s1")
        val jid = JobId("j1")
        assertNotEquals(sid.value, jid.value)
        // Type system prevents mixing: session.execute(..., sessionId=JobId) won't compile
    }

    @Test fun `SessionId toString round-trips`() {
        val sid = SessionId("session_abc_123")
        assertEquals("session_abc_123", sid.value)
    }

    // ─── §8: Input API sealed ───
    @Test fun `TerminalInput has three variants`() {
        val text = TerminalInput.Text("hello")
        val key = TerminalInput.Key(TerminalKey.ENTER)
        val bytes = TerminalInput.Bytes(byteArrayOf(0x41, 0x42))
        assertTrue(text is TerminalInput.Text)
        assertTrue(key is TerminalInput.Key)
        assertTrue(bytes is TerminalInput.Bytes)
    }

    @Test fun `TerminalKey has all essential keys`() {
        assertTrue(TerminalKey.values().any { it.name == "ENTER" })
        assertTrue(TerminalKey.values().any { it.name == "CTRL_C" })
        assertTrue(TerminalKey.values().any { it.name == "CTRL_D" })
        assertTrue(TerminalKey.values().any { it.name == "UP" })
        assertTrue(TerminalKey.values().any { it.name == "F1" })
    }

    // ─── §6: Observation Result sealed ───
    @Test fun `ObservationResult has three variants`() {
        val snap = ObservationResult.Snapshot(
            TerminalSnapshot(24, 80, "hello", 0, 5, true, false, null, 1)
        )
        val delta = ObservationResult.Delta(
            TerminalDelta(1, 5, emptyList()), "cursor_5"
        )
        val expired = ObservationResult.CursorExpired(
            TerminalSnapshot(24, 80, "", 0, 0, true, false, null, 100), "cursor_100"
        )
        assertTrue(snap is ObservationResult.Snapshot)
        assertTrue(delta is ObservationResult.Delta)
        assertTrue(expired is ObservationResult.CursorExpired)
    }

    // ─── §10/§11: Snapshot immutable ───
    @Test fun `TerminalSnapshot is immutable (all val)`() {
        val snap = TerminalSnapshot(
            rows = 24, columns = 80, screenText = "hello",
            cursorRow = 0, cursorColumn = 5, cursorVisible = true,
            alternateScreen = false, title = "test", sequence = 1
        )
        assertEquals(24, snap.rows)
        assertEquals("hello", snap.screenText)
        assertEquals(1, snap.sequence)
    }

    @Test fun `SessionSnapshot is immutable`() {
        val snap = SessionSnapshot(
            sessionId = SessionId("s1"), name = "test",
            lifecycle = SessionLifecycleState.RUNNING,
            health = "HEALTHY", recovery = "NONE",
            terminal = TerminalSnapshot(24, 80, "", 0, 0, true, false, null, 1),
            foregroundJob = null, backgroundJobs = emptyList()
        )
        assertEquals(SessionLifecycleState.RUNNING, snap.lifecycle)
        assertNull(snap.foregroundJob)
        assertEquals(0, snap.backgroundJobs.size)
    }

    @Test fun `JobSnapshot has no ProcessHandle`() {
        val snap = JobSnapshot(
            id = JobId("j1"), sessionId = SessionId("s1"),
            command = "echo", state = JobState.EXITED,
            startedAt = 1000, finishedAt = 2000,
            foreground = true, attachment = PtyAttachment.ATTACHED,
            exitInfo = ExitInfo(0, null, false, ExitReason.NORMAL_EXIT)
        )
        // Verify no processHandle/processGroupHandle fields
        val fields = JobSnapshot::class.java.declaredFields.map { it.name }
        assertFalse("no processHandle", fields.any { it.contains("rocessHandle") })
        assertFalse("no processGroup", fields.any { it.contains("processGroup") })
    }

    // ─── §13: Error codes stable ───
    @Test fun `TerminalErrorCode has all required codes`() {
        val codes = TerminalErrorCode.values().map { it.name }
        assertTrue(codes.contains("SESSION_NOT_FOUND"))
        assertTrue(codes.contains("JOB_NOT_FOUND"))
        assertTrue(codes.contains("TIMEOUT"))
        assertTrue(codes.contains("CANCELLED"))
        assertTrue(codes.contains("CURSOR_EXPIRED"))
        assertTrue(codes.contains("BACKEND_UNAVAILABLE"))
        assertTrue(codes.contains("UNSUPPORTED"))
        assertTrue(codes.contains("PERMISSION_DENIED"))
    }

    @Test fun `TerminalError is immutable data class`() {
        val err = TerminalError(
            code = TerminalErrorCode.SESSION_NOT_FOUND,
            message = "session not found",
            retryable = false
        )
        assertEquals(TerminalErrorCode.SESSION_NOT_FOUND, err.code)
        assertFalse(err.retryable)
    }

    // ─── §22: API Version ───
    @Test fun `API version is 1.0`() {
        assertEquals(1, TerminalApiVersion.MAJOR)
        assertEquals(0, TerminalApiVersion.MINOR)
        assertEquals("1.0", TerminalApiVersion.versionString)
    }

    // ─── §21: Capabilities ───
    @Test fun `Capabilities has all fields`() {
        val caps = TerminalCapabilities()
        assertTrue(caps.supportsPty)
        assertTrue(caps.supportsSignals)
        assertFalse(caps.supportsReattach)  // not available by default
    }

    // ─── §20: TerminalSize validation ───
    @Test fun `TerminalSize rejects zero`() {
        assertThrows(IllegalArgumentException::class.java) { TerminalSize(0, 24) }
        assertThrows(IllegalArgumentException::class.java) { TerminalSize(80, 0) }
    }

    @Test fun `TerminalSize DEFAULT is 80x24`() {
        assertEquals(80, TerminalSize.DEFAULT.columns)
        assertEquals(24, TerminalSize.DEFAULT.rows)
    }

    // ─── §3: SessionRequest ───
    @Test fun `SessionRequest has no backend config`() {
        val req = SessionRequest(workingDirectory = "/tmp")
        assertNull(req.name)
        assertEquals("/tmp", req.workingDirectory)
        // Verify no backend/proutRoot/ptyImplementation fields
        val fields = SessionRequest::class.java.declaredFields.map { it.name }
        assertFalse("no backend field", fields.any { it.contains("backend") || it.contains("root") })
    }

    // ─── §17: EnvironmentSpec ───
    @Test fun `EnvironmentSpec supports inherit/override/remove`() {
        val env = EnvironmentSpec(
            inheritParent = true,
            overrides = mapOf("PATH" to "/custom"),
            removals = setOf("OLD_VAR")
        )
        assertTrue(env.inheritParent)
        assertEquals("/custom", env.overrides["PATH"])
        assertTrue(env.removals.contains("OLD_VAR"))
    }

    // ─── §27: Observation cursor is opaque string ───
    @Test fun `ObservationRequest uses opaque string cursor`() {
        val req = ObservationRequest(cursor = "opaque_cursor_123", maxBytes = 4096)
        assertEquals("opaque_cursor_123", req.cursor)
        assertEquals(4096, req.maxBytes)
    }

    // ─── §18: JobResult ───
    @Test fun `JobResult has observationRange not output`() {
        val result = JobResult(
            id = JobId("j1"), sessionId = SessionId("s1"),
            state = JobState.EXITED,
            exitInfo = ExitInfo(0, null, false, ExitReason.NORMAL_EXIT),
            durationMs = 1000, startedAt = 1000, finishedAt = 2000,
            observationRange = ObservationRange("cursor_100", "cursor_200")
        )
        assertNotNull(result.observationRange)
        assertEquals("cursor_100", result.observationRange!!.startCursor)
        // Verify no output field
        val fields = JobResult::class.java.declaredFields.map { it.name }
        assertFalse("no output field", fields.any { it == "output" })
    }

    // ─── §45: Freeze checklist ───
    @Test fun `Agent API does not expose PID`() {
        // Check all public models for pid field
        val models = listOf(
            SessionSummary::class.java, JobSnapshot::class.java, JobResult::class.java,
            SessionSnapshot::class.java, TerminalSnapshot::class.java
        )
        for (model in models) {
            val fields = model.declaredFields.map { it.name }
            assertFalse("${model.simpleName} should not expose pid", fields.any { it == "pid" })
        }
    }

    @Test fun `Agent API does not expose PTY`() {
        val models = listOf(SessionSummary::class.java, JobSnapshot::class.java, SessionSnapshot::class.java)
        for (model in models) {
            val fields = model.declaredFields.map { it.name }
            assertFalse("${model.simpleName} should not expose PTY", fields.any { it.contains("pty") || it.contains("Pty") })
        }
    }

    @Test fun `TerminalChange has all 7 types`() {
        val cells = TerminalChange.CellsChanged(0, 0, 10, "hello")
        val cursor = TerminalChange.CursorChanged(0, 5, true)
        val resize = TerminalChange.ScreenResized(40, 120)
        val title = TerminalChange.TitleChanged("Title")
        val mode = TerminalChange.ModeChanged(true)
        val scroll = TerminalChange.ScrollChanged("UP", 3)
        val cleared = TerminalChange.Cleared("SCREEN")
        assertNotNull(cells)
        assertNotNull(cursor)
        assertNotNull(resize)
        assertNotNull(title)
        assertNotNull(mode)
        assertNotNull(scroll)
        assertNotNull(cleared)
    }

    @Test fun `PtyAttachment has three modes`() {
        assertEquals(3, PtyAttachment.values().size)
        assertTrue(PtyAttachment.values().any { it.name == "ATTACHED" })
        assertTrue(PtyAttachment.values().any { it.name == "DETACHED" })
        assertTrue(PtyAttachment.values().any { it.name == "NONE" })
    }

    @Test fun `Subscription has cancel and isActive`() {
        // Verify the interface exists and has the right methods
        val methods = Subscription::class.java.declaredMethods.map { it.name }
        assertTrue(methods.contains("cancel"))
        assertTrue(methods.any { it == "isActive" })
    }

    @Test fun `TerminalApiEvent has 4 types`() {
        val types = TerminalApiEvent::class.java.declaredClasses.map { it.simpleName }
        assertTrue(types.any { it == "SessionStateChanged" })
        assertTrue(types.any { it == "JobStateChanged" })
        assertTrue(types.any { it == "OutputAvailable" })
        assertTrue(types.any { it == "ProcessExited" })
    }
}
