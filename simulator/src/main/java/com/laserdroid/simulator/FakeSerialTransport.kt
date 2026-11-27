package com.laserdroid.simulator

import com.laserdroid.communication.api.ConnectionState
import com.laserdroid.communication.api.SerialTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory [SerialTransport] backed by a [GrblSimulator].
 *
 * Simulates disconnection ([disconnectAtCommand]) and a silent
 * controller ([timeoutAtCommand]); realtime bytes are recorded in
 * [realtimeCommands].
 */
class FakeSerialTransport : SerialTransport {

    /** 1-based line command at which the link is lost. */
    var disconnectAtCommand: Int? = null

    /** 1-based line command that never gets an answer. */
    var timeoutAtCommand: Int? = null

    private var commandCount = 0

    val simulator = GrblSimulator()

    private val _connectionState =
        MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    override val connectionState: StateFlow<ConnectionState>
        get() = _connectionState

    private val _incomingData = MutableSharedFlow<ByteArray>()

    override val incomingData: Flow<ByteArray>
        get() = _incomingData

    override suspend fun connect() {
        _connectionState.value = ConnectionState.Connecting
        _connectionState.value = ConnectionState.Connected
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun write(data: ByteArray) {

        check(
            connectionState.value ==
                    ConnectionState.Connected
        ) {
            "Serial transport is not connected"
        }

        if (isRealtimeCommand(data)) {
            handleRealtimeCommand(data)
            return
        }

        val command = data
            .decodeToString()
            .trimEnd('\r', '\n')

        commandCount++

        if (
            disconnectAtCommand != null &&
            commandCount == disconnectAtCommand
        ) {
            _connectionState.value =
                ConnectionState.Disconnected

            return
        }

        if (
            timeoutAtCommand != null &&
            commandCount == timeoutAtCommand
        ) {
            simulator.processCommand(command)

            // Simulate a command received by GRBL
            // for which no response is returned.
            return
        }

        val response =
            simulator.processCommand(command)

        _incomingData.emit(
            "$response\n".encodeToByteArray()
        )
    }

    /**
     * When true, the realtime status query '?' is answered with
     * [statusReport]. Disable it when a test must fully control
     * the incoming status stream.
     */
    var respondToStatusQueries: Boolean = true

    /**
     * Status report returned to '?' when
     * [respondToStatusQueries] is enabled.
     */
    var statusReport: String =
        "<Idle|MPos:0.000,0.000,0.000|FS:0,0>"

    private val _realtimeCommands =
        mutableListOf<Byte>()

    /**
     * Realtime bytes received ('?', '!', '~', 0x18, 0x85...).
     * They are never counted as line commands.
     */
    val realtimeCommands: List<Byte>
        get() = synchronized(_realtimeCommands) {
            _realtimeCommands.toList()
        }

    private fun isRealtimeCommand(
        data: ByteArray
    ): Boolean {

        if (data.size != 1) {
            return false
        }

        val value = data[0].toInt() and 0xFF

        return value == '?'.code ||
                value == '!'.code ||
                value == '~'.code ||
                value == 0x18 ||
                value >= 0x80
    }

    private suspend fun handleRealtimeCommand(
        data: ByteArray
    ) {
        synchronized(_realtimeCommands) {
            _realtimeCommands.add(data[0])
        }

        if (
            data[0] == '?'.code.toByte() &&
            respondToStatusQueries
        ) {
            _incomingData.emit(
                "$statusReport\r\n".encodeToByteArray()
            )
        }
    }

    suspend fun emitIncomingData(data: ByteArray) {
        check(
            connectionState.value ==
                    ConnectionState.Connected
        ) {
            "Serial transport is not connected"
        }

        _incomingData.emit(data)
    }

    suspend fun emitIncomingText(text: String) {
        emitIncomingData(
            text.encodeToByteArray()
        )
    }
}