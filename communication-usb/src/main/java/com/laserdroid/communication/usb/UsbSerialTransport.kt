package com.laserdroid.communication.usb

import android.hardware.usb.UsbDevice
import com.laserdroid.communication.api.ConnectionState
import com.laserdroid.communication.api.SerialTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class UsbSerialTransport(
    private val connection: UsbSerialConnection,
    private val device: UsbDevice
) : SerialTransport {

    private val _connectionState =
        MutableStateFlow<ConnectionState>(
            ConnectionState.Disconnected
        )

    override val connectionState:
            StateFlow<ConnectionState>
        get() = _connectionState

    private val _incomingData =
        MutableSharedFlow<ByteArray>(
            extraBufferCapacity = 64
        )

    override val incomingData:
            Flow<ByteArray>
        get() = _incomingData

    init {
        connection.onDataReceived = { data ->

            _incomingData.tryEmit(
                data.copyOf()
            )
        }

        connection.onConnectionError = {
                exception ->

            _connectionState.value =
                ConnectionState.Error(
                    exception
                )
        }
    }

    override suspend fun connect() {

        if (
            _connectionState.value ==
            ConnectionState.Connected
        ) {
            return
        }

        _connectionState.value =
            ConnectionState.Connecting

        when (
            val result =
                connection.open(device)
        ) {

            is UsbConnectionResult.Opened -> {

                _connectionState.value =
                    ConnectionState.Connected
            }

            UsbConnectionResult.PermissionRequired -> {

                _connectionState.value =
                    ConnectionState.Error(
                        SecurityException(
                            "USB permission is required"
                        )
                    )
            }

            UsbConnectionResult.DriverNotFound -> {

                _connectionState.value =
                    ConnectionState.Error(
                        IllegalStateException(
                            "No compatible USB serial driver"
                        )
                    )
            }

            UsbConnectionResult.PortNotFound -> {

                _connectionState.value =
                    ConnectionState.Error(
                        IllegalStateException(
                            "No USB serial port found"
                        )
                    )
            }

            is UsbConnectionResult.OpenFailed -> {

                _connectionState.value =
                    ConnectionState.Error(
                        IllegalStateException(
                            result.message
                        )
                    )
            }
        }
    }

    override suspend fun disconnect() {

        connection.close()

        _connectionState.value =
            ConnectionState.Disconnected
    }

    override suspend fun write(
        data: ByteArray
    ) {

        check(
            _connectionState.value ==
                    ConnectionState.Connected
        ) {
            "USB serial transport is not connected"
        }

        connection.write(data)
    }
}