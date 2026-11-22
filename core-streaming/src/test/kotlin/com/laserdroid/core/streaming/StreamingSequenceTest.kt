package com.laserdroid.core.streaming

import com.laserdroid.controllers.grbl.GrblController
import com.laserdroid.core.gcode.toGCodeLines
import com.laserdroid.simulator.FakeSerialTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamingSequenceTest {

    @Test
    fun `lazy sequence is streamed with original line numbers`() = runBlocking<Unit> {
        val transport = FakeSerialTransport()
        val controller = GrblController(transport)
        controller.connect()
        val engine = SendResponseStreamingEngine(controller)

        val lines = sequenceOf("; header", "G0 X0 Y0", "", "G1 X1 F100", "M5")
            .toGCodeLines()

        val result = engine.execute(lines, totalCommands = 3)

        assertIs<StreamingState.Completed>(result)
        assertEquals(3, result.commandsSent)
        assertEquals(5L, engine.progress.value.currentOriginalLine)
        assertEquals(listOf("G0 X0 Y0", "G1 X1 F100", "M5"), transport.simulator.receivedCommands)
    }

    @Test
    fun `cancellation stops feeding immediately`() = runBlocking<Unit> {
        val transport = FakeSerialTransport()
        // Command 10 is never acknowledged.
        transport.timeoutAtCommand = 10
        val controller = GrblController(transport)
        controller.connect()
        val engine = SendResponseStreamingEngine(
            controller,
            ackInactivityTimeoutMillis = 60_000L
        )

        val lines = generateSequence(1) { it + 1 }
            .take(100)
            .map { "G1 X$it F100" }
            .toGCodeLines()

        val job = launch { engine.execute(lines, totalCommands = 100) }
        while (transport.simulator.receivedCommands.size < 10) {
            delay(5)
        }
        job.cancelAndJoin()

        val state = engine.state.value
        assertIs<StreamingState.Cancelled>(state)
        assertEquals(10, state.commandsSent)
        assertEquals(9, state.commandsAcknowledged)
        delay(100)
        assertEquals(10, transport.simulator.receivedCommands.size)
    }

    @Test
    fun `status reports keep a long acknowledgement alive`() = runBlocking<Unit> {
        val transport = FakeSerialTransport()
        transport.timeoutAtCommand = 2
        val controller = GrblController(transport)
        controller.connect()
        val engine = SendResponseStreamingEngine(
            controller,
            ackInactivityTimeoutMillis = 300L
        )

        val lines = sequenceOf("G1 X1 F100", "G1 X2", "G1 X3").toGCodeLines()
        val run = async(start = CoroutineStart.UNDISPATCHED) { engine.execute(lines, 3) }

        // Simulate a long move: status reports for 900 ms, then the ok.
        repeat(9) {
            delay(100)
            transport.emitIncomingText("<Run|MPos:1.000,0.000,0.000|FS:100,0>\r\n")
        }
        assertTrue(run.isActive)
        transport.emitIncomingText("ok\r\n")

        assertIs<StreamingState.Completed>(run.await())
    }
}
