package com.laserdroid.core.jobs

import com.laserdroid.controllers.api.CncController
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.core.gcode.GCodeAnalysis
import com.laserdroid.core.gcode.GCodeAnalyzer
import com.laserdroid.core.gcode.GCodeModalTracker
import com.laserdroid.core.gcode.GCodeSource
import com.laserdroid.core.gcode.LaserOffTransform
import com.laserdroid.core.gcode.MachineProfile
import com.laserdroid.core.gcode.toGCodeLines
import com.laserdroid.core.gcode.useLines
import com.laserdroid.core.machine.MachineManager
import com.laserdroid.core.streaming.SendResponseStreamingEngine
import com.laserdroid.core.streaming.StreamingFailure
import com.laserdroid.core.streaming.StreamingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Configuration of [JobManager].
 */
data class JobConfig(
    /** Status polling period while a job runs (5 Hz). */
    val statusPollMillis: Long = 200L,

    /**
     * No data at all from the controller during this time while
     * waiting for an acknowledgement = communication failure.
     */
    val ackInactivityTimeoutMillis: Long = 10_000L,

    /** Max wait for Hold before the reset when stopping. */
    val stopHoldTimeoutMillis: Long = 1_500L,

    /** Tolerance between expected and reported position (mm). */
    val recoveryToleranceMillimeters: Double = 0.5
)

/**
 * Owns the job lifecycle: load, analyze, start, pause, resume, stop.
 *
 * - Independent of Android and of the UI lifecycle.
 * - Takes exclusive control of the machine through
 *   [MachineManager.beginJob] while running.
 * - Never restarts or resumes a job automatically, in particular
 *   after a disconnection.
 */
