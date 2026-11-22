package com.laserdroid.communication.usb

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialProber

class UsbDeviceDetector(
    context: Context
) {

    private val usbManager =
        context.getSystemService(
            Context.USB_SERVICE
        ) as UsbManager

    fun detectDevices(): List<UsbDeviceInfo> {

        val drivers =
            UsbSerialProber
                .getDefaultProber()
                .findAllDrivers(usbManager)

        return usbManager.deviceList.values.map { device ->

            val driver =
                drivers.firstOrNull {
                    it.device.deviceId ==
                            device.deviceId
                }

            UsbDeviceInfo(
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                manufacturerName =
                    device.manufacturerName,
                productName =
                    device.productName,
                serialNumber =
                    if (
                        usbManager.hasPermission(device)
                    ) {
                        runCatching {
                            device.serialNumber
                        }.getOrNull()
                    } else {
                        null
                    },
                driverName =
                    driver?.javaClass?.simpleName
            )
        }
    }
}