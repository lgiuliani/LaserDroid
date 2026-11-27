package com.laserdroid.core.jobs

import com.laserdroid.controllers.api.CncController
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.ControllerCapabilities
import com.laserdroid.controllers.api.ControllerEvent
import com.laserdroid.controllers.api.JogRequest
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.core.gcode.TextGCodeSource
import com.laserdroid.core.machine.MachineManager
import java.util.Collections
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

class JobManagerTest {

    /**
     * Minimal controller: Run while commands arrive, Idle a few
     * status polls after the last acknowledgement.
     */
    private class FakeController : CncController {

        val state = MutableStateFlow<MachineState>(MachineState.Idle)
        override val machineState: StateFlow<MachineState> get() = state
        override val machinePosition = MutableStateFlow(MachinePosition.ZERO)
        override val events = MutableSharedFlow<ControllerEvent>()
        override val capabilities = ControllerCapabilities()

        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val realtime: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile var ackDelayMillis = 2L
        @Volatile var rejectAt: Int? = null
        @Volatile var disconnectAt: Int? = null
        @Volatile private var busyPolls = 0

        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit

        override suspend fun executeCommand(command: String): CommandResult =
            executeCommand(command, 2_000L)

        override suspend fun executeCommand(
            command: String,
            inactivityTimeoutMillis: Long
        ): CommandResult {
            commands.add(command)
            val number = commands.size
            // Planner full while holding: no ok until resumed.
            while (state.value == MachineState.Hold) {
                delay(5)
            }
            delay(ackDelayMillis)
            if (number == rejectAt) {
                return CommandResult.Rejected(20)
            }
            if (number == disconnectAt) {
                state.value = MachineState.Disconnected
                return CommandResult.Disconnected
            }
            if (state.value == MachineState.Idle) {
                state.value = MachineState.Running
            }
            busyPolls = 3
            return CommandResult.Acknowledged
        }

        override suspend fun requestStatus() {
            realtime.add("?")
            if (state.value == MachineState.Running) {
                busyPolls--
                if (busyPolls <= 0) {
                    state.value = MachineState.Idle
                }
            }
        }

        override suspend fun pause() {
            realtime.add("!")
            if (state.value == MachineState.Running) {
                state.value = MachineState.Hold
            }
        }

        override suspend fun resume() {
            realtime.add("~")
            if (state.value == MachineState.Hold) {
                state.value = MachineState.Running
            }
        }

        override suspend fun stop() {
            realtime.add("RESET")
            state.value = MachineState.Idle
        }

        override suspend fun jog(request: JogRequest) = CommandResult.Acknowledged
        override suspend fun jogToZero(feedRate: Double) = CommandResult.Acknowledged
        override suspend fun cancelJog() = Unit
    }

    private val config = JobConfig(
        statusPollMillis = 20L,
        ackInactivityTimeoutMillis = 1_000L,
        stopHoldTimeoutMillis = 200L
    )

    private fun program(lines: Int): String = buildString {
        appendLine("; test")
        appendLine("G21 G90")
        appendLine("G0 X0 Y0")
        appendLine("M4 S500 F1200")
        repeat(lines) { index -> appendLine("G1 X${index % 50} Y${index / 50}") }
        appendLine("M5")
    }

    private class Fixture(
        val controller: FakeController,
        val machine: MachineManager,
        val jobs: JobManager
    )

    private suspend fun fixture(config: JobConfig = this.config): Fixture {
        val controller = FakeController()
        val machine = MachineManager(controller, statusPollIntervalMillis = 20L)
        waitUntil { machine.snapshot.value.machineState == MachineState.Idle }
        val jobs = JobManager(config)
        jobs.attachMachine(controller, machine)
        return Fixture(controller, machine, jobs)
    }

    private suspend fun waitUntil(
        timeoutMillis: Long = 5_000L,
        condition: () -> Boolean
    ) {
        withTimeout(timeoutMillis) {
            while (!condition()) {
                delay(5)
            }
        }
    }

