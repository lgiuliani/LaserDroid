package com.laserdroid.communication.network

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Socket
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkTest {

    @Test
    fun `telnet negotiation is removed and realtime bytes pass`() {
        val filter = TelnetFilter()
        val input = byteArrayOf(
            0xFF.toByte(), 0xFD.toByte(), 0x01, // IAC DO ECHO
            '?'.code.toByte(), 0x85.toByte(), 0x18,
            0xFF.toByte(), 0xFA.toByte(), 0x18, 0x00, 0xFF.toByte(), 0xF0.toByte(), // SB ... SE
            'G'.code.toByte(), 0xFF.toByte()
        )
        val first = filter.filter(input)
        // IAC at the end of a packet: completed by the next one.
        val second = filter.filter(byteArrayOf(0xFF.toByte(), '\n'.code.toByte()))
        assertEquals(listOf(0x3F, 0x85, 0x18, 0x47), first.map { it.toInt() and 0xFF })
        assertEquals(listOf(0xFF, 0x0A), second.map { it.toInt() and 0xFF })
    }

    @Test
    fun `websocket accept key matches RFC 6455`() {
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            WebSocketProtocol.acceptKey("dGhlIHNhbXBsZSBub25jZQ==")
        )
    }

    @Test
    fun `websocket frames round trip`() {
        val payload = ByteArray(300) { it.toByte() }
        val frame = WebSocketProtocol.readFrame(
            ByteArrayInputStream(WebSocketProtocol.encodeClientFrame(WebSocketProtocol.OPCODE_BINARY, payload))
        )
        assertTrue(frame.fin)
        assertEquals(WebSocketProtocol.OPCODE_BINARY, frame.opcode)
        assertTrue(payload.contentEquals(frame.payload))
    }

    @Test
    fun `websocket text restores realtime characters`() {
        val text = "G1X1\n\u0085?".toByteArray(Charsets.UTF_8)
        val bytes = WebSocketProtocol.textToBytes(text)
        assertEquals(listOf(0x47, 0x31, 0x58, 0x31, 0x0A, 0x85, 0x3F), bytes.map { it.toInt() and 0xFF })
    }

    private class Recorder : RemoteClientHandler {
        val data: MutableList<Byte> = Collections.synchronizedList(mutableListOf())
        val connected: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var disconnected = 0
        @Volatile var accept = true
        @Volatile var endpoint: RemoteEndpoint? = null

        override suspend fun onConnected(endpoint: RemoteEndpoint): String? {
            if (!accept) return "busy"
            this.endpoint = endpoint
            connected += endpoint.remoteAddress
            endpoint.send("hello\r\n".toByteArray())
            return null
        }

        override suspend fun onData(endpoint: RemoteEndpoint, data: ByteArray) {
            this.data.addAll(data.toList())
            endpoint.send(data)
        }

        override suspend fun onDisconnected(endpoint: RemoteEndpoint) {
            disconnected++
        }
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) break
            offset += read
        }
        return buffer.copyOf(offset)
    }

    @Test
    fun `telnet server echoes raw bytes and reports disconnection`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = Recorder()
        val server = RemoteServer(RemoteProtocol.TELNET, 0, recorder, scope)
        server.start()
        try {
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 3_000
                val input = socket.getInputStream()
                assertEquals("hello\r\n", String(readExactly(input, 7)))
                val raw = byteArrayOf('?'.code.toByte(), 0x18, 0x85.toByte(), 0x0A)
                socket.getOutputStream().write(raw)
                assertTrue(raw.contentEquals(readExactly(input, 4)))
            }
            withTimeout(3_000) { while (recorder.disconnected == 0) delay(10) }
            assertEquals(1, recorder.connected.size)
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `websocket server handshake and text frames`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = Recorder()
        val server = RemoteServer(RemoteProtocol.WEBSOCKET, 0, recorder, scope)
        server.start()
        try {
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 3_000
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                output.write(
                    ("GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray()
                )
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) header.append(input.read().toChar())
                assertTrue(header.contains("101"))
                assertTrue(header.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))

                val hello = WebSocketProtocol.readFrame(input)
                assertEquals(WebSocketProtocol.OPCODE_TEXT, hello.opcode)
                assertEquals("hello\r\n", String(hello.payload))

                output.write(WebSocketProtocol.encodeClientFrame(WebSocketProtocol.OPCODE_TEXT, "\$\$\n".toByteArray()))
                val echo = WebSocketProtocol.readFrame(input)
                assertEquals("\$\$\n", String(echo.payload))

                output.write(WebSocketProtocol.encodeClientFrame(WebSocketProtocol.OPCODE_PING, "p".toByteArray()))
                assertEquals(WebSocketProtocol.OPCODE_PONG, WebSocketProtocol.readFrame(input).opcode)
            }
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `refused client receives the reason`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = Recorder().apply { accept = false }
        val server = RemoteServer(RemoteProtocol.TELNET, 0, recorder, scope)
        server.start()
        try {
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 3_000
                assertEquals("busy\r\n", String(readExactly(socket.getInputStream(), 6)))
                assertEquals(-1, socket.getInputStream().read())
            }
            assertEquals(0, recorder.disconnected)
        } finally {
            server.stop()
            scope.cancel()
        }
    }
}
