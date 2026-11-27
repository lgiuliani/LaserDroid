package com.laserdroid.core.remote

import com.laserdroid.communication.network.RemoteClientHandler
import com.laserdroid.communication.network.RemoteEndpoint
import com.laserdroid.communication.network.RemoteProtocol
import com.laserdroid.communication.network.RemoteServer
import com.laserdroid.communication.network.ServerState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * LaserGRBL over Wi-Fi: Telnet (and optionally WebSocket) servers,
 * one PC at a time, in RECEPTION or BRIDGE mode.
 *
 * Pure Kotlin: the Android service provides the machine accesses.
 */
class RemoteManager(
    private val info: MachineInfoProvider,
    private val relay: MachineRelay,
    private val link: RawMachineLink,
    private val captureDirectory: File,
    private val onProgramReceived: suspend (CapturedProgram) -> Unit,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(RemoteStatus())
    val status: StateFlow<RemoteStatus> = _status.asStateFlow()

    private val configMutex = Mutex()
    private var config = RemoteConfig()
    private var servers: List<RemoteServer> = emptyList()

    /* The single connected PC and its session. */
    private val sessionLock = Any()
    private var activeEndpoint: RemoteEndpoint? = null
    private var activeSession: ClientSession? = null

    /** Applies a configuration: (re)starts or stops the servers. */
    suspend fun apply(newConfig: RemoteConfig) = configMutex.withLock {
        val restart = newConfig.enabled != config.enabled ||
                newConfig.mode != config.mode ||
                newConfig.telnetPort != config.telnetPort ||
                newConfig.webSocketEnabled != config.webSocketEnabled ||
                newConfig.webSocketPort != config.webSocketPort ||
                newConfig.relayEnabled != config.relayEnabled
        config = newConfig
        if (!restart && servers.isNotEmpty() == newConfig.enabled) {
            return@withLock
        }
        stopServers()
        if (newConfig.enabled) {
            startServers(newConfig)
        }
    }

    /** Disconnects the current PC (the servers keep listening). */
    fun disconnectClient() {
        synchronized(sessionLock) { activeEndpoint }?.close()
    }

    fun close() {
        stopServers()
        scope.cancel()
    }

    private fun startServers(current: RemoteConfig) {
        val handler = Handler()
        val list = mutableListOf(
            RemoteServer(RemoteProtocol.TELNET, current.telnetPort, handler, scope)
        )
        if (current.webSocketEnabled) {
            list += RemoteServer(RemoteProtocol.WEBSOCKET, current.webSocketPort, handler, scope)
        }
        list.forEach { it.start() }
        servers = list

        val errors = list.mapNotNull { (it.state.value as? ServerState.Failed)?.message }
        _status.value = RemoteStatus(
            enabled = true,
            mode = current.mode,
            telnetPort = list.firstOrNull { it.protocol == RemoteProtocol.TELNET }
                ?.takeIf { it.state.value is ServerState.Listening }?.localPort,
            webSocketPort = list.firstOrNull { it.protocol == RemoteProtocol.WEBSOCKET }
                ?.takeIf { it.state.value is ServerState.Listening }?.localPort,
            error = errors.firstOrNull()
        )
        log(
            "GRBL server ${current.mode}: Telnet ${_status.value.telnetPort ?: "failed"}" +
                    if (current.webSocketEnabled) ", WebSocket ${_status.value.webSocketPort ?: "failed"}" else ""
        )
        errors.forEach { log("Server error: $it") }
    }

    private fun stopServers() {
        servers.forEach { it.stop() }
        servers = emptyList()
        disconnectClient()
        _status.value = RemoteStatus(enabled = false, mode = config.mode)
    }

    /*
     * ------------------------------------------------------------
     * Client handling
     * ------------------------------------------------------------
     */

    private inner class Handler : RemoteClientHandler {

        override suspend fun onConnected(endpoint: RemoteEndpoint): String? {
            val current = config
            synchronized(sessionLock) {
                val connected = activeEndpoint
                if (connected != null) {
                    log("PC ${endpoint.remoteAddress} refused: ${connected.remoteAddress} already connected")
                    return "LaserDroid busy: another PC is connected"
                }
                if (current.mode == RemoteMode.BRIDGE) {
                    val refusal = link.acquire()
                    if (refusal != null) {
                        log("PC ${endpoint.remoteAddress} refused: $refusal")
                        return "LaserDroid busy: $refusal"
                    }
                }
                activeEndpoint = endpoint
            }

            val session = when (current.mode) {
                RemoteMode.RECEPTION -> ReceptionSession(endpoint, current)
                RemoteMode.BRIDGE -> BridgeSession(endpoint)
            }
            synchronized(sessionLock) { activeSession = session }

            _status.update {
                it.copy(
                    clientAddress = endpoint.remoteAddress,
                    clientProtocol = endpoint.protocol.name,
                    linesReceived = 0L,
                    bytesReceived = 0L
                )
            }
            log("PC connected: ${endpoint.remoteAddress} (${endpoint.protocol}, ${current.mode})")
            session.start()
            return null
        }

        override suspend fun onData(endpoint: RemoteEndpoint, data: ByteArray) {
            val session = synchronized(sessionLock) {
                if (activeEndpoint === endpoint) activeSession else null
            } ?: return
            _status.update { it.copy(bytesReceived = it.bytesReceived + data.size) }
            session.onData(data)
        }

        override suspend fun onDisconnected(endpoint: RemoteEndpoint) {
            val session = synchronized(sessionLock) {
                if (activeEndpoint !== endpoint) return
                activeEndpoint = null
                activeSession.also { activeSession = null }
            }
            session?.stop()
            _status.update { it.copy(clientAddress = null, clientProtocol = null, receiving = false) }
            log("PC disconnected: ${endpoint.remoteAddress}")
        }
    }

    private interface ClientSession {
        fun start()
        suspend fun onData(data: ByteArray)
        suspend fun stop()
    }

    /*
     * Option B: virtual GRBL + program capture.
     */
    private inner class ReceptionSession(
        private val endpoint: RemoteEndpoint,
        private val current: RemoteConfig
    ) : ClientSession {

        private val mutex = Mutex()
        private val capture = ProgramCapture(captureDirectory, current.maxProgramBytes, clock)
        private val session = VirtualGrblSession(
            info = info,
            capture = capture,
            relay = if (current.relayEnabled) relay else null,
            send = { text -> endpoint.send(text.toByteArray(Charsets.ISO_8859_1)) },
            log = log
        )
        private var watchdog: Job? = null

        override fun start() {
            watchdog = scope.launch {
                while (isActive) {
                    delay(200L.milliseconds)
                    finishIfIdle()
                }
            }
        }

        override suspend fun onData(data: ByteArray) {
            mutex.withLock {
                session.onBytes(data)
                _status.update {
                    it.copy(receiving = capture.isCapturing, linesReceived = capture.lines)
                }
            }
        }

        private suspend fun finishIfIdle() {
            val program = mutex.withLock {
                if (!capture.isCapturing || capture.idleMillis() < current.idleTimeoutMillis) {
                    return
                }
                capture.finish()
            }
            _status.update { it.copy(receiving = false) }
            if (program == null) {
                log("Commands received without motion: no program created")
                return
            }
            _status.update { it.copy(programsReceived = it.programsReceived + 1) }
            log("Program received: ${program.lines} lines, ${program.bytes / 1024} KB")
            try {
                onProgramReceived(program)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log("Received program not loaded: ${exception.message}")
            }
        }

        override suspend fun stop() {
            watchdog?.cancel()
            mutex.withLock {
                if (capture.isCapturing) {
                    // Connection lost before the end: never keep a partial program.
                    log("Transfer interrupted: partial program discarded (${capture.lines} lines)")
                    capture.cancel()
                }
            }
        }
    }

    /*
     * Option A: transparent bridge, LaserGRBL drives the machine.
     */
    private inner class BridgeSession(
        private val endpoint: RemoteEndpoint
    ) : ClientSession {

        private var forward: Job? = null

        override fun start() {
            forward = scope.launch {
                link.incoming.collect { bytes ->
                    runCatching { endpoint.send(bytes) }
                }
            }
        }

        override suspend fun onData(data: ByteArray) {
            try {
                link.write(data)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log("Bridge write failed: ${exception.message}")
                endpoint.close()
            }
        }

        override suspend fun stop() {
            forward?.cancel()
            // The PC is gone (closed, crashed or Wi-Fi lost).
            link.release(clientLost = true)
        }
    }
}
