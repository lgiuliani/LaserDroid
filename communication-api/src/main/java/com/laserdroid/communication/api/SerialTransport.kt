package com.laserdroid.communication.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SerialTransport {

    val connectionState: StateFlow<ConnectionState>

    val incomingData: Flow<ByteArray>

    suspend fun connect()

    suspend fun disconnect()

    suspend fun write(data: ByteArray)
}