package com.laserdroid.core.jobs

import com.laserdroid.controllers.api.CncController
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.core.gcode.GCodeAnalysis
import com.laserdroid.core.gcode.GCodeAnalyzer
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
    val stopHoldTimeoutMillis: Long = 1_500L
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
     */
    suspend fun start(
        laserOff: Boolean
    ): JobStartResult = commandMutex.withLock {

        val current = _snapshot.value
        val programSource = source
        val activeController = controller
        val activeMachine = machine
        val analysis = current.analysis

        val rejection = when {
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
            totalCommands = analysis!!.executableCommands,
            startedAtMillis = clock()
        )

        runJob = scope.launch {
            runProgram(
                programSource!!,
                activeController!!,
                activeMachine,
                laserOff
            )
        }

        JobStartResult.Started
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
                withTimeoutOrNull(config.stopHoldTimeoutMillis) {
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
        true
    }

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
        laserOff: Boolean
    ) {
        val engine = SendResponseStreamingEngine(
            controller = activeController,
            ackInactivityTimeoutMillis = config.ackInactivityTimeoutMillis
        )

        coroutineScope {

            val poller = launch { pollStatus(activeController) }
            val progress = launch { mirrorProgress(engine) }
            val watcher = launch { watchMachineState(activeController) }

            try {
                transition(JobState.RUNNING)

                val total = _snapshot.value.totalCommands
                val result = withContext(ioDispatcher) {
                    programSource.useLines { rawLines ->
                        var lines = rawLines.toGCodeLines()
                        if (laserOff) {
                            lines = lines.map { LaserOffTransform.apply(it) }
                        }
                        engine.execute(lines, total)
                    }
                }

                handleStreamingResult(result, activeController)

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
                mirrorProgressOnce(engine)
                activeMachine.endJob()
            }

        }
    }

    private suspend fun handleStreamingResult(
        result: StreamingState,
        activeController: CncController
    ) {
        when (result) {
            is StreamingState.Completed -> waitForCompletion(activeController)

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
        delay(config.statusPollMillis * 2)

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
            delay(config.statusPollMillis)
        }
    }

    private suspend fun mirrorProgress(
        engine: SendResponseStreamingEngine
    ) {
        engine.progress.collect { progress ->
            _snapshot.update { current ->
                if (current.state.isActive) {
                    current.copy(
                        commandsSent = progress.commandsSent,
                        commandsAcknowledged = progress.commandsAcknowledged,
                        currentLine = progress.currentOriginalLine
                    )
                } else {
                    current
                }
            }
        }
    }

    private fun mirrorProgressOnce(
        engine: SendResponseStreamingEngine
    ) {
        val progress = engine.progress.value
        _snapshot.update {
            it.copy(
                commandsSent = progress.commandsSent,
                commandsAcknowledged = progress.commandsAcknowledged,
                currentLine = progress.currentOriginalLine ?: it.currentLine
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
