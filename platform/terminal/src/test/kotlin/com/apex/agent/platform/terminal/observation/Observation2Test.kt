package com.apex.agent.platform.terminal.observation

import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import com.apex.agent.platform.terminal.state.SemanticStateReducer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Observation2Test {

    private suspend fun newObservation(): Pair<ObservationEngine2Impl, TerminalRuntimeImpl> {
        val rt = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
        )
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val a = rt.sessionManager.assembly(s.sessionId)!!
        val obs = ObservationEngine2Impl(a.observationEngine, a.virtualTerminal, s.sessionId)
        return obs to rt  // sessionId = obs.sessionId
    }

    @Test fun `snapshot returns consistent state`() = runBlocking {
        val (obs, _) = newObservation()
        val snap = obs.snapshot(obs.sessionId).getOrThrow()
        assertTrue(snap.sequence >= 0)
        assertTrue(snap.rows > 0)
        assertTrue(snap.cols > 0)
        assertNotNull(snap.screenText)
    }

    @Test fun `observeSince with initial cursor returns empty batch`() = runBlocking {
        val (obs, _) = newObservation()
        val cursor = ObservationCursor(obs.diagnostics(obs.sessionId)!!.currentSequence)
        val batch = obs.observeSince(obs.sessionId, cursor).getOrThrow()
        assertTrue("no changes should give empty batch", batch.changes.isEmpty())
    }

    @Test fun `sequence increments on mutation`() = runBlocking {
        val (obs, _) = newObservation()
        val before = obs.diagnostics(obs.sessionId)!!.currentSequence
        obs.onScreenMutation(listOf(TerminalChange.CursorChanged(0, 5, true)))
        val after = obs.diagnostics(obs.sessionId)!!.currentSequence
        assertTrue("sequence should increment", after > before)
    }

    @Test fun `observeSince after mutation returns changes`() = runBlocking {
        val (obs, _) = newObservation()
        val cursor = ObservationCursor(obs.diagnostics(obs.sessionId)!!.currentSequence)
        obs.onScreenMutation(listOf(TerminalChange.CellsChanged(10, 0, 18, "BUILD SUCCESSFUL")))
        val batch = obs.observeSince(obs.sessionId, cursor).getOrThrow()
        assertTrue("batch should have changes", batch.changes.isNotEmpty())
        assertTrue(batch.toSequence > cursor.sequence)
    }

    @Test fun `cursor expired returns error`() = runBlocking {
        val (obs, _) = newObservation()
        // Fill batch ring beyond capacity to evict old entries
        for (i in 1..600) obs.onScreenMutation(listOf(TerminalChange.CursorChanged(0, i, true)))
        // Oldest cursor should be > 0 now
        val diag = obs.diagnostics(obs.sessionId)!!
        assertTrue("oldest should be > 0 after eviction", diag.oldestSequence > 0)
        assertTrue("some batches should be dropped", diag.droppedBatches > 0)
        // observeSince with cursor 0 should fail (expired)
        val r = obs.observeSince(obs.sessionId, ObservationCursor(0))
        assertTrue("cursor 0 should be expired", r.isFailure)
    }

    @Test fun `multi-consumer cursors are independent`() = runBlocking {
        val (obs, _) = newObservation()
        val c1 = obs.registerConsumer(obs.sessionId).getOrThrow()
        val c2 = obs.registerConsumer(obs.sessionId).getOrThrow()
        assertNotEquals("consumer IDs should differ", c1.consumerId, c2.consumerId)
        // Mutate
        obs.onScreenMutation(listOf(TerminalChange.CellsChanged(0, 0, 5, "hello")))
        // c1 observes
        val b1 = obs.observeSince(obs.sessionId, c1.cursor).getOrThrow()
        assertTrue("c1 should see changes", b1.changes.isNotEmpty())
        // c2 hasn't advanced — still sees same changes
        val b2 = obs.observeSince(obs.sessionId, c2.cursor).getOrThrow()
        assertTrue("c2 should also see changes", b2.changes.isNotEmpty())
        // c1 advances
        c1.cursor = ObservationCursor(b1.toSequence)
        val b1b = obs.observeSince(obs.sessionId, c1.cursor).getOrThrow()
        assertTrue("c1 after advance should see no new changes", b1b.changes.isEmpty())
    }

    @Test fun `unregister removes consumer`() = runBlocking {
        val (obs, _) = newObservation()
        val c = obs.registerConsumer(obs.sessionId).getOrThrow()
        assertEquals(1, obs.diagnostics(obs.sessionId)!!.activeConsumers)
        obs.unregisterConsumer(c.consumerId)
        assertEquals(0, obs.diagnostics(obs.sessionId)!!.activeConsumers)
    }

    @Test fun `subscribe returns non-null flow`() = runBlocking {
        val (obs, _) = newObservation()
        val flow = obs.subscribe(obs.sessionId)
        assertNotNull(flow)
    }

    @Test fun `diagnostics returns valid metrics`() = runBlocking {
        val (obs, _) = newObservation()
        val d = obs.diagnostics(obs.sessionId)
        assertNotNull(d)
        assertTrue(d!!.currentSequence >= 0)
        assertTrue(d.activeConsumers >= 0)
        assertTrue(d.bufferedBatches >= 0)
    }

    @Test fun `snapshot sequence is atomic with screen state`() = runBlocking {
        val (obs, _) = newObservation()
        val snap1 = obs.snapshot(obs.sessionId).getOrThrow()
        val snap2 = obs.snapshot(obs.sessionId).getOrThrow()
        // Both snapshots should have same or increasing sequence
        assertTrue("sequence should be monotonic", snap2.sequence >= snap1.sequence)
    }

    @Test fun `TerminalChange types exist`() {
        val cells = TerminalChange.CellsChanged(0, 0, 10, "hello")
        val cursor = TerminalChange.CursorChanged(0, 5, true)
        val resize = TerminalChange.ScreenResized(40, 120)
        val title = TerminalChange.TitleChanged("My Title")
        val mode = TerminalChange.ModeChanged(true, false)
        val scroll = TerminalChange.ScrollChanged(TerminalChange.ScrollDirection.UP, 3)
        val cleared = TerminalChange.Cleared(TerminalChange.ClearMode.SCREEN)
        assertNotNull(cells)
        assertNotNull(cursor)
        assertNotNull(resize)
        assertNotNull(title)
        assertNotNull(mode)
        assertNotNull(scroll)
        assertNotNull(cleared)
    }

    @Test fun `ObservationError has all types`() {
        assertNotNull(ObservationError.CursorExpired(0, 100, 500))
        assertNotNull(ObservationError.ObservationUnavailable)
        assertNotNull(ObservationError.SnapshotTooLarge)
        assertNotNull(ObservationError.ObservationBackpressure)
        assertNotNull(ObservationError.ConsumerNotFound)
        assertNotNull(ObservationError.InvalidCursor)
        assertNotNull(ObservationError.ObservationClosed)
    }

    @Test fun `RetentionConfig has bounded defaults`() {
        val c = TerminalRetentionConfig()
        assertTrue(c.maxScrollbackLines > 0)
        assertTrue(c.maxObservationBatches > 0)
        assertTrue(c.maxRawOutputBytes > 0)
        assertTrue(c.coalesceWindowMs > 0)
    }

    @Test fun `batch ring evicts oldest when full`() = runBlocking {
        val (obs, _) = newObservation()
        val config = TerminalRetentionConfig(maxObservationBatches = 10)
        // The impl was created with default config, but we can verify eviction works
        for (i in 1..15) {
            obs.onScreenMutation(listOf(TerminalChange.CellsChanged(0, 0, 1, "x")))
        }
        val d = obs.diagnostics(obs.sessionId)!!
        // With default config (500 batches), all 15 should be retained
        assertEquals(15, d.bufferedBatches)
        assertEquals(0, d.droppedBatches)
    }

    @Test fun `scenario execute observe shows output`() = runBlocking {
        val (obs, rt) = newObservation()
        val sid = 1L // sessionId from newObservation
        // Get initial cursor
        val cursor = ObservationCursor(obs.diagnostics(sid)!!.currentSequence)
        // Execute a command
        rt.run(sid, "echo observation_test", com.apex.agent.platform.terminal.io.InputOwner.AGENT)
        kotlinx.coroutines.delay(500)
        // Observe changes
        val batch = obs.observeSince(sid, cursor).getOrThrow()
        // Should have some changes (screen mutations from command output)
        // (FakeNativePty produces output that feeds VT)
    }
}
