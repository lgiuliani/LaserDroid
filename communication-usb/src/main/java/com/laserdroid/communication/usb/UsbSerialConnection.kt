package com.laserdroid.communication.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

class UsbSerialConnection(
    context: Context
) {
    private var ioManager:
            SerialInputOutputManager? = null

    var onDataReceived: ((ByteArray) -> Unit)? = null

    var onConnectionError: ((Exception) -> Unit)? = null

    private val usbManager =
        context.getSystemService(
            Context.USB_SERVICE
        ) as UsbManager

    private var deviceConnection:
            UsbDeviceConnection? = null

    private var serialPort:
            UsbSerialPort? = null

    fun findSupportedDevice(): UsbDevice? {

        val drivers =
            UsbSerialProber
                .getDefaultProber()
                .findAllDrivers(usbManager)

        return drivers
            .firstOrNull()
            ?.device
    }

    fun hasPermission(
        device: UsbDevice
    ): Boolean {
        return usbManager.hasPermission(device)
    }

    fun open(
        device: UsbDevice
    ): UsbConnectionResult {

        if (!usbManager.hasPermission(device)) {
            return UsbConnectionResult.PermissionRequired
        }

        val driver =
            UsbSerialProber
                .getDefaultProber()
                .probeDevice(device)
                ?: return UsbConnectionResult.DriverNotFound

        val port =
            driver.ports.firstOrNull()
                ?: return UsbConnectionResult.PortNotFound

        val connection =
            usbManager.openDevice(device)
                ?: return UsbConnectionResult.OpenFailed(
                    "Android could not open the USB device."
                )

        return try {

            port.open(connection)

            port.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            val manager =
                SerialInputOutputManager(
                    port,
                    object :
                        SerialInputOutputManager.Listener {

                        override fun onNewData(
                            data: ByteArray
                        ) {
                            onDataReceived?.invoke(data)
                        }

                        override fun onRunError(
                            exception: Exception
                        ) {
                            onConnectionError
                                ?.invoke(exception)
                        }
                    }
                )

            ioManager = manager

            manager.start()

            deviceConnection = connection
            serialPort = port

            UsbConnectionResult.Opened(
                driverName =
                    driver.javaClass.simpleName,
                portNumber = port.portNumber,
                baudRate = 115200
            )

        } catch (exception: Exception) {

            runCatching {
                port.close()
            }

            connection.close()

            UsbConnectionResult.OpenFailed(
                exception.message
                    ?: exception.javaClass.simpleName
            )
        }
    }

    fun close() {

        ioManager?.stop()
        ioManager = null

        runCatching {
            serialPort?.close()
        }

        deviceConnection?.close()

        serialPort = null
        deviceConnection = null
    }

    fun write(
        data: ByteArray
    ) {

        val port =
            serialPort
                ?: throw IllegalStateException(
                    "USB serial port is not open"
                )

        port.write(
            data,
            2_000
        )
    }
}