class JobManager(
    private val config: JobConfig = JobConfig(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _snapshot =
        MutableStateFlow(JobSnapshot.EMPTY)

    val snapshot: StateFlow<JobSnapshot> =
        _snapshot.asStateFlow()

    private val commandMutex = Mutex()

    private var source: GCodeSource? = null

    private var controller: CncController? = null

    private var machine: MachineManager? = null

    private var runJob: Job? = null

    @Volatile
    private var stopping = false

    /*
     * ------------------------------------------------------------
     * Machine attachment
     * ------------------------------------------------------------
     */

    /**
     * Associates the job manager with a connected machine.
     */
    fun attachMachine(
        controller: CncController,
        machine: MachineManager
    ) {
        this.controller = controller
        this.machine = machine
    }

    /**
     * Must be called when the machine connection is lost or closed,
     * before the backend is destroyed. An active job becomes
     * DISCONNECTED and is never resumed.
     */
    fun detachMachine(reason: String = "Machine disconnected") {
        val active = runJob
        runJob = null
        active?.cancel()

        _snapshot.update { current ->
            if (current.state.isActive) {
                current.copy(
                    state = JobState.DISCONNECTED,
                    endedAtMillis = clock(),
                    message = reason
                )
            } else {
                current
            }
        }

        controller = null
        machine = null
    }

    /*
     * ------------------------------------------------------------
     * Loading
     * ------------------------------------------------------------
     */

    /**
     * Analyzes [newSource] and makes it the current program.
     * Refused while a job is active.
     */
    suspend fun load(
        newSource: GCodeSource,
        profile: MachineProfile? = null
    ): Result<GCodeAnalysis> = commandMutex.withLock {

        if (_snapshot.value.state.isActive) {
            return@withLock Result.failure(
                IllegalStateException("A job is running")
            )
        }

        source = null
        _snapshot.value = JobSnapshot(
            state = JobState.LOADING,
            name = newSource.name
        )

        val machineProfile = profile
            ?: machine?.settings?.value
                ?.let { MachineProfile.fromGrblSettings(it) }
            ?: MachineProfile()

        val result = runCatching {
            withContext(ioDispatcher) {
                GCodeAnalyzer(profile = machineProfile)
                    .analyze(newSource)
            }
        }

        result
            .onSuccess { analysis ->
                source = newSource
                _snapshot.value = JobSnapshot(
                    state = JobState.READY,
                    name = newSource.name,
                    analysis = analysis,
                    totalCommands = analysis.executableCommands
                )
            }
            .onFailure { exception ->
                if (exception is CancellationException) {
                    throw exception
                }
                _snapshot.value = JobSnapshot(
                    state = JobState.ERROR,
                    name = newSource.name,
                    message = "Analysis failed: " +
                            (exception.message ?: exception.javaClass.simpleName)
                )
            }

        result
    }

    /** Forgets the current program. Refused while a job is active. */
    fun clear(): Boolean {
        if (_snapshot.value.state.isActive) {
            return false
        }
        source = null
        _snapshot.value = JobSnapshot.EMPTY
        return true
    }

    /*
     * ------------------------------------------------------------
     * Commands
     * ------------------------------------------------------------
     */

    /**
     * Starts the loaded program.
     *
     * @param laserOff dry run: M3/M4 -> M5 and S -> S0 while
     * streaming. The file is not modified.
     * @param airAssist air assist on during the job: M8 before the
     * first line, M9 after the last one (a reset also stops it).
     */
    suspend fun start(
        laserOff: Boolean,
        airAssist: Boolean = false
    ): JobStartResult = commandMutex.withLock {

        val current = _snapshot.value
        val programSource = source
        val activeController = controller
        val activeMachine = machine
        val analysis = current.analysis

        val rejection = when {
            current.isRecoverable ->
                "Interrupted job: choose Resume, Restart or Cancel"
            !current.state.canStart -> "Job state ${current.state}"
            programSource == null || analysis == null -> "No program loaded"
            activeController == null || activeMachine == null -> "Machine not connected"
            analysis.hasErrors -> "Program has ${analysis.errorCount} blocking error(s)"
            analysis.executableCommands == 0 -> "Program is empty"
            else -> null
        }

        if (rejection != null) {
            return@withLock JobStartResult.Rejected(rejection)
        }

        if (!activeMachine!!.beginJob()) {
            return@withLock JobStartResult.Rejected(
                "Machine is not Idle (${activeMachine.snapshot.value.machineState})"
            )
        }

        stopping = false

        _snapshot.value = JobSnapshot(
            state = JobState.STARTING,
            name = current.name,
            analysis = analysis,
            laserOff = laserOff,
            airAssist = airAssist,
            totalCommands = analysis!!.executableCommands,
            startedAtMillis = clock()
        )

        runJob = scope.launch {
            runProgram(
                programSource!!,
                activeController!!,
                activeMachine,
                laserOff,
                preamble = if (airAssist) listOf(WorkshopCommands.AIR_ON) else emptyList(),
                airAssist = airAssist
            )
        }

        JobStartResult.Started
    }

    /**
     * Evaluates the interrupted job against the position reported
     * by the reconnected machine. Null if nothing to recover.
     */
    suspend fun assessRecovery(): RecoveryAssessment? {
        val current = _snapshot.value
        val programSource = source ?: return null
        val activeController = controller ?: return null
        val activeMachine = machine ?: return null
        val line = current.currentLine ?: return null
        if (!current.isRecoverable) return null

        val modal = withContext(ioDispatcher) {
            programSource.useLines { lines ->
                GCodeModalTracker.stateAfter(lines.toGCodeLines(), line)
            }
        }

        val machineState = activeMachine.snapshot.value
        val connected = machineState.isConnected
        val offset = activeController.workCoordinateOffset.value
        val currentX = if (connected) machineState.position.x - offset.x else null
        val currentY = if (connected) machineState.position.y - offset.y else null

        val expectedX = modal.x
        val expectedY = modal.y
        val trusted = expectedX != null && expectedY != null &&
                currentX != null && currentY != null &&
                abs(expectedX - currentX) <= config.recoveryToleranceMillimeters &&
                abs(expectedY - currentY) <= config.recoveryToleranceMillimeters

        return RecoveryAssessment(
            lineNumber = line,
            commandsDone = current.commandsSent,
            totalCommands = current.totalCommands,
            expectedX = expectedX,
            expectedY = expectedY,
            currentX = currentX,
            currentY = currentY,
            positionTrusted = trusted,
            positionUncertain = modal.positionUncertain
        )
    }

    /**
     * Continues an interrupted job (manual decision only).
     *
     * If the reported position does not match the expected one
     * (GRBL reset), the work origin is first redefined with
     * G10 L20 so that the current head position becomes the last
     * executed point. The head must not have been moved.
     */
    suspend fun recover(
        mode: RecoveryMode,
        laserOff: Boolean,
        airAssist: Boolean = false
    ): JobStartResult {
        val assessment = assessRecovery()
            ?: return JobStartResult.Rejected("Nothing to recover")

        return commandMutex.withLock {
            val current = _snapshot.value
            val programSource = source
            val activeController = controller
            val activeMachine = machine
            val analysis = current.analysis

            if (programSource == null || activeController == null ||
                activeMachine == null || analysis == null || !current.isRecoverable
            ) {
                return@withLock JobStartResult.Rejected("Nothing to recover")
            }
            if (!assessment.positionTrusted && !assessment.canResynchronize) {
                return@withLock JobStartResult.Rejected(
                    "Unknown position at line ${assessment.lineNumber}"
                )
            }

            val preamble = mutableListOf<String>()
            if (!assessment.positionTrusted) {
                preamble += WorkshopCommands.resynchronize(
                    assessment.expectedX!!,
                    assessment.expectedY!!
                )
            }

            val resume = mode == RecoveryMode.RESUME
            if (resume) {
                val modal = withContext(ioDispatcher) {
                    programSource.useLines { lines ->
                        GCodeModalTracker.stateAfter(
                            lines.toGCodeLines(),
                            assessment.lineNumber
                        )
                    }
                }
                preamble += WorkshopCommands.resumePreamble(modal, laserOff)
            }

            if (airAssist) {
                preamble += WorkshopCommands.AIR_ON
            }

            if (!activeMachine.beginJob()) {
                return@withLock JobStartResult.Rejected(
                    "Machine is not Idle (${activeMachine.snapshot.value.machineState})"
                )
            }

            stopping = false
            val alreadyDone = if (resume) assessment.commandsDone else 0
            val startAfterLine = if (resume) assessment.lineNumber else 0L

            _snapshot.value = JobSnapshot(
                state = JobState.STARTING,
                name = current.name,
                analysis = analysis,
                laserOff = laserOff,
                airAssist = airAssist,
                totalCommands = analysis.executableCommands,
                commandsSent = alreadyDone,
                commandsAcknowledged = alreadyDone,
                currentLine = if (resume) assessment.lineNumber else null,
                startedAtMillis = clock(),
                message = if (resume) {
                    "Resumed after line ${assessment.lineNumber}"
                } else {
                    "Restarted from the beginning"
                }
            )

            runJob = scope.launch {
                runProgram(
                    programSource,
                    activeController,
                    activeMachine,
                    laserOff,
                    preamble = preamble,
                    startAfterLine = startAfterLine,
                    alreadyDone = alreadyDone,
                    airAssist = airAssist
                )
            }

            JobStartResult.Started
        }
    }

    /**
     * Abandons an interrupted job. The program stays loaded (READY).
     */
    fun cancelRecovery(): Boolean {
        val current = _snapshot.value
        if (current.state != JobState.DISCONNECTED || source == null) {
            return false
        }
        val analysis = current.analysis ?: return false
        _snapshot.value = JobSnapshot(
            state = JobState.READY,
            name = current.name,
            analysis = analysis,
            totalCommands = analysis.executableCommands,
            message = "Interrupted job cancelled"
        )
        return true
    }

    /** Feed hold ('!'). */
    suspend fun pause(): Boolean = commandMutex.withLock {
        val activeController = controller ?: return@withLock false
        if (!_snapshot.value.state.canPause) {
            return@withLock false
        }
        // State first: the watcher may see Hold immediately.
        transition(JobState.HOLDING)
        activeController.pause()
        true
    }

    /** Cycle start ('~'). Manual only. */
    suspend fun resume(): Boolean = commandMutex.withLock {
        val activeController = controller ?: return@withLock false
        if (!_snapshot.value.state.canResume) {
            return@withLock false
        }
        // State first: the watcher may see Run immediately.
        transition(JobState.RESUMING)
        activeController.resume()
        true
    }

    /**
     * Stops the job: feeding stops, then feed hold, then soft reset.
     *
     * The hold before the reset decelerates the machine and keeps
     * the position (a reset in motion raises ALARM:3).
     * With [immediate] the reset is sent at once.
     */
    suspend fun stop(
        immediate: Boolean = false
    ): Boolean = commandMutex.withLock {
        val activeController = controller
        if (!_snapshot.value.state.isActive || activeController == null) {
            return@withLock false
        }

        stopping = true
        runJob?.cancelAndJoin()
        runJob = null

        runCatching {
            if (!immediate) {
                activeController.pause()
                withTimeoutOrNull(config.stopHoldTimeoutMillis.milliseconds) {
                    activeController.machineState.first {
                        it == MachineState.Hold ||
                                it == MachineState.Idle ||
                                it == MachineState.Alarm
                    }
                }
            }
            activeController.stop()
        }

        finish(
            state = JobState.ABORTED,
            message = "Stopped by user"
        )

        /*
         * GRBL restarts after Ctrl-X: refresh the state so that the
         * UI does not keep showing Hold.
         */
        scope.launch {
            delay((config.stopHoldTimeoutMillis / 3).milliseconds)
            runCatching { activeController.requestStatus() }
        }
        true
    }

    /** Cancels the running job coroutine and the internal scope. */
    fun close() {
        runJob?.cancel()
        scope.cancel()
    }

    /*
     * ------------------------------------------------------------
     * Execution
     * ------------------------------------------------------------
     */

    private suspend fun runProgram(
        programSource: GCodeSource,
        activeController: CncController,
        activeMachine: MachineManager,
        laserOff: Boolean,
        preamble: List<String> = emptyList(),
        startAfterLine: Long = 0L,
        alreadyDone: Int = 0,
        airAssist: Boolean = false
    ) {
        val engine = SendResponseStreamingEngine(
            controller = activeController,
            ackInactivityTimeoutMillis = config.ackInactivityTimeoutMillis
        )

        coroutineScope {

            val poller = launch { pollStatus(activeController) }
            val progress = launch { mirrorProgress(engine, alreadyDone) }
            val watcher = launch { watchMachineState(activeController) }

            try {
                transition(JobState.RUNNING)

                for (command in preamble) {
                    val ack = activeController.executeCommand(
                        command,
                        config.ackInactivityTimeoutMillis
                    )
                    if (ack != CommandResult.Acknowledged) {
                        runCatching { activeController.stop() }
                        finish(JobState.ERROR, "Preparation command '$command' failed: $ack")
                        return@coroutineScope
                    }
                }

                val total = _snapshot.value.totalCommands - alreadyDone
                val result = withContext(ioDispatcher) {
                    programSource.useLines { rawLines ->
                        var lines = rawLines.toGCodeLines()
                        if (startAfterLine > 0L) {
                            lines = lines.filter { it.originalLineNumber > startAfterLine }
                        }
                        if (laserOff) {
                            lines = lines.map { LaserOffTransform.apply(it) }
                        }
                        engine.execute(lines, total)
                    }
                }

                handleStreamingResult(result, activeController, airAssist)

            } catch (exception: CancellationException) {
                // stop() / detachMachine() own the final state.
                throw exception
            } catch (exception: Exception) {
                runCatching { activeController.stop() }
                finish(
                    JobState.ERROR,
                    "Streaming failure: " +
                            (exception.message ?: exception.javaClass.simpleName)
                )
            } finally {
                poller.cancel()
                progress.cancel()
                watcher.cancel()
                mirrorProgressOnce(engine, alreadyDone)
                activeMachine.endJob()
            }

        }
    }

    private suspend fun handleStreamingResult(
        result: StreamingState,
        activeController: CncController,
        airAssist: Boolean
    ) {
        when (result) {
            is StreamingState.Completed -> {
                if (airAssist) {
                    // Executed after the last motion (GRBL syncs M9).
                    activeController.executeCommand(
                        WorkshopCommands.AIR_OFF,
                        config.ackInactivityTimeoutMillis
                    )
                }
                waitForCompletion(activeController)
            }

            is StreamingState.Failed -> {
                _snapshot.update {
                    it.copy(
                        errorLine = result.lineNumber,
                        errorCommand = result.command
                    )
                }
                when (val reason = result.reason) {
                    is StreamingFailure.Rejected -> {
                        // Stop feeding and stop the machine.
                        runCatching { activeController.stop() }
                        finish(
                            JobState.ERROR,
                            "GRBL error:${reason.code ?: "?"} at line ${result.lineNumber}: ${result.command}"
                        )
                    }
                    is StreamingFailure.Alarm -> finish(
                        JobState.ERROR,
                        "GRBL ALARM:${reason.code ?: "?"} at line ${result.lineNumber}"
                    )
                    StreamingFailure.Timeout -> {
                        runCatching { activeController.stop() }
                        finish(
                            JobState.ERROR,
                            "No response from controller at line ${result.lineNumber}"
                        )
                    }
                    StreamingFailure.Disconnected -> finish(
                        JobState.DISCONNECTED,
                        "Connection lost at line ${result.lineNumber}"
                    )
                    StreamingFailure.Reset -> if (!stopping) {
                        finish(
                            JobState.ERROR,
                            "Controller reset during the job (line ${result.lineNumber})"
                        )
                    }
                }
            }

            is StreamingState.Cancelled,
            StreamingState.Idle,
            StreamingState.Running -> Unit
        }
    }

    /*
     * Every line is acknowledged: wait until the controller reports
     * Idle, which means that the planner buffer is empty and the
     * motion has physically ended.
     */
    private suspend fun waitForCompletion(
        activeController: CncController
    ) {
        transition(JobState.COMPLETING)

        // Make sure at least one fresh status report was received.
        delay((config.statusPollMillis * 2).milliseconds)

        val finalState = activeController.machineState.first { state ->
            state == MachineState.Idle ||
                    state == MachineState.Alarm ||
                    state == MachineState.Disconnected ||
                    state is MachineState.Error
        }

        when (finalState) {
            MachineState.Idle -> {
                _snapshot.update { it.copy(executionConfirmed = true) }
                finish(JobState.COMPLETED, "Job completed")
            }
            MachineState.Disconnected -> finish(
                JobState.DISCONNECTED,
                "Connection lost before the end of the motion"
            )
            else -> finish(
                JobState.ERROR,
                "Machine state $finalState before the end of the job"
            )
        }
    }

    private suspend fun pollStatus(
        activeController: CncController
    ) {
        while (currentCoroutineContext().isActive) {
            try {
                activeController.requestStatus()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // Write failure: the streaming engine reports it.
            }
            delay(config.statusPollMillis.milliseconds)
        }
    }

    private suspend fun mirrorProgress(
        engine: SendResponseStreamingEngine,
        offset: Int
    ) {
        engine.progress.collect { progress ->
            _snapshot.update { current ->
                if (current.state.isActive && progress.currentOriginalLine != null) {
                    current.copy(
                        commandsSent = offset + progress.commandsSent,
                        commandsAcknowledged = offset + progress.commandsAcknowledged,
                        currentLine = progress.currentOriginalLine
                    )
                } else {
                    current
                }
            }
        }
    }

    private fun mirrorProgressOnce(
        engine: SendResponseStreamingEngine,
        offset: Int
    ) {
        val progress = engine.progress.value
        if (progress.currentOriginalLine == null) {
            return
        }
        _snapshot.update {
            it.copy(
                commandsSent = offset + progress.commandsSent,
                commandsAcknowledged = offset + progress.commandsAcknowledged,
                currentLine = progress.currentOriginalLine
            )
        }
    }

    /*
     * Machine state -> job state while running.
     */
    private suspend fun watchMachineState(
        activeController: CncController
    ) {
        activeController.machineState.collect { machineState ->
            val jobState = _snapshot.value.state
            when (machineState) {
                MachineState.Hold -> if (
                    jobState == JobState.RUNNING ||
                    jobState == JobState.HOLDING
                ) {
                    transition(JobState.PAUSED)
                }

                MachineState.Running,
                MachineState.Idle -> if (
                    jobState == JobState.RESUMING
                ) {
                    transition(JobState.RUNNING)
                }

                MachineState.Alarm -> if (!stopping) {
                    val active = runJob
                    finish(JobState.ERROR, "GRBL ALARM during the job")
                    active?.cancel()
                }

                else -> Unit
            }
        }
    }

    private fun transition(state: JobState) {
        _snapshot.update { current ->
            if (current.state.isActive || state == JobState.STARTING) {
                current.copy(state = state)
            } else {
                current
            }
        }
    }

    private fun finish(state: JobState, message: String) {
        _snapshot.update { current ->
            if (!current.state.isActive) {
                // Already finished (first cause wins).
                current
            } else {
                current.copy(
                    state = state,
                    endedAtMillis = clock(),
                    message = message
                )
            }
        }
    }
}