    private suspend fun Fixture.waitForState(state: JobState, timeoutMillis: Long = 10_000L) {
        waitUntil(timeoutMillis) { jobs.snapshot.value.state == state }
    }

    @Test
    fun `load analyzes the program`() = runBlocking<Unit> {
        val f = fixture()
        val result = f.jobs.load(TextGCodeSource("job.nc", program(100)))
        assertTrue(result.isSuccess)
        val snapshot = f.jobs.snapshot.value
        assertEquals(JobState.READY, snapshot.state)
        assertEquals(104, snapshot.totalCommands)
        assertTrue(snapshot.analysis!!.usesLaser)
    }

    @Test
    fun `job completes only after the machine is idle`() = runBlocking<Unit> {
        val f = fixture()
        f.jobs.load(TextGCodeSource("job.nc", program(200)))

        assertIs<JobStartResult.Started>(f.jobs.start(laserOff = false))
        assertTrue(f.machine.snapshot.value.jobActive)
        assertFalse(f.machine.snapshot.value.canJog)

        f.waitForState(JobState.COMPLETED)

        val snapshot = f.jobs.snapshot.value
        assertTrue(snapshot.executionConfirmed)
        assertEquals(204, snapshot.commandsSent)
        assertEquals(204, snapshot.commandsAcknowledged)
        assertEquals(100.0, snapshot.percent)
        assertEquals(204, f.controller.commands.size)
        assertEquals("M4 S500 F1200", f.controller.commands[2])
        assertEquals(MachineState.Idle, f.controller.state.value)

        waitUntil { !f.machine.snapshot.value.jobActive }
        assertTrue(f.controller.realtime.contains("?"))
    }

    @Test
    fun `laser off mode never sends power`() = runBlocking<Unit> {
        val f = fixture()
        f.jobs.load(TextGCodeSource("job.nc", program(20)))
        f.jobs.start(laserOff = true)
        f.waitForState(JobState.COMPLETED)

        assertEquals("M5 S0 F1200", f.controller.commands[2])
        assertTrue(f.controller.commands.none { it.contains("M3") || it.contains("M4") })
        assertTrue(f.controller.commands.none { Regex("S[1-9]").containsMatchIn(it) })
    }

    @Test
    fun `grbl error stops feeding and resets`() = runBlocking<Unit> {
        val f = fixture()
        f.controller.rejectAt = 50
        f.jobs.load(TextGCodeSource("job.nc", program(200)))
        f.jobs.start(laserOff = true)
        f.waitForState(JobState.ERROR)

        assertEquals(50, f.controller.commands.size)
        assertTrue(f.controller.realtime.contains("RESET"))
        val snapshot = f.jobs.snapshot.value
        assertEquals(50, snapshot.commandsSent)
        assertEquals(49, snapshot.commandsAcknowledged)
        // Line 1 is a comment: command 50 is on line 51.
        assertEquals(51L, snapshot.errorLine)
        waitUntil { !f.machine.snapshot.value.jobActive }
    }

    @Test
    fun `stop holds then resets and never sends more lines`() = runBlocking<Unit> {
        val f = fixture()
        f.controller.ackDelayMillis = 5L
        f.jobs.load(TextGCodeSource("job.nc", program(2_000)))
        f.jobs.start(laserOff = true)
        waitUntil { f.controller.commands.size > 20 }

        assertTrue(f.jobs.stop())

        val snapshot = f.jobs.snapshot.value
        assertEquals(JobState.ABORTED, snapshot.state)
        val sentAtStop = f.controller.commands.size
        val holdIndex = f.controller.realtime.indexOf("!")
        val resetIndex = f.controller.realtime.indexOf("RESET")
        assertTrue(holdIndex in 0 until resetIndex)

        delay(200)
        assertEquals(sentAtStop, f.controller.commands.size)
        assertFalse(f.machine.snapshot.value.jobActive)
    }

