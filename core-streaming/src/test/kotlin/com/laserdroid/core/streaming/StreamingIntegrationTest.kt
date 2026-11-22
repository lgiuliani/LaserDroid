package com.laserdroid.core.streaming

import com.laserdroid.controllers.grbl.GrblController
import com.laserdroid.core.gcode.GCodeNormalizer
import com.laserdroid.core.gcode.GCodeProgram
import com.laserdroid.simulator.FakeSerialTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.laserdroid.communication.api.ConnectionState
import com.laserdroid.controllers.api.MachineState

class StreamingIntegrationTest {

    @Test
    fun `1000 commands are streamed end to end`() =
        runBlocking {

            val transport =
                FakeSerialTransport()

            val controller =
                GrblController(transport)

            val engine =
                SendResponseStreamingEngine(
                    controller
                )

            controller.connect()

            val normalizer =
                GCodeNormalizer()

            val commandCount = 1_000

            val lines =
                (1..commandCount).map { index ->

                    normalizer.normalize(
                        lineNumber = index.toLong(),
                        content = "G1 X$index Y$index"
                    )
                }

            val program =
                GCodeProgram(
                    name = "integration-1000.gcode",
                    lines = lines
                )

            val result =
                withTimeout(30_000) {
                    engine.execute(program)
                }

            assertIs<StreamingState.Completed>(
                result
            )

            assertEquals(
                commandCount,
                result.commandsSent
            )

            assertEquals(
                commandCount,
                engine.progress.value
                    .commandsSent
            )

            assertEquals(
                commandCount,
                engine.progress.value
                    .commandsAcknowledged
            )

            assertEquals(
                100.0,
                engine.progress.value.percent
            )

            controller.disconnect()
        }

    @Test
    fun `10000 commands are streamed without loss or duplication`() =
        runBlocking {

            val transport =
                FakeSerialTransport()

            val controller =
                GrblController(transport)

            val engine =
                SendResponseStreamingEngine(
                    controller
                )

            controller.connect()

            val normalizer =
                GCodeNormalizer()

            val commandCount = 10_000

            val expectedCommands =
                (1..commandCount).map { index ->

                    "G1 X${index % 400} Y${index / 400}"
                }

            val lines =
                expectedCommands.mapIndexed {
                        index,
                        command ->

                    normalizer.normalize(
                        lineNumber =
                            (index + 1).toLong(),
                        content = command
                    )
                }

            val program =
                GCodeProgram(
                    name =
                        "integration-10000.gcode",
                    lines = lines
                )

            val result =
                withTimeout(60_000) {

                    engine.execute(program)
                }

            assertIs<StreamingState.Completed>(
                result
            )

            assertEquals(
                commandCount,
                result.commandsSent
            )

            assertEquals(
                commandCount,
                engine.progress.value
                    .commandsAcknowledged
            )

            assertEquals(
                commandCount,
                transport.simulator
                    .receivedCommands.size
            )

            assertEquals(
                expectedCommands,
                transport.simulator
                    .receivedCommands
            )

            assertEquals(
                100.0,
                engine.progress.value.percent
            )

            controller.disconnect()
        }

    @Test
    fun `streaming stops exactly at command 5327 on GRBL error`() =
        runBlocking {

            val transport =
                FakeSerialTransport()

            transport.simulator.errorAtCommand = 5_327
            transport.simulator.errorCode = 2

            val controller =
                GrblController(transport)

            val engine =
                SendResponseStreamingEngine(
                    controller
                )

            controller.connect()

            val normalizer =
                GCodeNormalizer()

            val commandCount = 10_000

            val expectedCommands =
                (1..commandCount).map { index ->

                    "G1 X${index % 400} Y${index / 400}"
                }

            val program =
                GCodeProgram(
                    name = "error-5327.gcode",

                    lines =
                        expectedCommands.mapIndexed {
                                index,
                                command ->

                            normalizer.normalize(
                                lineNumber =
                                    (index + 1).toLong(),
                                content = command
                            )
                        }
                )

            val result =
                withTimeout(60_000) {

                    engine.execute(program)
                }

            assertIs<StreamingState.Failed>(
                result
            )

            assertEquals(
                5_327,
                result.lineNumber
            )

            assertEquals(
                expectedCommands[5_326],
                result.command
            )

            assertIs<StreamingFailure.Rejected>(
                result.reason
            )

            assertEquals(
                2,
                result.reason.code
            )

            assertEquals(
                5_327,
                engine.progress.value.commandsSent
            )

            assertEquals(
                5_326,
                engine.progress.value
                    .commandsAcknowledged
            )

            assertEquals(
                5_327,
                transport.simulator
                    .receivedCommands.size
            )

            assertEquals(
                expectedCommands.take(5_327),
                transport.simulator
                    .receivedCommands
            )

            controller.disconnect()
        }

