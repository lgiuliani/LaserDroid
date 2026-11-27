package com.laserdroid.communication.network

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RemoteProtocol {
    /** Raw TCP stream (LaserGRBL "Telnet"). */
    TELNET,

    /** WebSocket text frames (LaserGRBL "LaserWebESP8266"). */
    WEBSOCKET
}

/**
 * One connected PC.
 */
interface RemoteEndpoint {
    val protocol: RemoteProtocol
    val remoteAddress: String

    /** Sends raw GRBL bytes to the PC. */
    suspend fun send(data: ByteArray)

    fun close()
}

/**
 * Receives the events of a [RemoteServer]. Calls for one client are
 * sequential: [onData] is not called again before it returns.
 */
interface RemoteClientHandler {
    /** Return a refusal message to reject the client, null to accept. */
    suspend fun onConnected(endpoint: RemoteEndpoint): String?
    suspend fun onData(endpoint: RemoteEndpoint, data: ByteArray)
    suspend fun onDisconnected(endpoint: RemoteEndpoint)
}

sealed interface ServerState {
    data object Stopped : ServerState
    data class Listening(val port: Int) : ServerState
    data class Failed(val port: Int, val message: String) : ServerState
}

/**
 * TCP server (Telnet or WebSocket) on the local network.
 * Blocking sockets on the I/O dispatcher; one coroutine per client.
 */
class RemoteServer(
    val protocol: RemoteProtocol,
    private val port: Int,
    private val handler: RemoteClientHandler,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * No byte from the PC during this time = connection lost
     * (Wi-Fi drop without TCP close). LaserGRBL polls '?' several
     * times per second while connected.
     */
    private val readTimeoutMillis: Int = 10_000
) {

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val clients = mutableSetOf<Socket>()

    /** Actual bound port (useful with port 0 in tests). */
    val localPort: Int
        get() = serverSocket?.localPort ?: port

    fun start() {
        if (serverSocket != null) return
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (exception: IOException) {
            _state.value = ServerState.Failed(port, exception.message ?: "Cannot open port $port")
            return
        }
        serverSocket = socket
        _state.value = ServerState.Listening(socket.localPort)

        acceptJob = scope.launch(ioDispatcher) {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (exception: IOException) {
                    break
                }
                launch { serve(client) }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        synchronized(clients) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        _state.value = ServerState.Stopped
    }

    private suspend fun serve(socket: Socket) {
        synchronized(clients) { clients += socket }
        val address = socket.inetAddress?.hostAddress ?: "?"
        var endpoint: SocketEndpoint? = null
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = readTimeoutMillis
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            if (protocol == RemoteProtocol.WEBSOCKET) {
                val key = WebSocketProtocol.readHandshake(input)
                output.write(WebSocketProtocol.handshakeResponse(key))
                output.flush()
            }

            val current = SocketEndpoint(protocol, address, socket, output, ioDispatcher)
            endpoint = current

            val refusal = handler.onConnected(current)
            if (refusal != null) {
                runCatching { current.send("$refusal\r\n".toByteArray()) }
                endpoint = null
                return
            }

            when (protocol) {
                RemoteProtocol.TELNET -> readTelnet(current, input)
                RemoteProtocol.WEBSOCKET -> readWebSocket(current, input)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // Connection lost: reported through onDisconnected.
        } finally {
            endpoint?.let { runCatching { handler.onDisconnected(it) } }
            runCatching { socket.close() }
            synchronized(clients) { clients -= socket }
        }
    }

    private suspend fun readTelnet(endpoint: SocketEndpoint, input: InputStream) {
        val filter = TelnetFilter()
        val buffer = ByteArray(4_096)
        while (true) {
            val count = withContext(ioDispatcher) { input.read(buffer) }
            if (count < 0) return
            if (count == 0) continue
            val data = filter.filter(buffer, count)
            if (data.isNotEmpty()) handler.onData(endpoint, data)
        }
    }

    private suspend fun readWebSocket(endpoint: SocketEndpoint, input: InputStream) {
        val message = java.io.ByteArrayOutputStream()
        var messageOpcode = WebSocketProtocol.OPCODE_TEXT
        while (true) {
            val frame = withContext(ioDispatcher) { WebSocketProtocol.readFrame(input) }
            when (frame.opcode) {
                WebSocketProtocol.OPCODE_CLOSE -> {
                    runCatching { endpoint.sendFrame(WebSocketProtocol.OPCODE_CLOSE, ByteArray(0)) }
                    return
                }
                WebSocketProtocol.OPCODE_PING ->
                    endpoint.sendFrame(WebSocketProtocol.OPCODE_PONG, frame.payload)
                WebSocketProtocol.OPCODE_PONG -> Unit
                else -> {
                    if (frame.opcode != WebSocketProtocol.OPCODE_CONTINUATION) {
                        messageOpcode = frame.opcode
                        message.reset()
                    }
                    message.write(frame.payload)
                    if (message.size() > WebSocketProtocol.MAX_PAYLOAD) {
                        throw IOException("Message too large")
                    }
                    if (frame.fin) {
                        val bytes = message.toByteArray()
                        message.reset()
                        val data = if (messageOpcode == WebSocketProtocol.OPCODE_TEXT) {
                            WebSocketProtocol.textToBytes(bytes)
                        } else {
                            bytes
                        }
                        if (data.isNotEmpty()) handler.onData(endpoint, data)
                    }
                }
            }
        }
    }

    private class SocketEndpoint(
        override val protocol: RemoteProtocol,
        override val remoteAddress: String,
        private val socket: Socket,
        private val output: OutputStream,
        private val ioDispatcher: CoroutineDispatcher
    ) : RemoteEndpoint {

        private val writeMutex = Mutex()

        override suspend fun send(data: ByteArray) {
            if (protocol == RemoteProtocol.WEBSOCKET) {
                sendFrame(WebSocketProtocol.OPCODE_TEXT, data)
            } else {
                write(data)
            }
        }

        suspend fun sendFrame(opcode: Int, payload: ByteArray) {
            write(WebSocketProtocol.encodeFrame(opcode, payload))
        }

        private suspend fun write(bytes: ByteArray) {
            writeMutex.withLock {
                withContext(ioDispatcher) {
                    output.write(bytes)
                    output.flush()
                }
            }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
