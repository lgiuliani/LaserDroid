package com.laserdroid.core.machine

import com.laserdroid.controllers.api.CncController
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.ControllerEvent
import com.laserdroid.controllers.api.JogRequest
import com.laserdroid.controllers.api.MachineState
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Central arbiter of the machine (specification 3).
 *
 * - Publishes a [MachineSnapshot] built from the controller flows.
 * - Executes manual operations (jog, HOME, unlock, console,
 *   workshop functions) and refuses them while a job owns the
 *   machine ([beginJob] / [endJob]).
 * - Polls the status in the background when no job runs.
 *
 * Independent of Android. Call [close] when discarded.
 */
class MachineManager(
    private val controller: CncController,
    private val statusPollIntervalMillis: Long = 200L
) {

    companion object {
        private const val CONSOLE_TIMEOUT_MILLIS = 5_000L
        private const val HOMING_TIMEOUT_MILLIS = 120_000L

        /*
         * Status refresh outside jobs: keeps the displayed state
         * right after resets, unlocks, console commands...
         */
        private const val BACKGROUND_POLL_MILLIS = 500L

        /* $30 default (Sculpfun S10). */
        private const val DEFAULT_MAX_POWER = 1_000.0
    }

    private var backgroundPollJob: Job? = null

    private val _framingActive =
        MutableStateFlow(false)

    /** True while the job frame is being traced. */
    val framingActive:
            StateFlow<Boolean> =
        _framingActive.asStateFlow()

    val controllerEvents:
            Flow<ControllerEvent>
        get() = controller.events

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.Default
        )

    private val _snapshot =
        MutableStateFlow(
            MachineSnapshot.initial()
        )

    val snapshot:
            StateFlow<MachineSnapshot> =
        _snapshot.asStateFlow()

    private val _events =
        MutableSharedFlow<MachineEvent>(
            extraBufferCapacity = 64
        )

    val events:
            Flow<MachineEvent> =
        _events.asSharedFlow()

    private val _settings =
        MutableStateFlow<Map<Int, String>>(
            emptyMap()
        )

    /**
     * Controller settings received so far ($$ response),
     * for example 110 -> "6000.000".
     */
    val settings:
            StateFlow<Map<Int, String>> =
        _settings.asStateFlow()

    init {
        observeController()
    }

    /*
     * ----------------------------------------------------
     * JOB OWNERSHIP
     * ----------------------------------------------------
     */

    /**
     * Gives exclusive control of the machine to a job.
     *
     * Returns false if a job is already active or if the
     * machine is not Idle.
     */
    fun beginJob(): Boolean {

        while (true) {

            val current =
                _snapshot.value

            if (
                current.jobActive ||
                current.machineState != MachineState.Idle
            ) {
                return false
            }

            if (
                _snapshot.compareAndSet(
                    current,
                    current.copy(
                        jobActive = true
                    )
                )
            ) {
                return true
            }
        }
    }

    /**
     * Exclusive control for a remote program (LaserGRBL bridge):
     * like a job, but allowed in any connected state (Alarm
     * included) so that LaserGRBL can unlock the machine.
     */
    fun beginRemoteControl(): Boolean {

        while (true) {

            val current =
                _snapshot.value

            if (
                current.jobActive ||
                current.machineState == MachineState.Disconnected ||
                current.machineState == MachineState.Connecting
            ) {
                return false
            }

            if (
                _snapshot.compareAndSet(
                    current,
                    current.copy(
                        jobActive = true
                    )
                )
            ) {
                return true
            }
        }
    }

    /**
     * Jog line relayed from LaserGRBL ($J=...). Refused during a job.
     */
    suspend fun relayJog(
        line: String
    ): CommandResult {

        if (!snapshot.value.canJog) {
            return CommandResult.Rejected(code = null)
        }

        return controller.executeCommand(line)
    }

    /**
     * Releases the machine after a job has ended.
     */
    fun endJob() {

        _snapshot.update {
            it.copy(
                jobActive = false
            )
        }
    }

    val isJobActive: Boolean
        get() = snapshot.value.jobActive

    private fun observeController() {

        scope.launch {

            controller.machineState.collect {
                    state ->

                _snapshot.update {
                    it.copy(
                        connectionState =
                            mapConnectionState(
                                state
                            ),

                        machineState =
                            state,

                        lastUpdateTimestamp =
                            System.currentTimeMillis()
                    )
                }
            }
        }

        scope.launch {

            controller.events.collect {
                    event ->

                if (
                    event is ControllerEvent.SettingReceived
                ) {
                    _settings.value =
                        _settings.value +
                                (event.number to event.value)
                }
            }
        }

        scope.launch {

            controller.machinePosition.collect {
                    position ->

                _snapshot.update {
                    it.copy(
                        position =
                            position,

                        lastUpdateTimestamp =
                            System.currentTimeMillis()
                    )
                }

                _events.tryEmit(
                    MachineEvent.PositionUpdated(
                        position
                    )
                )
            }
        }
    }

    /** Connects the controller and starts the background polling. Rethrows failures. */
    suspend fun connect() {

        _snapshot.update {
            it.copy(
                connectionState =
                    MachineConnectionState.CONNECTING,

                machineState =
                    MachineState.Connecting,

                lastUpdateTimestamp =
                    System.currentTimeMillis()
            )
        }

        try {

            // connect() requests the first status itself.
            controller.connect()

            startBackgroundPolling()

            _events.emit(
                MachineEvent.Connected
            )

        } catch (
            exception: Exception
        ) {

            _snapshot.update {
                it.copy(
                    connectionState =
                        MachineConnectionState.ERROR,

                    machineState =
                        MachineState.Error(
                            exception.message
                                ?: "Connection error"
                        ),

                    lastUpdateTimestamp =
                        System.currentTimeMillis()
                )
            }

            _events.emit(
                MachineEvent.Error(
                    message =
                        exception.message
                            ?: "Connection error",

                    cause =
                        exception
                )
            )

            throw exception
        }
    }

    /** Disconnects the controller; the snapshot returns to its initial value. */
    suspend fun disconnect() {

        backgroundPollJob?.cancel()
        backgroundPollJob = null

        try {

            controller.disconnect()

        } finally {

            _snapshot.value =
                MachineSnapshot.initial()

            _events.emit(
                MachineEvent.Disconnected
            )
        }
    }

    /** Realtime status query. */
    suspend fun requestStatus() {

        controller.requestStatus()
    }

    /** `$$`: refreshes [settings]. Refused during a job. */
    suspend fun requestSettings():
            CommandResult {

        if (isJobActive) {
            return CommandResult.Rejected(code = null)
        }

        return controller.executeCommand(
            "\$\$"
        )
    }

    /** `$I`: firmware identification. Refused during a job. */
    suspend fun requestBuildInfo():
            CommandResult {

        if (isJobActive) {
            return CommandResult.Rejected(code = null)
        }

        return controller.executeCommand(
            "\$I"
        )
    }

    /**
     * Sends $X (kill alarm lock). Refused during a job.
     */
    suspend fun unlock():
            CommandResult {

        if (isJobActive) {
            return CommandResult.Rejected(code = null)
        }

        val result =
            controller.executeCommand(
                "\$X"
            )

        controller.requestStatus()

        return result
    }

    /**
     * Sends one console line (G-code or $ command).
     * Refused during a job. Homing ($H) may last long:
     * its acknowledgement timeout is extended.
     */
    suspend fun sendCommand(
        command: String
    ): CommandResult {

        if (isJobActive) {
            return CommandResult.Rejected(code = null)
        }

        val line =
            command.trim()

        if (line.isEmpty()) {
            return CommandResult.Acknowledged
        }

        val timeout =
            if (line.uppercase().startsWith("\$H")) {
                HOMING_TIMEOUT_MILLIS
            } else {
                CONSOLE_TIMEOUT_MILLIS
            }

        val result =
            controller.executeCommand(
                line,
                timeout
            )

        controller.requestStatus()

        return result
    }

    /*
     * ----------------------------------------------------
     * BACKGROUND STATUS
     * ----------------------------------------------------
     */

    private fun startBackgroundPolling() {

        backgroundPollJob?.cancel()

        backgroundPollJob =
            scope.launch {
                while (isActive) {
                    delay(BACKGROUND_POLL_MILLIS)
                    // Jobs and framing poll by themselves.
                    if (!snapshot.value.jobActive) {
                        try {
                            controller.requestStatus()
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            // Link lost: reported elsewhere.
                        }
                    }
                }
            }
    }

    /*
     * ----------------------------------------------------
     * WORKSHOP FUNCTIONS
     * ----------------------------------------------------
     */

    /** $30: S value for 100% laser power. */
    val maxLaserPower: Double
        get() = settings.value[30]
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?: DEFAULT_MAX_POWER

    /**
     * Current position becomes the work origin X0 Y0
     * (G10 L20 P1, stored by GRBL).
     */
    suspend fun setWorkZero(
        includeZ: Boolean = false
    ): CommandResult {

        if (!snapshot.value.canJog) {
            return CommandResult.Rejected(code = null)
        }

        val result =
            controller.executeCommand(
                if (includeZ) "G10 L20 P1 X0 Y0 Z0" else "G10 L20 P1 X0 Y0"
            )

        controller.requestStatus()

        return result
    }

    /**
     * Laser on at [powerPercent] of $30, machine stopped (M3).
     * Only when Idle and no job.
     */
    suspend fun laserPointerOn(
        powerPercent: Double
    ): CommandResult {

        if (!snapshot.value.canJog) {
            return CommandResult.Rejected(code = null)
        }

        val power =
            maxLaserPower * powerPercent.coerceIn(0.0, 100.0) / 100.0

        return controller.executeCommand(
            "M3 S" + formatValue(power)
        )
    }

    /** Laser off (M5). Not sent during a job. */
    suspend fun laserPointerOff(): CommandResult {

        if (isJobActive && !_framingActive.value) {
            return CommandResult.Rejected(code = null)
        }

        return controller.executeCommand(
            "M5 S0"
        )
    }

    /**
     * Executes a short program (job frame) with exclusive control
     * of the machine, then waits until the machine is Idle.
     */
    suspend fun runFraming(
        commands: List<String>,
        pathLengthMillimeters: Double,
        feedRate: Double
    ): MachineOperationResult {

        if (!beginJob()) {
            return MachineOperationResult.Interrupted(
                snapshot.value.machineState
            )
        }

        _framingActive.value = true

        return try {

            for (command in commands) {

                val result =
                    controller.executeCommand(
                        command,
                        CONSOLE_TIMEOUT_MILLIS
                    )

                if (result != CommandResult.Acknowledged) {
                    runCatching { controller.stop() }
                    return MachineOperationResult.CommandRejected(result)
                }
            }

            monitorUntilIdle(
                timeoutMillis =
                    calculateMotionTimeout(
                        distanceMillimeters = pathLengthMillimeters,
                        feedRate = feedRate
                    ) + 5_000L,
                allowRunning = true
            )

        } finally {

            _framingActive.value = false
            endJob()
        }
    }

    /**
     * Stops the framing: feed hold, then soft reset (laser off).
     */
    suspend fun stopFraming() {

        if (!_framingActive.value) {
            return
        }

        controller.pause()
        delay(300L)
        controller.stop()
    }

    private fun formatValue(value: Double): String {
        return BigDecimal
            .valueOf(value)
            .setScale(3, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    /**
     * Soft reset (Ctrl-X): immediate stop, laser off.
     * Always allowed.
     */
    suspend fun softReset() {

        controller.stop()
    }

    /**
     * Relative jog, then waits until the machine is Idle again
     * (timeout derived from distance and feed rate).
     */
    suspend fun executeJog(
        request: JogRequest
    ): MachineOperationResult {

        val currentSnapshot =
            snapshot.value

        if (!currentSnapshot.canJog) {

            return MachineOperationResult.Interrupted(
                currentSnapshot.machineState
            )
        }

        val distance =
            calculateJogDistance(
                request
            )

        val timeout =
            calculateMotionTimeout(
                distanceMillimeters =
                    distance,

                feedRate =
                    request.feedRate
            )

        _events.emit(
            MachineEvent.JogStarted(
                buildString {

                    append("Jog")

                    request.x?.let {
                        append(" X=$it")
                    }

                    request.y?.let {
                        append(" Y=$it")
                    }

                    request.z?.let {
                        append(" Z=$it")
                    }

                    append(
                        " F=${request.feedRate}"
                    )
                }
            )
        )

        return try {

            val result =
                controller.jog(
                    request
                )

            if (
                result !=
                CommandResult.Acknowledged
            ) {

                MachineOperationResult
                    .CommandRejected(
                        result
                    )

            } else {

                monitorUntilIdle(
                    timeoutMillis =
                        timeout
                )
            }

        } catch (
            exception: Exception
        ) {

            _events.emit(
                MachineEvent.Error(
                    message =
                        exception.message
                            ?: "Jog error",

                    cause =
                        exception
                )
            )

            MachineOperationResult.Failed(
                message =
                    exception.message
                        ?: "Jog error",

                cause =
                    exception
            )
        }
    }

    /** Jog to work X0 Y0, then waits until Idle. */
    suspend fun goToZero(
        feedRate: Double
    ): MachineOperationResult {

        require(
            feedRate.isFinite() &&
                    feedRate > 0.0
        ) {
            "Feed rate must be positive and finite"
        }

        val currentSnapshot =
            snapshot.value

        if (!currentSnapshot.canJog) {

            return MachineOperationResult.Interrupted(
                currentSnapshot.machineState
            )
        }

        val position =
            currentSnapshot.position

        val distance =
            hypot(
                position.x,
                position.y
            )

        val timeout =
            calculateMotionTimeout(
                distanceMillimeters =
                    distance,

                feedRate =
                    feedRate
            )

        _events.emit(
            MachineEvent.JogStarted(
                "HOME X0 Y0 F=$feedRate"
            )
        )

        return try {

            val result =
                controller.jogToZero(
                    feedRate =
                        feedRate
                )

            if (
                result !=
                CommandResult.Acknowledged
            ) {

                MachineOperationResult
                    .CommandRejected(
                        result
                    )

            } else {

                monitorUntilIdle(
                    timeoutMillis =
                        timeout
                )
            }

        } catch (
            exception: Exception
        ) {

            _events.emit(
                MachineEvent.Error(
                    message =
                        exception.message
                            ?: "HOME error",

                    cause =
                        exception
                )
            )

            MachineOperationResult.Failed(
                message =
                    exception.message
                        ?: "HOME error",

                cause =
                    exception
            )
        }
    }

    /**
     * Laser off jog to an absolute work position (X/Y only), then
     * waits until Idle. Cancellable with [cancelJog].
     */
    suspend fun goTo(
        x: Double,
        y: Double,
        feedRate: Double
    ): MachineOperationResult {

        require(
            x.isFinite() && y.isFinite() &&
                    feedRate.isFinite() && feedRate > 0.0
        ) {
            "Target and feed rate must be finite, feed rate positive"
        }

        val currentSnapshot =
            snapshot.value

        if (!currentSnapshot.canJog) {
            return MachineOperationResult.Interrupted(
                currentSnapshot.machineState
            )
        }

        val offset =
            controller.workCoordinateOffset.value

        val distance =
            hypot(
                x - (currentSnapshot.position.x - offset.x),
                y - (currentSnapshot.position.y - offset.y)
            )

        _events.emit(
            MachineEvent.JogStarted(
                "Move to X$x Y$y F=$feedRate"
            )
        )

        val result =
            controller.executeCommand(
                "\$J=G21 G90 X${formatValue(x)} Y${formatValue(y)} F${formatValue(feedRate)}"
            )

        if (result != CommandResult.Acknowledged) {
            return MachineOperationResult.CommandRejected(result)
        }

        return monitorUntilIdle(
            timeoutMillis =
                calculateMotionTimeout(
                    distanceMillimeters = distance,
                    feedRate = feedRate
                )
        )
    }

    /**
     * Sends one jog step without waiting for the motion
     * (continuous jog: steps are chained, then [cancelJog]).
     */
    suspend fun startContinuousJog(
        request: JogRequest
    ): CommandResult {

        if (!snapshot.value.canJog) {

            return CommandResult.Rejected(
                code = null
            )
        }

        return controller.jog(
            request
        )
    }

    /** Realtime jog cancel, then refreshes the status. */
    suspend fun cancelJog() {

        controller.cancelJog()

        _events.emit(
            MachineEvent.JogCancelled
        )

        delay(
            statusPollIntervalMillis
        )

        controller.requestStatus()
    }

    private suspend fun monitorUntilIdle(
        timeoutMillis: Long,
        allowRunning: Boolean = false
    ): MachineOperationResult {

        val startTime =
            System.currentTimeMillis()

        /*
         * Allow physical movement to begin.
         */
        delay(
            statusPollIntervalMillis
        )

        while (true) {

            controller.requestStatus()

            delay(
                statusPollIntervalMillis
            )

            val currentSnapshot =
                snapshot.value

            when (
                currentSnapshot.machineState
            ) {

                MachineState.Idle -> {

                    _events.emit(
                        MachineEvent.JogCompleted(
                            currentSnapshot.position
                        )
                    )

                    return MachineOperationResult
                        .Completed(
                            currentSnapshot.position
                        )
                }

                MachineState.Running -> {
                    if (!allowRunning) {
                        return MachineOperationResult
                            .Interrupted(
                                currentSnapshot.machineState
                            )
                    }
                }

                MachineState.Jog -> {
                    // Expected transient state.
                }

                else -> {

                    return MachineOperationResult
                        .Interrupted(
                            currentSnapshot.machineState
                        )
                }
            }

            val elapsed =
                System.currentTimeMillis() -
                        startTime

            if (
                elapsed >=
                timeoutMillis
            ) {

                return MachineOperationResult
                    .Timeout(
                        timeoutMillis =
                            timeoutMillis,

                        lastPosition =
                            currentSnapshot.position
                    )
            }
        }
    }

    /*
     * Length of the jog vector, Z included: a Z-only jog must get
     * a timeout matching its (usually slow) feed rate.
     */
    private fun calculateJogDistance(
        request: JogRequest
    ): Double {

        val x = request.x ?: 0.0
        val y = request.y ?: 0.0
        val z = request.z ?: 0.0

        return sqrt(x * x + y * y + z * z)
    }

    private fun calculateMotionTimeout(
        distanceMillimeters: Double,
        feedRate: Double
    ): Long {

        if (
            distanceMillimeters <= 0.0
        ) {
            return 3_000L
        }

        if (
            !feedRate.isFinite() ||
            feedRate <= 0.0
        ) {
            return 5_000L
        }

        val theoreticalDuration =
            (
                    distanceMillimeters /
                            feedRate
                    ) *
                    60_000.0

        /*
         * 50% margin for acceleration and
         * deceleration, plus three seconds.
         */
        return (
                theoreticalDuration *
                        1.5
                ).toLong() +
                3_000L
    }

    private fun mapConnectionState(
        state: MachineState
    ): MachineConnectionState {

        return when (state) {

            MachineState.Disconnected ->
                MachineConnectionState
                    .DISCONNECTED

            MachineState.Connecting ->
                MachineConnectionState
                    .CONNECTING

            is MachineState.Error ->
                MachineConnectionState
                    .ERROR

            else ->
                MachineConnectionState
                    .CONNECTED
        }
    }

    /** Stops polling and observers. The controller is not closed. */
    fun close() {

        backgroundPollJob?.cancel()

        scope.cancel()
    }
}