    @Test
    fun `streaming stops exactly at command 5327 on disconnect`() =
        runBlocking {

            val transport =
                FakeSerialTransport()

            // Simulate a physical communication loss
            // when command 5327 reaches the transport.
            transport.disconnectAtCommand = 5_327

            val controller =
                GrblController(transport)

            val engine =
                SendResponseStreamingEngine(
                    controller
                )

            controller.connect()

            val normalizer =
                GCodeNormalizer()

            val commandCount = 10_000

            val expectedCommands =
                (1..commandCount).map { index ->
                    "G1 X${index % 400} Y${index / 400}"
                }

            val lines =
                expectedCommands.mapIndexed {
                        index,
                        command ->

                    normalizer.normalize(
                        lineNumber = (index + 1).toLong(),
                        content = command
                    )
                }

            val program =
                GCodeProgram(
                    name = "disconnect-5327.gcode",
                    lines = lines
                )

            val result =
                withTimeout(60_000) {
                    engine.execute(program)
                }

            /*
             * Streaming must fail.
             */
            assertIs<StreamingState.Failed>(
                result
            )

            /*
             * Failure must occur exactly on
             * original G-code line 5327.
             */
            assertEquals(
                5_327,
                result.lineNumber
            )

            /*
             * The failed command must be the
             * command corresponding to line 5327.
             */
            assertEquals(
                expectedCommands[5_326],
                result.command
            )

            /*
             * The Streaming Engine must report
             * a communication disconnection.
             */
            assertIs<StreamingFailure.Disconnected>(
                result.reason
            )

            /*
             * From the Streaming Engine point of view,
             * command 5327 was sent to the transport.
             */
            assertEquals(
                5_327,
                engine.progress.value.commandsSent
            )

            /*
             * Only the first 5326 commands were
             * acknowledged successfully.
             */
            assertEquals(
                5_326,
                engine.progress.value
                    .commandsAcknowledged
            )

            /*
             * The transport disconnects before command
             * 5327 reaches the simulated GRBL controller.
             */
            assertEquals(
                5_326,
                transport.simulator
                    .receivedCommands.size
            )

            /*
             * Verify zero loss, duplication or reordering
             * before the disconnection.
             */
            assertEquals(
                expectedCommands.take(5_326),
                transport.simulator
                    .receivedCommands
            )

            /*
             * The transport must remain disconnected.
             * No automatic reconnection is allowed.
             */
            assertEquals(
                ConnectionState.Disconnected,
                transport.connectionState.value
            )

            /*
             * The controller must also expose
             * the disconnected machine state.
             */
            assertEquals(
                MachineState.Disconnected,
                controller.machineState.value
            )
        }

    @Test
    fun `streaming stops exactly at command 5327 on timeout`() =
        runBlocking {

            val transport =
                FakeSerialTransport()

            /*
             * Command 5327 will reach the simulated
             * GRBL controller, but no response will
             * be returned.
             */
            transport.timeoutAtCommand = 5_327

            val controller =
                GrblController(transport)

            val engine =
                SendResponseStreamingEngine(
                    controller
                )

            controller.connect()

            val normalizer =
                GCodeNormalizer()

            val commandCount = 10_000

            val expectedCommands =
                (1..commandCount).map { index ->

                    "G1 X${index % 400} Y${index / 400}"
                }

            val lines =
                expectedCommands.mapIndexed {
                        index,
                        command ->

                    normalizer.normalize(
                        lineNumber =
                            (index + 1).toLong(),
                        content = command
                    )
                }

            val program =
                GCodeProgram(
                    name = "timeout-5327.gcode",
                    lines = lines
                )

            /*
             * GrblController currently uses a
             * 2-second command timeout.
             *
             * The outer timeout only protects
             * the test from hanging indefinitely.
             */
            val result =
                withTimeout(10_000) {

                    engine.execute(program)
                }

            /*
             * Streaming must fail.
             */
            assertIs<StreamingState.Failed>(
                result
            )

            /*
             * Failure must occur exactly on
             * original G-code line 5327.
             */
            assertEquals(
                5_327,
                result.lineNumber
            )

            /*
             * Store the exact G-code command
             * responsible for the timeout.
             */
            assertEquals(
                expectedCommands[5_326],
                result.command
            )

            /*
             * Failure reason must specifically
             * be a timeout.
             */
            assertIs<StreamingFailure.Timeout>(
                result.reason
            )

            /*
             * From the Streaming Engine point of view,
             * command 5327 was sent.
             */
            assertEquals(
                5_327,
                engine.progress.value
                    .commandsSent
            )

            /*
             * Commands 1..5326 returned "ok".
             *
             * Command 5327 never returned an
             * acknowledgement.
             */
            assertEquals(
                5_326,
                engine.progress.value
                    .commandsAcknowledged
            )

            /*
             * Unlike the disconnection scenario,
             * command 5327 DID reach GRBL.
             */
            assertEquals(
                5_327,
                transport.simulator
                    .receivedCommands.size
            )

            /*
             * Verify that commands 1..5327 arrived
             * once and in exactly the expected order.
             *
             * Command 5328 and following commands
             * must never be transmitted.
             */
            assertEquals(
                expectedCommands.take(5_327),
                transport.simulator
                    .receivedCommands
            )

            /*
             * A timeout does not necessarily mean
             * that the physical serial connection
             * has disappeared.
             */
            assertEquals(
                ConnectionState.Connected,
                transport.connectionState.value
            )
        }
}