package com.laserdroid.controllers.grbl

import com.laserdroid.communication.api.SerialTransport
import com.laserdroid.controllers.api.CncController
import com.laserdroid.controllers.api.ControllerCapabilities
import com.laserdroid.controllers.api.ControllerEvent
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.laserdroid.controllers.api.CommandResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import com.laserdroid.communication.api.ConnectionState
import com.laserdroid.controllers.api.JogRequest
import java.math.BigDecimal

class GrblController(
    private val transport: SerialTransport
) : CncController {

    private val commandMutex = Mutex()

    @Volatile
    private var pendingCommand:
            CompletableDeferred<CommandResult>? = null
    private val parser = GrblParser()

    private val lineBuffer = GrblLineBuffer()

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _machineState =
        MutableStateFlow<MachineState>(MachineState.Disconnected)

    override val machineState: StateFlow<MachineState>
        get() = _machineState

    private val _machinePosition =
        MutableStateFlow(MachinePosition.ZERO)

    override val machinePosition: StateFlow<MachinePosition>
        get() = _machinePosition

    private val _events =
        MutableSharedFlow<ControllerEvent>(
            extraBufferCapacity = 32
        )

    override val events: Flow<ControllerEvent>
        get() = _events

    override val capabilities =
        ControllerCapabilities(
            supportsJog = true,
            supportsHoming = true,
            supportsRealtimeCommands = true,
            supportsOverrides = true,
            supportsPositionReporting = true,
            supportsSettings = true
        )

    init {
        observeIncomingData()
        observeConnectionState()
    }

    private fun observeConnectionState() {

        scope.launch(
            start = CoroutineStart.UNDISPATCHED
        ) {

            transport.connectionState.collect { state ->

                when (state) {

                    ConnectionState.Disconnected -> {

                        pendingCommand?.complete(
                            CommandResult.Disconnected
                        )

                        _machineState.value =
                            MachineState.Disconnected
                    }

                    ConnectionState.Connecting -> {

                        _machineState.value =
                            MachineState.Connecting
                    }

                    ConnectionState.Connected -> {
                        // GRBL status will determine
                        // the actual machine state.
                    }

                    is ConnectionState.Error -> {

                        pendingCommand?.complete(
                            CommandResult.Disconnected
                        )

                        _machineState.value =
                            MachineState.Error(
                                state.cause.message
                                    ?: "Transport error"
                            )
                    }
                }
            }
        }
    }

    override suspend fun connect() {

        lineBuffer.clear()

        _machineState.value =
            MachineState.Connecting

        transport.connect()

        if (
            transport.connectionState.value ==
            ConnectionState.Connected
        ) {
            _events.emit(
                ControllerEvent.Connected
            )

            requestStatus()
        }

        /*
         * Synchronize LaserDroid with the actual
         * machine state immediately after opening
         * the serial connection.
         *
         * Do not assume that an open serial port
         * means that GRBL is IDLE.
         */
        requestStatus()
    }

    override suspend fun disconnect() {

        pendingCommand?.complete(
            CommandResult.Disconnected
        )

        pendingCommand = null

        transport.disconnect()

        lineBuffer.clear()

        _machineState.value =
            MachineState.Disconnected

        _events.emit(
            ControllerEvent.Disconnected
        )
    }

    override suspend fun executeCommand(
        command: String
    ): CommandResult = commandMutex.withLock {

        val response =
            CompletableDeferred<CommandResult>()

        pendingCommand = response

        try {
            transport.write(
                "$command\n".encodeToByteArray()
            )

            withTimeout(2_000.milliseconds) {
                response.await()
            }
        } catch (
            exception: TimeoutCancellationException
        ) {
            CommandResult.Timeout
        } catch (
            exception: IllegalStateException
        ) {
            CommandResult.Disconnected
        } finally {
            if (pendingCommand === response) {
                pendingCommand = null
            }
        }
    }

    override suspend fun requestStatus() {
        transport.write(
            GrblCommands.STATUS.encodeToByteArray()
        )
    }

    override suspend fun pause() {
        transport.write(
            GrblCommands.PAUSE.encodeToByteArray()
        )
    }

    override suspend fun resume() {
        transport.write(
            GrblCommands.RESUME.encodeToByteArray()
        )
    }

    override suspend fun stop() {
        transport.write(
            byteArrayOf(GrblCommands.SOFT_RESET)
        )
    }

    private fun observeIncomingData() {

        scope.launch(
            start = CoroutineStart.UNDISPATCHED
        ) {

            transport.incomingData.collect { data ->

                val completedLines =
                    lineBuffer.append(data)

                completedLines.forEach { line ->
                    handleResponse(
                        parser.parse(line)
                    )
                }
            }
        }
    }

    private suspend fun handleResponse(
        response: GrblResponse
    ) {

        when (response) {

            GrblResponse.Ok -> {
                pendingCommand?.complete(
                    CommandResult.Acknowledged
                )
            }

            is GrblResponse.Status -> {
                handleStatus(response)
            }

            is GrblResponse.Error -> {

                pendingCommand?.complete(
                    CommandResult.Rejected(
                        code = response.code
                    )
                )

                _machineState.value =
                    MachineState.Error(
                        "GRBL error ${response.code}"
                    )

                _events.emit(
                    ControllerEvent.ErrorReceived(
                        code = response.code,
                        message = "GRBL error ${response.code}"
                    )
                )
            }

            is GrblResponse.Alarm -> {

                pendingCommand?.complete(
                    CommandResult.Alarm(
                        code = response.code
                    )
                )

                _machineState.value =
                    MachineState.Alarm

                _events.emit(
                    ControllerEvent.AlarmReceived(
                        code = response.code,
                        message = "GRBL alarm ${response.code}"
                    )
                )
            }

            is GrblResponse.Message -> {

                _events.emit(
                    ControllerEvent.MessageReceived(
                        response.content
                    )
                )
            }

            is GrblResponse.Unknown -> {

                _events.emit(
                    ControllerEvent.MessageReceived(
                        response.raw
                    )
                )
            }
            is GrblResponse.Setting -> {

                _events.emit(
                    ControllerEvent.SettingReceived(
                        number = response.number,
                        value = response.value
                    )
                )
            }
        }
    }

    private fun handleStatus(
        status: GrblResponse.Status
    ) {

        _machineState.value =
            mapMachineState(
                status.state
            )

        if (
            status.machineX != null &&
            status.machineY != null &&
            status.machineZ != null
        ) {
            _machinePosition.value =
                MachinePosition(
                    x = status.machineX,
                    y = status.machineY,
                    z = status.machineZ
                )
        }
    }

    suspend fun requestSettings(): CommandResult {

        return executeCommand(
            GrblCommands.SETTINGS
        )
    }

    suspend fun requestBuildInfo(): CommandResult {

        return executeCommand(
            GrblCommands.BUILD_INFO
        )
    }

    private val jogCommandBuilder =
        GrblJogCommandBuilder()

    override suspend fun jog(
        request: JogRequest
    ): CommandResult {

        val currentState =
            machineState.value

        if (
            currentState != MachineState.Idle &&
            currentState != MachineState.Jog
        ) {
            return CommandResult.Rejected(
                code = null
            )
        }

        val command =
            jogCommandBuilder.build(request)

        return executeCommand(command)
    }

    override suspend fun cancelJog() {

        transport.write(
            byteArrayOf(
                GrblCommands.JOG_CANCEL
            )
        )
    }

    private fun mapMachineState(
        status: String
    ): MachineState {

        val baseState =
            status
                .substringBefore(":")
                .lowercase()

        return when (baseState) {

            "idle" ->
                MachineState.Idle

            "run" ->
                MachineState.Running

            "jog" ->
                MachineState.Jog

            "hold" ->
                MachineState.Hold

            "alarm" ->
                MachineState.Alarm

            else ->
                _machineState.value
        }
    }

    override suspend fun jogToZero(
        feedRate: Double
    ): CommandResult {

        require(
            feedRate.isFinite() &&
                    feedRate > 0.0
        ) {
            "Feed rate must be positive and finite"
        }

        val currentState =
            machineState.value

        if (
            currentState != MachineState.Idle &&
            currentState != MachineState.Jog
        ) {

            return CommandResult.Rejected(
                code = null
            )
        }

        val formattedFeedRate =
            java.math.BigDecimal
                .valueOf(feedRate)
                .stripTrailingZeros()
                .toPlainString()

        return executeCommand(
            "\$J=G21 G90 X0 Y0 F$formattedFeedRate"
        )
    }

}