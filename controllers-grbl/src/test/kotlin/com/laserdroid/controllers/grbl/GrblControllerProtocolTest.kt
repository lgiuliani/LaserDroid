package com.laserdroid.controllers.grbl

import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.ControllerEvent
import com.laserdroid.controllers.api.JogRequest
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.simulator.FakeSerialTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GRBL protocol details: errors, alarms, resets, settings,
 * WPos reports and realtime bytes.
 */
class GrblControllerProtocolTest {

    private class Fixture {
        val transport = FakeSerialTransport()
        val controller = GrblController(transport)

        suspend fun connectIdle() {
            controller.connect()
            awaitState(MachineState.Idle)
        }

        suspend fun awaitState(state: MachineState) {
            withTimeout(1_000L) {
                controller.machineState.first { it == state }
            }
        }

        /*
         * Sends [command] (never answered by the simulator), lets it
         * reach the transport, then emits [response].
         */
        suspend fun answer(command: String, response: String): CommandResult =
            coroutineScope {
                transport.timeoutAtCommand =
                    transport.simulator.receivedCommands.size + 1
                val pending = async { controller.executeCommand(command) }
                withTimeout(1_000L) {
                    while (transport.simulator.receivedCommands.lastOrNull() != command) delay(5)
                }
                transport.emitIncomingText(response)
                withTimeout(1_000L) { pending.await() }
            }
    }

    @Test
    fun `grbl error rejects the line without changing the machine state`() = runBlocking {
        val f = Fixture()
        f.connectIdle()

        val result = f.controller.executeCommand("BAD")

        assertEquals(CommandResult.Rejected(1), result)
        // error:N does not change GRBL's state: jog stays possible.
        assertEquals(MachineState.Idle, f.controller.machineState.value)
        assertEquals(
            CommandResult.Acknowledged,
            f.controller.jog(JogRequest(x = 1.0, feedRate = 100.0))
        )
        f.controller.close()
    }

    @Test
    fun `alarm completes the pending command and sets Alarm`() = runBlocking {
        val f = Fixture()
        f.connectIdle()

        val result = f.answer("G1 X500 F100", "ALARM:2\r\n")

        assertEquals(CommandResult.Alarm(2), result)
        assertEquals(MachineState.Alarm, f.controller.machineState.value)
        f.controller.close()
    }

    @Test
    fun `startup banner completes the pending command with Reset`() = runBlocking {
        val f = Fixture()
        f.connectIdle()
        /*
         * The controller sends '?' after a reset from its reader
         * coroutine: the fake transport (unbuffered) must not answer.
         */
        f.transport.respondToStatusQueries = false
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            f.controller.events.first { it is ControllerEvent.ResetDetected }
        }

        val result = f.answer("G1 X10 F100", "\r\nGrbl 1.1h ['\$' for help]\r\n")

        assertEquals(CommandResult.Reset, result)
        assertEquals(
            ControllerEvent.ResetDetected("Grbl 1.1h ['\$' for help]"),
            withTimeout(1_000L) { events.await() }
        )
        f.controller.close()
    }

    @Test
    fun `settings lines are published as events`() = runBlocking {
        val f = Fixture()
        f.connectIdle()
        val settings = async(start = CoroutineStart.UNDISPATCHED) {
            f.controller.events
                .take(2)
                .toList()
        }

        val result = f.answer("\$\$", "\$30=1000\r\n\$110=6000.000\r\nok\r\n")

        assertEquals(CommandResult.Acknowledged, result)
        assertEquals(
            listOf(
                ControllerEvent.SettingReceived(30, "1000"),
                ControllerEvent.SettingReceived(110, "6000.000")
            ),
            withTimeout(1_000L) { settings.await() }
        )
        f.controller.close()
    }

    @Test
    fun `work position reports are converted with the offset`() = runBlocking {
        val f = Fixture()
        f.transport.respondToStatusQueries = false
        f.controller.connect()

        f.transport.emitIncomingText("<Idle|WPos:1.000,2.000,0.000|FS:0,0|WCO:10.000,20.000,0.000>\r\n")
        f.awaitState(MachineState.Idle)

        assertEquals(MachinePosition(10.0, 20.0, 0.0), f.controller.workCoordinateOffset.value)
        assertEquals(MachinePosition(11.0, 22.0, 0.0), f.controller.machinePosition.value)
        f.controller.close()
    }

    @Test
    fun `door and home states are mapped`() = runBlocking {
        val f = Fixture()
        f.transport.respondToStatusQueries = false
        f.controller.connect()

        f.transport.emitIncomingText("<Door:1|MPos:0.000,0.000,0.000|FS:0,0>\r\n")
        f.awaitState(MachineState.Hold)
        f.transport.emitIncomingText("<Home|MPos:0.000,0.000,0.000|FS:0,0>\r\n")
        f.awaitState(MachineState.Running)
        f.controller.close()
    }

    @Test
    fun `jog is refused when the machine is not idle`() = runBlocking {
        val f = Fixture()
        f.transport.respondToStatusQueries = false
        f.controller.connect()
        f.transport.emitIncomingText("<Run|MPos:0.000,0.000,0.000|FS:0,0>\r\n")
        f.awaitState(MachineState.Running)

        assertEquals(
            CommandResult.Rejected(null),
            f.controller.jog(JogRequest(x = 1.0, feedRate = 100.0))
        )
        assertEquals(CommandResult.Rejected(null), f.controller.jogToZero(1_000.0))
        assertEquals(emptyList(), f.transport.simulator.receivedCommands)
        f.controller.close()
    }

    @Test
    fun `realtime commands are single bytes`() = runBlocking {
        val f = Fixture()
        f.connectIdle()

        val before = f.transport.realtimeCommands.size

        f.controller.pause()
        f.controller.resume()
        f.controller.cancelJog()
        f.controller.stop()

        assertEquals(
            listOf('!'.code, '~'.code, 0x85, 0x18),
            f.transport.realtimeCommands.drop(before).map { it.toInt() and 0xFF }
        )
        assertEquals(emptyList(), f.transport.simulator.receivedCommands)
        f.controller.close()
    }

    @Test
    fun `disconnection completes the pending command`() = runBlocking {
        val f = Fixture()
        f.connectIdle()
        f.transport.timeoutAtCommand = 1
        val pending = async { f.controller.executeCommand("G1 X1 F100") }
        withTimeout(1_000L) {
            while (f.transport.simulator.receivedCommands.isEmpty()) delay(5)
        }

        launch { f.transport.disconnect() }

        assertEquals(CommandResult.Disconnected, withTimeout(1_000L) { pending.await() })
        assertEquals(MachineState.Disconnected, f.controller.machineState.value)
        f.controller.close()
    }
}
