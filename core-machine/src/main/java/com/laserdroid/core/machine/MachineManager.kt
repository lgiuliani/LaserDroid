package com.laserdroid.core.machine

import com.laserdroid.controllers.api.CncController
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.JogRequest
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.launch
import com.laserdroid.controllers.api.ControllerEvent

class MachineManager(
    private val controller: CncController,
    private val statusPollIntervalMillis: Long = 200L
) {

    companion object {
        private const val CONSOLE_TIMEOUT_MILLIS = 5_000L
        private const val HOMING_TIMEOUT_MILLIS = 120_000L
    }

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

            controller.connect()

            controller.requestStatus()

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

    suspend fun disconnect() {

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

    suspend fun requestStatus() {

        controller.requestStatus()
    }

    suspend fun requestSettings():
            CommandResult {

        if (isJobActive) {
            return CommandResult.Rejected(code = null)
        }

        return controller.executeCommand(
            "\$\$"
        )
    }

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

    /**
     * Soft reset (Ctrl-X): immediate stop, laser off.
     * Always allowed.
     */
    suspend fun softReset() {

        controller.stop()
    }

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
        timeoutMillis: Long
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

    private fun calculateJogDistance(
        request: JogRequest
    ): Double {

        val x =
            request.x
                ?: 0.0

        val y =
            request.y
                ?: 0.0

        return hypot(
            x,
            y
        )
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

    fun close() {

        scope.cancel()
    }
}