    @Test
    fun `pause and manual resume`() = runBlocking<Unit> {
        val f = fixture()
        f.controller.ackDelayMillis = 5L
        f.jobs.load(TextGCodeSource("job.nc", program(300)))
        f.jobs.start(laserOff = true)
        waitUntil { f.controller.commands.size > 10 }

        assertTrue(f.jobs.pause())
        f.waitForState(JobState.PAUSED)
        val sentDuringHold = f.controller.commands.size
        delay(150)
        // Only the line waiting for its ok may have been sent.
        assertTrue(f.controller.commands.size <= sentDuringHold + 1)

        assertTrue(f.jobs.resume())
        f.waitForState(JobState.RUNNING)
        f.waitForState(JobState.COMPLETED)
        assertEquals(304, f.controller.commands.size)
    }

    @Test
    fun `disconnection is final and not resumed`() = runBlocking<Unit> {
        val f = fixture()
        f.controller.disconnectAt = 30
        f.jobs.load(TextGCodeSource("job.nc", program(200)))
        f.jobs.start(laserOff = true)
        f.waitForState(JobState.DISCONNECTED)
        f.jobs.detachMachine()

        assertEquals(30, f.controller.commands.size)
        delay(200)
        assertEquals(30, f.controller.commands.size)
        assertEquals(JobState.DISCONNECTED, f.jobs.snapshot.value.state)

        // No machine: a new start is refused.
        assertIs<JobStartResult.Rejected>(f.jobs.start(laserOff = true))
    }

    @Test
    fun `detach while running marks the job disconnected`() = runBlocking<Unit> {
        val f = fixture()
        f.controller.ackDelayMillis = 5L
        f.jobs.load(TextGCodeSource("job.nc", program(2_000)))
        f.jobs.start(laserOff = true)
        waitUntil { f.controller.commands.size > 10 }

        f.jobs.detachMachine("USB detached")

        assertEquals(JobState.DISCONNECTED, f.jobs.snapshot.value.state)
        assertEquals("USB detached", f.jobs.snapshot.value.message)
        val sent = f.controller.commands.size
        delay(100)
        assertEquals(sent, f.controller.commands.size)
    }

    @Test
    fun `start is refused when the machine is not idle`() = runBlocking<Unit> {
        val f = fixture()
        f.jobs.load(TextGCodeSource("job.nc", program(10)))
        f.controller.state.value = MachineState.Alarm
        waitUntil { f.machine.snapshot.value.machineState == MachineState.Alarm }

        assertIs<JobStartResult.Rejected>(f.jobs.start(laserOff = true))
        assertEquals(JobState.READY, f.jobs.snapshot.value.state)
        assertTrue(f.controller.commands.isEmpty())
    }

    @Test
    fun `program with blocking errors is refused`() = runBlocking<Unit> {
        val f = fixture()
        f.jobs.load(TextGCodeSource("bad.nc", "G0 X0 Y0\nG1 X10"))
        assertIs<JobStartResult.Rejected>(f.jobs.start(laserOff = true))
    }

    @Test
    fun `alarm during the job is an error`() = runBlocking<Unit> {
        val f = fixture()
        f.controller.ackDelayMillis = 5L
        f.jobs.load(TextGCodeSource("job.nc", program(2_000)))
        f.jobs.start(laserOff = true)
        waitUntil { f.controller.commands.size > 10 }

        f.controller.state.value = MachineState.Alarm
        f.waitForState(JobState.ERROR)
        val sent = f.controller.commands.size
        delay(100)
        assertEquals(sent, f.controller.commands.size)
        waitUntil { !f.machine.snapshot.value.jobActive }
    }

    private suspend fun Fixture.interruptAt(command: Int) {
        controller.disconnectAt = command
        jobs.load(TextGCodeSource("job.nc", program(200)))
        jobs.start(laserOff = false)
        waitForState(JobState.DISCONNECTED)
        waitUntil { !machine.snapshot.value.jobActive }
        // Reconnection: controller back, Idle.
        controller.disconnectAt = null
        controller.commands.clear()
        controller.state.value = MachineState.Idle
        jobs.attachMachine(controller, machine)
        waitUntil { machine.snapshot.value.machineState == MachineState.Idle }
    }

