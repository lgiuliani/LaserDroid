package com.laserdroid.controllers.grbl

import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.simulator.FakeSerialTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import com.laserdroid.controllers.api.CommandResult
import kotlinx.coroutines.async
import kotlin.test.assertIs
import com.laserdroid.controllers.api.JogRequest

class GrblControllerTest {

    @Test
    fun `status request updates machine state`() = runBlocking {

        val transport =
            FakeSerialTransport()

        val controller =
            GrblController(transport)

        controller.connect()

        withTimeout(1_000) {

            controller.requestStatus()

            while (
                controller.machineState.value
                != MachineState.Idle
            ) {
                delay(10)
            }
        }

        assertEquals(
            MachineState.Idle,
            controller.machineState.value
        )

        assertEquals(
            MachinePosition.ZERO,
            controller.machinePosition.value
        )
    }

    @Test
    fun `fragmented status updates controller only when complete`() =
        runBlocking {

            val transport =
                FakeSerialTransport()

            /*
             * connect() sends '?': the test controls the
             * status stream itself.
             */
            transport.respondToStatusQueries = false

            val controller =
                GrblController(transport)

            controller.connect()

            transport.emitIncomingText(
                "<Idle|MPos:10.000"
            )

            delay(50)

            assertEquals(
                MachineState.Connecting,
                controller.machineState.value
            )

            transport.emitIncomingText(
                ",20.000,5.000|FS:0,0>\r\n"
            )

            withTimeout(1_000) {

                while (
                    controller.machineState.value
                    != MachineState.Idle
                ) {
                    delay(10)
                }
            }

            assertEquals(
                MachineState.Idle,
                controller.machineState.value
            )

            assertEquals(
                MachinePosition(
                    x = 10.0,
                    y = 20.0,
                    z = 5.0
                ),
                controller.machinePosition.value
            )
        }

    @Test
    fun `G1 command returns acknowledged`() =
        runBlocking {

            val transport =
                FakeSerialTransport()

            val controller =
                GrblController(transport)

            controller.connect()

            val result =
                controller.executeCommand(
                    "G1 X10 Y20"
                )

            assertEquals(
                CommandResult.Acknowledged,
                result
            )
        }

    @Test
    fun `invalid command returns rejected`() =
        runBlocking {

            val transport =
                FakeSerialTransport()

            val controller =
                GrblController(transport)

            controller.connect()

            val result =
                controller.executeCommand(
                    "INVALID"
                )

            assertIs<CommandResult.Rejected>(
                result
            )

            assertEquals(
                1,
                result.code
            )
        }

    @Test
    fun `command without connection returns disconnected`() =
        runBlocking {

            val transport =
                FakeSerialTransport()

            val controller =
                GrblController(transport)

            val result =
                controller.executeCommand(
                    "G1 X10 Y20"
                )

            assertEquals(
                CommandResult.Disconnected,
                result
            )
        }

    @Test
    fun `relative X jog is acknowledged`() =
        runBlocking {

            val transport =
                FakeSerialTransport()

            val controller =
                GrblController(transport)

            controller.connect()

            transport.emitIncomingText(
                "<Idle|MPos:0.000,0.000,0.000|FS:0,0>\r\n"
            )

            withTimeout(1_000) {

                while (
                    controller.machineState.value
                    != MachineState.Idle
                ) {
                    delay(10)
                }
            }

            val result =
                controller.jog(
                    JogRequest(
                        x = 1.0,
                        feedRate = 100.0
                    )
                )

            assertEquals(
                CommandResult.Acknowledged,
                result
            )

            assertEquals(
                "\$J=G21 G91 X1 F100",
                transport.simulator
                    .receivedCommands
                    .last()
            )
        }
}
