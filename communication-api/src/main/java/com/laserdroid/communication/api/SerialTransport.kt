package com.laserdroid.communication.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Byte-oriented serial link, independent of the CNC protocol
 * (USB serial, simulator...).
 */
interface SerialTransport {

    /** Current link state. */
    val connectionState: StateFlow<ConnectionState>

    /** Raw chunks received from the device, in order. */
    val incomingData: Flow<ByteArray>

    /** Opens the link. Failures are published in [connectionState]. */
    suspend fun connect()

    /** Closes the link. */
    suspend fun disconnect()

    /**
     * Writes [data] as is.
     * Throws [IllegalStateException] when not connected.
     */
    suspend fun write(data: ByteArray)
}