package com.laserdroid.simulator

import com.laserdroid.communication.api.ConnectionState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeSerialTransportTest {

    @Test
    fun `connect changes state to connected`() = runBlocking {

        val transport = FakeSerialTransport()

        transport.connect()

        assertEquals(
            ConnectionState.Connected,
            transport.connectionState.value
        )
    }

    @Test
    fun `G1 command receives ok`() = runBlocking {

        val transport = FakeSerialTransport()

        transport.connect()

        val responseDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            transport.incomingData.first()
        }

        transport.write(
            "G1 X10 Y20\n".encodeToByteArray()
        )

        val response = withTimeout(1_000) {
            responseDeferred.await()
        }
            .decodeToString()
            .trim()

        assertEquals("ok", response)
    }
}