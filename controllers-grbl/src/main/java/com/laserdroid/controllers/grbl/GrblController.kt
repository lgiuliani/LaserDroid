package com.laserdroid.controllers.grbl

import com.laserdroid.communication.api.ConnectionState
import com.laserdroid.communication.api.SerialTransport
import com.laserdroid.controllers.api.CncController
import com.laserdroid.controllers.api.CommandResult
import com.laserdroid.controllers.api.ControllerCapabilities
import com.laserdroid.controllers.api.ControllerEvent
import com.laserdroid.controllers.api.JogRequest
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * GRBL 1.1 driver over a [SerialTransport].
 *
 * - Line commands are sent one at a time (send/response): a command
 *   waits for its `ok` / `error:N` / `ALARM:N` before the next one.
 * - Realtime commands (`?`, `!`, `~`, Ctrl-X, jog cancel) bypass the
 *   line protocol and are written immediately.
 * - Status reports update [machineState], [machinePosition] and
 *   [workCoordinateOffset].
 * - A startup banner means GRBL restarted: the pending command is
 *   completed with [CommandResult.Reset].
 *
 * Call [close] when the controller is discarded.
 */
class GrblController(
    private val transport: SerialTransport
) : CncController {

    companion object {

        /**
         * Default acknowledgement timeout for interactive commands
         * (settings, jog, diagnostics).
         */
        const val DEFAULT_COMMAND_TIMEOUT_MILLIS = 2_000L

        private const val RESPONSE_POLL_MILLIS = 50L
    }

    /*
     * Monotonic timestamp of the last data received from GRBL.
     * Used by the inactivity timeout of executeCommand().
     */
    @Volatile
    private var lastReceiveMillis = 0L

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    private val _workCoordinateOffset =
        MutableStateFlow(MachinePosition.ZERO)

    override val workCoordinateOffset: StateFlow<MachinePosition>
        get() = _workCoordinateOffset

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

                        /*
                         * Publish the state before releasing the
                         * pending command, so that callers observe
                         * a consistent machine state.
                         */
                        _machineState.value =
                            MachineState.Disconnected

                        pendingCommand?.complete(
                            CommandResult.Disconnected
                        )
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

                        _machineState.value =
                            MachineState.Error(
                                state.cause.message
                                    ?: "Transport error"
                            )

                        pendingCommand?.complete(
                            CommandResult.Disconnected
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

        val connectionState =
            transport.connectionState.value

        if (
            connectionState !=
            ConnectionState.Connected
        ) {
            val message =
                (connectionState as? ConnectionState.Error)
                    ?.cause
                    ?.message
                    ?: "Serial transport is not connected"

            throw IllegalStateException(message)
        }

        _events.emit(
            ControllerEvent.Connected
        )

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
    ): CommandResult =
        executeCommand(
            command = command,
            inactivityTimeoutMillis =
                DEFAULT_COMMAND_TIMEOUT_MILLIS
        )

    override suspend fun executeCommand(
        command: String,
        inactivityTimeoutMillis: Long
    ): CommandResult = commandMutex.withLock {

        val response =
            CompletableDeferred<CommandResult>()

        pendingCommand = response

        try {
            transport.write(
                "$command\n".encodeToByteArray()
            )

            awaitResponse(
                response = response,
                sentAtMillis = nowMillis(),
                inactivityTimeoutMillis = inactivityTimeoutMillis
            )
        } catch (
            exception: CancellationException
        ) {
            /*
             * CancellationException is an IllegalStateException:
             * it must be rethrown, not reported as Disconnected.
             */
            throw exception
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

    /*
     * Waits for ok/error/ALARM. The timeout is restarted by any
     * incoming data, so a long move that keeps GRBL busy (planner
     * full) does not fail as long as status reports keep arriving.
     */
    private suspend fun awaitResponse(
        response: CompletableDeferred<CommandResult>,
        sentAtMillis: Long,
        inactivityTimeoutMillis: Long
    ): CommandResult {

        while (true) {

            val result =
                withTimeoutOrNull(RESPONSE_POLL_MILLIS.milliseconds) {
                    response.await()
                }

            if (result != null) {
                return result
            }

            val lastActivity =
                maxOf(sentAtMillis, lastReceiveMillis)

            if (
                nowMillis() - lastActivity >=
                inactivityTimeoutMillis
            ) {
                return CommandResult.Timeout
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

                lastReceiveMillis = nowMillis()

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

                /*
                 * error:N only rejects the line: GRBL keeps its
                 * state (Idle, Run...). The machine state is not
                 * changed, otherwise the machine would look
                 * disconnected until the next status report.
                 */
                pendingCommand?.complete(
                    CommandResult.Rejected(
                        code = response.code
                    )
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

                if (isStartupBanner(response.raw)) {
                    handleControllerReset(response.raw)
                    return
                }

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

    /*
     * "Grbl 1.1h ['$' for help]" is printed at every startup
     * (power-on, USB DTR reset, Ctrl-X).
     */
    private fun isStartupBanner(line: String): Boolean {
        return line.startsWith("Grbl ") && line.contains("help")
    }

    /*
     * GRBL flushed its serial and planner buffers: a command
     * waiting for "ok" will never be acknowledged.
     */
    private suspend fun handleControllerReset(banner: String) {

        pendingCommand?.complete(
            CommandResult.Reset
        )

        _events.emit(
            ControllerEvent.ResetDetected(banner)
        )

        runCatching {
            requestStatus()
        }
    }

    private fun handleStatus(
        status: GrblResponse.Status
    ) {

        /*
         * A report processed after the link was lost must not
         * hide the Disconnected / Error state.
         */
        if (
            transport.connectionState.value !=
            ConnectionState.Connected
        ) {
            return
        }

        /*
         * Compare-and-set: if the connection collector publishes
         * Disconnected concurrently, the update is retried and
         * then discarded.
         */
        _machineState.update { current ->
            if (
                transport.connectionState.value !=
                ConnectionState.Connected
            ) {
                current
            } else {
                mapMachineState(
                    status.state
                )
            }
        }

        if (
            status.offsetX != null &&
            status.offsetY != null
        ) {
            _workCoordinateOffset.value =
                MachinePosition(
                    x = status.offsetX,
                    y = status.offsetY,
                    z = status.offsetZ ?: 0.0
                )
        }

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
        } else if (
            status.workX != null &&
            status.workY != null
        ) {
            /*
             * $10 configured for WPos reports:
             * MPos = WPos + WCO.
             */
            val offset =
                _workCoordinateOffset.value

            _machinePosition.value =
                MachinePosition(
                    x = status.workX + offset.x,
                    y = status.workY + offset.y,
                    z = (status.workZ ?: 0.0) + offset.z
                )
        }
    }

    /** Sends `$$`: every setting is published as [ControllerEvent.SettingReceived]. */
    suspend fun requestSettings(): CommandResult {

        return executeCommand(
            GrblCommands.SETTINGS
        )
    }

    /** Sends `$I`: `[VER:...]` / `[OPT:...]` are published as messages. */
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

            /*
             * Safety door: motion is halted, treat as Hold.
             */
            "door" ->
                MachineState.Hold

            /*
             * Homing cycle: the machine is busy.
             */
            "home" ->
                MachineState.Running

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

        return executeCommand(
            GrblCommands.JOG_PREFIX +
                    "G21 G90 X0 Y0 F" +
                    GrblJogCommandBuilder.format(feedRate)
        )
    }

    /**
     * Stops the internal coroutines. The controller must not be
     * used afterwards; the transport is not closed.
     */
    override fun close() {
        pendingCommand?.complete(CommandResult.Disconnected)
        pendingCommand = null
        scope.cancel()
    }
}
