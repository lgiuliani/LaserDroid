package com.laserdroid.core.streaming

import com.laserdroid.controllers.api.CncController
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.ControllerCapabilities
import com.laserdroid.controllers.api.ControllerEvent
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.core.gcode.GCodeNormalizer
import com.laserdroid.core.gcode.GCodeProgram
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.laserdroid.controllers.api.JogRequest


class SendResponseStreamingEngineTest {

    @Test
    fun `complete program is streamed sequentially`() =
        runBlocking {

            val controller = TestController()

            val engine =
                SendResponseStreamingEngine(
                    controller
                )

            val normalizer =
                GCodeNormalizer()

            val program =
                GCodeProgram(
                    name = "test.gcode",
                    lines = listOf(
                        normalizer.normalize(
                            1,
                            "; Test"
                        ),
                        normalizer.normalize(
                            2,
                            "G0 X0 Y0"
                        ),
                        normalizer.normalize(
                            3,
                            ""
                        ),
                        normalizer.normalize(
                            4,
                            "G1 X10 Y20"
                        ),
                        normalizer.normalize(
                            5,
                            "M5"
                        )
                    )
                )

            val result =
                engine.execute(program)

            assertIs<StreamingState.Completed>(
                result
            )

            assertEquals(
                listOf(
                    "G0 X0 Y0",
                    "G1 X10 Y20",
                    "M5"
                ),
                controller.commands
            )

            assertEquals(
                3,
                engine.progress.value
                    .commandsSent
            )

            assertEquals(
                3,
                engine.progress.value
                    .commandsAcknowledged
            )

            assertEquals(
                100.0,
                engine.progress.value.percent
            )
        }

    @Test
    fun `streaming stops immediately on rejected command`() =
        runBlocking {

            val controller =
                TestController(
                    rejectCommand = "G1 X20 Y20"
                )

            val engine =
                SendResponseStreamingEngine(
                    controller
                )

            val normalizer =
                GCodeNormalizer()

            val program =
                GCodeProgram(
                    name = "error-test.gcode",
                    lines = listOf(
                        normalizer.normalize(
                            1,
                            "G1 X10 Y10"
                        ),
                        normalizer.normalize(
                            2,
                            "G1 X20 Y20"
                        ),
                        normalizer.normalize(
                            3,
                            "G1 X30 Y30"
                        )
                    )
                )

            val result =
                engine.execute(program)

            assertIs<StreamingState.Failed>(
                result
            )

            assertEquals(
                2,
                result.lineNumber
            )

            assertEquals(
                "G1 X20 Y20",
                result.command
            )

            assertIs<StreamingFailure.Rejected>(
                result.reason
            )

            assertEquals(
                1,
                result.reason.code
            )

            assertEquals(
                listOf(
                    "G1 X10 Y10",
                    "G1 X20 Y20"
                ),
                controller.commands
            )

            assertEquals(
                2,
                engine.progress.value
                    .commandsSent
            )

            assertEquals(
                1,
                engine.progress.value
                    .commandsAcknowledged
            )
        }

    private class TestController(
        private val rejectCommand: String? = null
    ) : CncController {

        val commands =
            mutableListOf<String>()

        private val _machineState =
            MutableStateFlow<MachineState>(
                MachineState.Idle
            )

        override val machineState:
                StateFlow<MachineState>
            get() = _machineState

        private val _machinePosition =
            MutableStateFlow(
                MachinePosition.ZERO
            )

        override val machinePosition:
                StateFlow<MachinePosition>
            get() = _machinePosition

        override val events:
                Flow<ControllerEvent> =
            MutableSharedFlow()

        override val capabilities =
            ControllerCapabilities()

        override suspend fun connect() {
            // No connection required for this unit test.
        }

        override suspend fun disconnect() {
            // No connection required for this unit test.
        }

        override suspend fun executeCommand(
            command: String
        ): CommandResult {

            commands.add(command)

            if (command == rejectCommand) {
                return CommandResult.Rejected(
                    code = 1
                )
            }

            return CommandResult.Acknowledged
        }

        override suspend fun jog(
            request: JogRequest
        ): CommandResult {
            return CommandResult.Acknowledged
        }

        override suspend fun jogToZero(
            feedRate: Double
        ): CommandResult {

            return CommandResult.Acknowledged
        }

        override suspend fun cancelJog() {
            // Not required by StreamingEngine tests.
        }

        override suspend fun requestStatus() {
            // Not required by StreamingEngine tests.
        }

        override suspend fun pause() {
            // Not required by StreamingEngine tests.
        }

        override suspend fun resume() {
            // Not required by StreamingEngine tests.
        }

        override suspend fun stop() {
            // Not required by StreamingEngine tests.
        }
    }
}