package com.laserdroid.communication.network

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Socket
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

class LiveStreamServerTest {

    private class Frames : FrameProvider {
        @Volatile var number = 0L
        @Volatile var frame: ByteArray? = null
        override fun latestFrame() = frame
        override fun frameNumber() = number
    }

    @Test
    fun `request line and query are parsed`() {
        val request = LiveStreamServer.parseRequest(
            ByteArrayInputStream("GET /stream?key=12%2034&t=5 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        )!!
        assertEquals("GET", request.method)
        assertEquals("/stream", request.path)
        assertEquals("12 34", request.query["key"])
    }

    @Test
    fun `json strings are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\n\"", LiveStreamServer.jsonString("a\"b\\c\n"))
        assertEquals("null", LiveStreamServer.jsonString(null))
    }

    private fun get(port: Int, path: String, method: String = "GET", headers: String = ""): Pair<String, InputStream> {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        socket.getOutputStream().write("$method $path HTTP/1.1\r\nHost: x\r\n$headers\r\n".toByteArray())
        val input = socket.getInputStream()
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val value = input.read()
            if (value < 0) break
            header.append(value.toChar())
        }
        return header.toString() to input
    }

    @Test
    fun `key, status, snapshot and mjpeg stream`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val frames = Frames()
        val viewerCounts = mutableListOf<Int>()
        val server = LiveStreamServer(
            port = 0,
            frames = frames,
            statusJson = { "{\"machine\":\"IDLE\"}" },
            page = { LiveStreamPage.HTML },
            accessKey = { "1234" },
            scope = scope,
            maxViewers = 1,
            maxFps = { 20 },
            onViewersChanged = { synchronized(viewerCounts) { viewerCounts += it } }
        )
        server.start()
        val port = server.localPort
        try {
            assertTrue(get(port, "/status").first.startsWith("HTTP/1.1 401"))

            val (statusHeader, statusBody) = get(port, "/status?key=1234")
            assertTrue(statusHeader.startsWith("HTTP/1.1 200"))
            assertEquals("{\"machine\":\"IDLE\"}", String(statusBody.readBytes()))

            assertTrue(get(port, "/snapshot.jpg?key=1234").first.startsWith("HTTP/1.1 503"))
            frames.frame = byteArrayOf(1, 2, 3)
            frames.number = 1
            val (snapHeader, snapBody) = get(port, "/snapshot.jpg?key=1234")
            assertTrue(snapHeader.contains("image/jpeg"))
            assertEquals(listOf<Byte>(1, 2, 3), snapBody.readBytes().toList())

            val (streamHeader, stream) = get(port, "/stream?key=1234")
            assertTrue(streamHeader.contains("multipart/x-mixed-replace"))
            val part = StringBuilder()
            while (!part.endsWith("\r\n\r\n")) part.append(stream.read().toChar())
            assertTrue(part.contains("--laserdroidframe"))
            assertTrue(part.contains("Content-Length: 3"))

            // Single viewer allowed.
            assertTrue(get(port, "/stream?key=1234").first.startsWith("HTTP/1.1 503"))

            val (pageHeader, pageBody) = get(port, "/?key=1234")
            assertTrue(pageHeader.contains("text/html"))
            assertTrue(String(pageBody.readBytes()).contains("LaserDroid live"))
            withTimeout(2_000) { while (synchronized(viewerCounts) { viewerCounts.isEmpty() }) delay(10) }
            assertEquals(1, synchronized(viewerCounts) { viewerCounts.first() })
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `key entry page, cookie, controls, preview and lockout`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = mutableListOf<String>()
        var now = 0L
        val server = LiveStreamServer(
            port = 0,
            frames = Frames(),
            statusJson = { "{}" },
            page = { LiveStreamPage.HTML },
            preview = { "{\"id\":\"a\"}" },
            control = { action -> synchronized(actions) { actions += action }; if (action == "stop") "Nothing to stop" else null },
            clock = { now },
            accessKey = { "1234" },
            scope = scope
        )
        server.start()
        val port = server.localPort
        try {
            val (loginHeader, loginBody) = get(port, "/")
            assertTrue(loginHeader.startsWith("HTTP/1.1 401"))
            val login = String(loginBody.readBytes())
            assertTrue(login.contains("name=\"key\""))
            assertTrue(!login.contains("Wrong key"))
            assertTrue(String(get(port, "/?key=0000").second.readBytes()).contains("Wrong key"))

            val (pageHeader, _) = get(port, "/?key=1234")
            assertTrue(pageHeader.contains("Set-Cookie: ldkey=1234"))
            assertTrue(pageHeader.contains("SameSite=Strict"))

            val cookie = "Cookie: a=b; ldkey=1234\r\n"
            assertTrue(get(port, "/status", headers = cookie).first.startsWith("HTTP/1.1 200"))
            val (previewHeader, previewBody) = get(port, "/preview", headers = cookie)
            assertTrue(previewHeader.startsWith("HTTP/1.1 200"))
            assertEquals("{\"id\":\"a\"}", String(previewBody.readBytes()))

            val (pauseHeader, pauseBody) = get(port, "/control?action=pause", "POST", cookie + "Origin: http://x\r\n")
            assertTrue(pauseHeader.startsWith("HTTP/1.1 200"))
            assertEquals("{\"ok\":true}", String(pauseBody.readBytes()))
            val (stopHeader, stopBody) = get(port, "/control?action=stop", "POST", cookie)
            assertTrue(stopHeader.startsWith("HTTP/1.1 409"))
            assertTrue(String(stopBody.readBytes()).contains("Nothing to stop"))
            assertTrue(get(port, "/control?action=resume", "POST", cookie).first.startsWith("HTTP/1.1 400"))
            assertTrue(get(port, "/control?action=pause", "POST", cookie + "Origin: http://evil\r\n").first.startsWith("HTTP/1.1 403"))
            assertTrue(get(port, "/control?action=pause", "GET", cookie).first.startsWith("HTTP/1.1 404"))
            assertTrue(get(port, "/control?action=pause", "POST").first.startsWith("HTTP/1.1 401"))
            assertEquals(listOf("pause", "stop"), synchronized(actions) { actions.toList() })

            assertTrue(get(port, "/logout").first.contains("Max-Age=0"))

            // Brute force: locked after 10 wrong keys, even the right key.
            repeat(8) { get(port, "/status?key=9$it").first } // + 1 wrong key above
            assertTrue(get(port, "/status?key=1234").first.startsWith("HTTP/1.1 200"))
            assertTrue(get(port, "/status?key=0").first.startsWith("HTTP/1.1 401"))
            assertTrue(get(port, "/status?key=1234").first.startsWith("HTTP/1.1 429"))
            now += LiveStreamServer.LOCKOUT_MILLIS
            assertTrue(get(port, "/status?key=1234").first.startsWith("HTTP/1.1 200"))
        } finally {
            server.stop()
            scope.cancel()
        }
    }
}
