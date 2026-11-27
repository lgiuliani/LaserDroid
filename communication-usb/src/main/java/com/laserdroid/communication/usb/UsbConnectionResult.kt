package com.laserdroid.communication.usb

/** Result of [UsbSerialConnection.open]. */
sealed interface UsbConnectionResult {

    data object PermissionRequired :
        UsbConnectionResult

    data object DriverNotFound :
        UsbConnectionResult

    data object PortNotFound :
        UsbConnectionResult

    data class Opened(
        val driverName: String,
        val portNumber: Int,
        val baudRate: Int
    ) : UsbConnectionResult

    data class OpenFailed(
        val message: String
    ) : UsbConnectionResult
}