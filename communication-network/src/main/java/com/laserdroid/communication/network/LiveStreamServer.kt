package com.laserdroid.communication.network

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Source of camera frames (JPEG).
 */
interface FrameProvider {
    /** Latest JPEG frame, or null when none is available yet. */
    fun latestFrame(): ByteArray?

    /** Increments at every new frame. */
    fun frameNumber(): Long
}

/**
 * Minimal HTTP server for remote monitoring on the local network.
 *
 * - GET /            HTML page: video, job preview and status
 *                    (key entry page when the key is missing).
 * - GET /stream      MJPEG (multipart/x-mixed-replace).
 * - GET /snapshot.jpg
 * - GET /status      JSON (machine and job).
 * - GET /preview     JSON job preview (see [PreviewJson]).
 * - POST /control?action=pause|stop   Feed hold / stop only:
 *                    resuming or starting stays on the phone.
 * - GET /logout      Forgets the key cookie.
 *
 * The access key is given once (?key=... or the entry page), then kept
 * in a SameSite=Strict, HttpOnly cookie. Repeated wrong keys lock the
 * key check for [LOCKOUT_MILLIS]. POST requests from another origin
 * are refused. At most [maxViewers] simultaneous video streams.
 */
class LiveStreamServer(
    private val port: Int,
    private val frames: FrameProvider,
    private val statusJson: () -> String,
    private val page: () -> String,
    /** JSON of the current job preview, null when no job is loaded. */
    private val preview: () -> String? = { null },
    /** Executes "pause" or "stop"; returns null on success or an error message. */
    private val control: suspend (String) -> String? = { "Not supported" },
    private val loginPage: (wrongKey: Boolean) -> String = LiveStreamPage::login,
    private val clock: () -> Long = System::currentTimeMillis,
    private val accessKey: () -> String,
    private val scope: CoroutineScope,
    private val maxViewers: Int = 3,
    private val maxFps: () -> Int = { 10 },
    /** Called when the number of video viewers changes. */
    private val onViewersChanged: (Int) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        /** Header names in lower case. */
        val headers: Map<String, String> = emptyMap()
    ) {
        fun cookie(name: String): String? =
            headers["cookie"]
                ?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
    }

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val viewers = AtomicInteger(0)

    private val failureLock = Any()
    private val failureTimes = ArrayDeque<Long>()
    @Volatile
    private var lockedUntil = 0L

    val viewerCount: Int
        get() = viewers.get()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val clients = mutableSetOf<Socket>()

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
        try {
            socket.soTimeout = 10_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val request = withContext(ioDispatcher) { parseRequest(input) } ?: return
            // Long-lived MJPEG responses must not time out on reads.
            socket.soTimeout = 0
            handle(request, output)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // Client gone.
        } finally {
            runCatching { socket.close() }
            synchronized(clients) { clients -= socket }
        }
    }

    private enum class Access { GRANTED, MISSING, WRONG, LOCKED }

    private fun checkAccess(request: Request): Access {
        val key = accessKey()
        if (key.isEmpty()) return Access.GRANTED
        val now = clock()
        if (now < lockedUntil) return Access.LOCKED
        val given = request.query["key"] ?: request.cookie(COOKIE) ?: return Access.MISSING
        if (given == key) return Access.GRANTED
        synchronized(failureLock) {
            failureTimes.addLast(now)
            while (failureTimes.isNotEmpty() && now - failureTimes.first() > FAILURE_WINDOW_MILLIS) {
                failureTimes.removeFirst()
            }
            if (failureTimes.size >= MAX_FAILURES) {
                lockedUntil = now + LOCKOUT_MILLIS
                failureTimes.clear()
            }
        }
        return Access.WRONG
    }

    private suspend fun handle(request: Request, output: OutputStream) {
        if (request.path == "/logout") {
            respond(
                output, 303, "text/plain", ByteArray(0),
                listOf("Location: ./", "Set-Cookie: $COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict")
            )
            return
        }
        val isPage = request.path == "/" || request.path == "/index.html"
        when (val access = checkAccess(request)) {
            Access.GRANTED -> Unit
            Access.LOCKED -> {
                respond(output, 429, "text/plain", "Too many wrong keys, retry in a minute".toByteArray())
                return
            }
            else -> {
                if (isPage && request.method == "GET") {
                    respond(
                        output, 401, "text/html; charset=utf-8",
                        loginPage(access == Access.WRONG).toByteArray(Charsets.UTF_8),
                        if (access == Access.WRONG) listOf(clearCookie()) else emptyList()
                    )
                } else {
                    respond(output, 401, "text/plain", "Access key required".toByteArray())
                }
                return
            }
        }
        if (request.method == "POST") {
            if (request.path != "/control") {
                respond(output, 404, "text/plain", "Not found".toByteArray())
            } else if (!sameOrigin(request)) {
                respond(output, 403, "text/plain", "Forbidden origin".toByteArray())
            } else {
                handleControl(request, output)
            }
            return
        }
        if (request.method != "GET") {
            respond(output, 405, "text/plain", "Method not allowed".toByteArray())
            return
        }
        when (request.path) {
            "/", "/index.html" -> {
                val extra = request.query["key"]?.let {
                    listOf("Set-Cookie: $COOKIE=$it; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict")
                } ?: emptyList()
                respond(output, 200, "text/html; charset=utf-8", page().toByteArray(Charsets.UTF_8), extra)
            }
            "/status" ->
                respond(output, 200, "application/json; charset=utf-8", statusJson().toByteArray(Charsets.UTF_8))
            "/preview" -> {
                val json = preview()
                if (json == null) {
                    respond(output, 404, "text/plain", "No job".toByteArray())
                } else {
                    respond(output, 200, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
                }
            }
            "/snapshot.jpg" -> {
                val frame = frames.latestFrame()
                if (frame == null) {
                    respond(output, 503, "text/plain", "No frame yet".toByteArray())
                } else {
                    respond(output, 200, "image/jpeg", frame)
                }
            }
            "/stream" -> stream(output)
            else -> respond(output, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun clearCookie() = "Set-Cookie: $COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"

    /** Browsers send Origin on POST: it must match the Host header. */
    private fun sameOrigin(request: Request): Boolean {
        val origin = request.headers["origin"] ?: return true
        val host = request.headers["host"] ?: return false
        return origin.substringAfter("://") == host
    }

    private suspend fun handleControl(request: Request, output: OutputStream) {
        val action = request.query["action"]
        if (action != "pause" && action != "stop") {
            respond(output, 400, "application/json", "{\"ok\":false,\"error\":\"Unknown action\"}".toByteArray())
            return
        }
        val error = try {
            control(action)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            exception.message ?: "Failed"
        }
        val body = if (error == null) "{\"ok\":true}" else "{\"ok\":false,\"error\":${jsonString(error)}}"
        respond(output, if (error == null) 200 else 409, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))
    }

    private suspend fun stream(output: OutputStream) {
        if (viewers.incrementAndGet() > maxViewers) {
            viewers.decrementAndGet()
            respond(output, 503, "text/plain", "Too many viewers".toByteArray())
            return
        }
        onViewersChanged(viewers.get())
        try {
            write(
                output,
                ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                        "Cache-Control: no-cache, no-store\r\n" +
                        "Pragma: no-cache\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
            )
            var lastSent = -1L
            while (true) {
                val number = frames.frameNumber()
                val frame = frames.latestFrame()
                if (frame != null && number != lastSent) {
                    lastSent = number
                    write(
                        output,
                        ("--$BOUNDARY\r\nContent-Type: image/jpeg\r\n" +
                                "Content-Length: ${frame.size}\r\n\r\n").toByteArray(),
                        frame,
                        "\r\n".toByteArray()
                    )
                }
                delay(1_000L / maxFps().coerceIn(1, 30))
            }
        } finally {
            onViewersChanged(viewers.decrementAndGet())
        }
    }

    private suspend fun respond(
        output: OutputStream,
        code: Int,
        type: String,
        body: ByteArray,
        extraHeaders: List<String> = emptyList()
    ) {
        val reason = when (code) {
            200 -> "OK"
            303 -> "See Other"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            429 -> "Too Many Requests"
            else -> "Service Unavailable"
        }
        write(
            output,
            ("HTTP/1.1 $code $reason\r\n" +
                    "Content-Type: $type\r\n" +
                    extraHeaders.joinToString("") { "$it\r\n" } +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            body
        )
    }

    private suspend fun write(output: OutputStream, vararg parts: ByteArray) {
        withContext(ioDispatcher) {
            parts.forEach { output.write(it) }
            output.flush()
        }
    }

    companion object {
        private const val BOUNDARY = "laserdroidframe"
        private const val COOKIE = "ldkey"
        private const val MAX_FAILURES = 10
        private const val FAILURE_WINDOW_MILLIS = 60_000L
        const val LOCKOUT_MILLIS = 60_000L

        /** Parses "GET /path?a=1 HTTP/1.1" and the headers (a body is ignored). */
        fun parseRequest(input: InputStream): Request? {
            val requestLine = readLine(input) ?: return null
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                if (headers.size < 64 && header.contains(':')) {
                    headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
                }
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return null
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
                }
            return Request(parts[0].uppercase(), path, query, headers)
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

        private fun readLine(input: InputStream): String? {
            val builder = StringBuilder()
            while (true) {
                val value = input.read()
                if (value < 0) return if (builder.isEmpty()) null else builder.toString()
                if (value == '\n'.code) return builder.toString().trimEnd('\r')
                if (builder.length > 8_192) throw IOException("Line too long")
                builder.append(value.toChar())
            }
        }

        /** JSON string literal. */
        fun jsonString(value: String?): String {
            if (value == null) return "null"
            val builder = StringBuilder("\"")
            value.forEach { character ->
                when {
                    character == '"' -> builder.append("\\\"")
                    character == '\\' -> builder.append("\\\\")
                    character == '\n' -> builder.append("\\n")
                    character == '\r' -> builder.append("\\r")
                    character == '\t' -> builder.append("\\t")
                    character.code < 0x20 -> builder.append(String.format("\\u%04x", character.code))
                    else -> builder.append(character)
                }
            }
            return builder.append('"').toString()
        }
    }
}