    @Test
    fun `interrupted job must be recovered explicitly`() = runBlocking<Unit> {
        val f = fixture()
        f.interruptAt(30)
        assertTrue(f.jobs.snapshot.value.isRecoverable)
        assertEquals(31L, f.jobs.snapshot.value.currentLine)
        assertIs<JobStartResult.Rejected>(f.jobs.start(laserOff = true))
        assertTrue(f.controller.commands.isEmpty())
    }

    @Test
    fun `resume with verified position`() = runBlocking<Unit> {
        val f = fixture()
        f.interruptAt(30)
        // Command 30 = line 31 = G1 X26 Y0: GRBL kept its position.
        f.controller.machinePosition.value = MachinePosition(26.0, 0.0, 0.0)
        waitUntil { f.machine.snapshot.value.position.x == 26.0 }

        val assessment = f.jobs.assessRecovery()!!
        assertTrue(assessment.positionTrusted)
        assertEquals(31L, assessment.lineNumber)
        assertEquals(30, assessment.commandsDone)

        assertIs<JobStartResult.Started>(f.jobs.recover(RecoveryMode.RESUME, laserOff = false))
        f.waitForState(JobState.COMPLETED)

        val commands = f.controller.commands
        assertEquals(
            listOf("M5 S0", "G21", "G90", "G0 X26 Y0", "F1200", "M4 S500", "G1"),
            commands.take(7)
        )
        assertEquals("G1 X27 Y0", commands[7])
        assertEquals(7 + 174, commands.size)
        assertEquals(204, f.jobs.snapshot.value.commandsAcknowledged)
    }

    @Test
    fun `resume after reset redefines the work origin`() = runBlocking<Unit> {
        val f = fixture()
        f.interruptAt(30)
        // Position lost (hard reset): MPos 0,0.
        val assessment = f.jobs.assessRecovery()!!
        assertFalse(assessment.positionTrusted)

        f.jobs.recover(RecoveryMode.RESUME, laserOff = true)
        f.waitForState(JobState.COMPLETED)

        val commands = f.controller.commands
        assertEquals(listOf("G21", "G90", "G10 L20 P1 X26 Y0"), commands.take(3))
        assertEquals("S0", commands[8])
        assertTrue(commands.none { it.contains("M4") })
    }

    @Test
    fun `restart from the beginning`() = runBlocking<Unit> {
        val f = fixture()
        f.interruptAt(30)
        f.jobs.recover(RecoveryMode.RESTART, laserOff = true)
        f.waitForState(JobState.COMPLETED)

        val commands = f.controller.commands
        assertEquals("G10 L20 P1 X26 Y0", commands[2])
        assertEquals("G21 G90", commands[3])
        assertEquals(3 + 204, commands.size)
    }

    @Test
    fun `cancel recovery keeps the program`() = runBlocking<Unit> {
        val f = fixture()
        f.interruptAt(30)
        assertTrue(f.jobs.cancelRecovery())
        assertEquals(JobState.READY, f.jobs.snapshot.value.state)
        assertIs<JobStartResult.Started>(f.jobs.start(laserOff = true))
        f.waitForState(JobState.COMPLETED)
    }

    @Test
    fun `air assist wraps the job with M8 and M9`() = runBlocking<Unit> {
        val f = fixture()
        f.jobs.load(TextGCodeSource("job.nc", program(20)))
        f.jobs.start(laserOff = true, airAssist = true)
        f.waitForState(JobState.COMPLETED)

        val commands = f.controller.commands
        assertEquals("M8", commands.first())
        assertEquals("M9", commands.last())
        assertEquals(26, commands.size)
        assertEquals(24, f.jobs.snapshot.value.commandsAcknowledged)
        assertTrue(f.jobs.snapshot.value.airAssist)
    }

    @Test
    fun `progress estimates`() {
        val snapshot = JobSnapshot(
            state = JobState.RUNNING,
            totalCommands = 100,
            commandsAcknowledged = 25,
            startedAtMillis = 1_000L
        )
        assertEquals(25.0, snapshot.percent)
        assertEquals(10_000L, snapshot.elapsedMillis(11_000L))
        assertEquals(30_000L, snapshot.remainingMillis(11_000L))
    }
}
