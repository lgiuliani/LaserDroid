package com.laserdroid.simulator

import com.laserdroid.communication.api.ConnectionState
import com.laserdroid.communication.api.SerialTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeSerialTransport : SerialTransport {

    var disconnectAtCommand: Int? = null

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