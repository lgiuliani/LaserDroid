package com.laserdroid.communication.usb

data class UsbDeviceInfo(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val driverName: String?
)