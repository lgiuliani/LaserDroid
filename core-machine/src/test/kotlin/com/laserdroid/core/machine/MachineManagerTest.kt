package com.laserdroid.core.machine

import com.laserdroid.controllers.api.CncController
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.ControllerCapabilities
import com.laserdroid.controllers.api.ControllerEvent
import com.laserdroid.controllers.api.JogRequest
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MachineManagerTest {

    /**
     * Controller whose motions last [motionMillis]: Jog (or Run)
     * after an acknowledged motion, Idle once the time has elapsed
     * and a status was requested.
     */
    private class FakeController : CncController {

        val state = MutableStateFlow<MachineState>(MachineState.Disconnected)
        override val machineState: StateFlow<MachineState> get() = state
        override val machinePosition = MutableStateFlow(MachinePosition.ZERO)
        override val workCoordinateOffset = MutableStateFlow(MachinePosition.ZERO)
        override val events = MutableSharedFlow<ControllerEvent>(extraBufferCapacity = 16)
        override val capabilities = ControllerCapabilities(supportsJog = true)

        /** Line commands with their timeout. */
        val commands: MutableList<Pair<String, Long>> =
            Collections.synchronizedList(mutableListOf())
        val statusRequests = AtomicInteger()

        @Volatile var motionMillis = 0L
        @Volatile private var motionEndsAt = 0L

        override suspend fun connect() {
            state.value = MachineState.Idle
        }

        override suspend fun disconnect() {
            state.value = MachineState.Disconnected
        }

        override suspend fun executeCommand(command: String): CommandResult =
            executeCommand(command, 2_000L)

        override suspend fun executeCommand(
            command: String,
            inactivityTimeoutMillis: Long
        ): CommandResult {
            commands += command to inactivityTimeoutMillis
            if (command.startsWith("\$J=")) {
                startMotion(MachineState.Jog)
            }
            return CommandResult.Acknowledged
        }

        override suspend fun requestStatus() {
            statusRequests.incrementAndGet()
            val current = state.value
            if (
                (current == MachineState.Jog || current == MachineState.Running) &&
                System.currentTimeMillis() >= motionEndsAt
            ) {
                state.value = MachineState.Idle
            }
        }

        override suspend fun pause() = Unit
        override suspend fun resume() = Unit
        override suspend fun stop() = Unit

        override suspend fun jog(request: JogRequest): CommandResult {
            commands += "jog $request" to 0L
            startMotion(MachineState.Jog)
            return CommandResult.Acknowledged
        }

        override suspend fun jogToZero(feedRate: Double): CommandResult =
            executeCommand("\$J=G21 G90 X0 Y0 F$feedRate")

        override suspend fun cancelJog() = Unit

        private fun startMotion(motionState: MachineState) {
            motionEndsAt = System.currentTimeMillis() + motionMillis
            state.value = motionState
        }

        fun lastCommand(): String = commands.last().first
    }

    private suspend fun connected(
        controller: FakeController = FakeController()
    ): Pair<MachineManager, FakeController> {
        val manager = MachineManager(controller, statusPollIntervalMillis = 20L)
        manager.connect()
        withTimeout(1_000L) {
            while (manager.snapshot.value.machineState != MachineState.Idle) delay(5)
        }
        return manager to controller
    }

    @Test
    fun `a job takes exclusive control of an idle machine`() = runBlocking {
        val (manager, _) = connected()

        assertTrue(manager.beginJob())
        assertFalse(manager.beginJob())
        assertFalse(manager.beginRemoteControl())
        assertFalse(manager.snapshot.value.canJog)

        manager.endJob()
        assertTrue(manager.snapshot.value.canJog)
        manager.close()
    }

    @Test
    fun `a job is refused when the machine is not idle`() = runBlocking {
        val (manager, controller) = connected()
        controller.state.value = MachineState.Alarm
        withTimeout(1_000L) {
            while (manager.snapshot.value.machineState != MachineState.Alarm) delay(5)
        }

        assertFalse(manager.beginJob())
        // LaserGRBL bridge may take control in Alarm to unlock.
        assertTrue(manager.beginRemoteControl())
        manager.close()
    }

    @Test
    fun `manual commands are refused during a job`() = runBlocking {
        val (manager, controller) = connected()
        assertTrue(manager.beginJob())
        controller.commands.clear()

        assertEquals(CommandResult.Rejected(null), manager.sendCommand("G0 X10"))
        assertEquals(CommandResult.Rejected(null), manager.unlock())
        assertEquals(CommandResult.Rejected(null), manager.requestSettings())
        assertEquals(CommandResult.Rejected(null), manager.setWorkZero())
        assertEquals(CommandResult.Rejected(null), manager.laserPointerOn(5.0))
        assertEquals(CommandResult.Rejected(null), manager.relayJog("\$J=G91 X1 F100"))
        assertIs<MachineOperationResult.Interrupted>(
            manager.executeJog(JogRequest(x = 1.0, feedRate = 1_000.0))
        )
        assertTrue(controller.commands.isEmpty())
        manager.close()
    }

    @Test
    fun `homing gets an extended timeout`() = runBlocking {
        val (manager, controller) = connected()

        manager.sendCommand("  \$h ")
        manager.sendCommand("G0 X1")

        assertEquals("\$h" to 120_000L, controller.commands[0])
        assertEquals("G0 X1" to 5_000L, controller.commands[1])
        manager.close()
    }

    @Test
    fun `jog waits until the machine is idle`() = runBlocking {
        val (manager, controller) = connected()
        controller.motionMillis = 200L

        val result = manager.executeJog(JogRequest(x = 1.0, feedRate = 1_000.0))

        assertIs<MachineOperationResult.Completed>(result)
        manager.close()
    }

    @Test
    fun `Z only jog timeout follows the Z distance`() = runBlocking {
        val (manager, controller) = connected()
        // 5 mm at 100 mm/min = 3 s: longer than the 3 s minimum
        // timeout used for a zero length move.
        controller.motionMillis = 3_500L

        val result = manager.executeJog(JogRequest(z = 5.0, feedRate = 100.0))

        assertIs<MachineOperationResult.Completed>(result)
        manager.close()
    }

    @Test
    fun `settings are collected and set the laser power`() = runBlocking {
        val (manager, controller) = connected()
        controller.events.emit(ControllerEvent.SettingReceived(30, "500.000"))
        withTimeout(1_000L) {
            while (manager.settings.value[30] == null) delay(5)
        }

        assertEquals(500.0, manager.maxLaserPower)
        manager.laserPointerOn(10.0)
        assertEquals("M3 S50", controller.lastCommand())
        manager.close()
    }

    @Test
    fun `goTo sends an absolute laser off jog`() = runBlocking {
        val (manager, controller) = connected()

        val result = manager.goTo(12.3456, -4.0, 3_000.0)

        assertIs<MachineOperationResult.Completed>(result)
        assertEquals("\$J=G21 G90 X12.346 Y-4 F3000", controller.commands[0].first)
        manager.close()
    }

    @Test
    fun `background polling stops after disconnection`() = runBlocking {
        val (manager, controller) = connected()
        delay(1_200L)
        assertTrue(controller.statusRequests.get() >= 2)

        manager.disconnect()
        val count = controller.statusRequests.get()
        delay(1_200L)

        assertEquals(count, controller.statusRequests.get())
        assertEquals(MachineState.Disconnected, manager.snapshot.value.machineState)
        manager.close()
    }
}
