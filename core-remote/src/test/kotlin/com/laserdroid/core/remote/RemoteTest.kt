package com.laserdroid.core.remote

import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteTest {

    private class FakeInfo : MachineInfoProvider {
        override fun banner() = "Grbl 1.1h ['\$' for help]"
        override fun settingsLines() = listOf("\$30=1000", "\$32=1")
        override fun buildInfoLines() = listOf("[VER:1.1h.20190825:]", "[OPT:V,15,128]")
        override fun parserStateLine() = "[GC:G0 G54 G17 G21 G90 G94 M5 M9 T0 F0 S0]"
        override fun parameterLines() = listOf("[G54:0.000,0.000,0.000]")
        override fun statusReport(state: String) = "<$state|MPos:1.000,2.000,0.000|FS:0,0>"
    }

    private class FakeRelay : MachineRelay {
        val jogs: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var cancels = 0
        @Volatile var unlocks = 0
        override suspend fun jog(line: String): String { jogs += line; return "ok" }
        override suspend fun cancelJog() { cancels++ }
        override suspend fun unlock(): String { unlocks++; return "ok" }
        override fun liveState(): String? = null
    }

    private class FakeLink : RawMachineLink {
        val written: MutableList<Byte> = Collections.synchronizedList(mutableListOf())
        override val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        @Volatile var acquired = false
        @Volatile var releases = 0
        @Volatile var refusal: String? = null
        override fun acquire(): String? {
            refusal?.let { return it }
            acquired = true
            return null
        }
        override suspend fun write(data: ByteArray) { written.addAll(data.toList()) }
        override suspend fun release(clientLost: Boolean) { acquired = false; releases++ }
    }

    private fun temporaryDirectory(): File = Files.createTempDirectory("laserdroid").toFile()

    /* ---------------- VirtualGrblSession ---------------- */

    private class SessionFixture(
        relay: MachineRelay? = null,
        clock: () -> Long = System::currentTimeMillis,
        maxBytes: Long = 10_000_000L
    ) {
        val output = StringBuilder()
        val directory: File = Files.createTempDirectory("capture").toFile()
        val capture = ProgramCapture(directory, maxBytes, clock)
        val session = VirtualGrblSession(FakeInfo(), capture, relay, { output.append(it) })
        suspend fun send(text: String) = session.onBytes(text.toByteArray(Charsets.ISO_8859_1))
        fun take(): String = output.toString().also { output.setLength(0) }
    }

    @Test
    fun `answers like GRBL at connection`() = runBlocking<Unit> {
        val f = SessionFixture()
        f.send("\u0018")
        assertEquals("\r\nGrbl 1.1h ['\$' for help]\r\n", f.take())
        f.send("\$\$\n")
        assertEquals("\$30=1000\r\n\$32=1\r\nok\r\n", f.take())
        f.send("\$I\n")
        assertTrue(f.take().endsWith("[OPT:V,15,128]\r\nok\r\n"))
        f.send("?")
        assertEquals("<Idle|MPos:1.000,2.000,0.000|FS:0,0>\r\n", f.take())
    }

    @Test
    fun `every line is acknowledged and recorded`() = runBlocking<Unit> {
        val f = SessionFixture()
        f.send("G90 M4 S0\nG0 X1 Y1\nG1 X2 S500 F1000\n\nM5\n")
        assertEquals("ok\r\n".repeat(5), f.take())
        assertTrue(f.session.isReceiving)
        f.send("?")
        assertTrue(f.take().startsWith("<Run|"))

        val program = assertNotNull(f.capture.finish())
        assertEquals(4L, program.lines)
        assertEquals(2L, program.motionLines)
        assertEquals(listOf("G90 M4 S0", "G0 X1 Y1", "G1 X2 S500 F1000", "M5"), program.file.readLines())
    }

    @Test
    fun `split packets and CRLF behave like GRBL`() = runBlocking<Unit> {
        val f = SessionFixture()
        f.send("G0 X")
        assertEquals("", f.take())
        f.send("1\r\n")
        // GRBL answers both \r and \n.
        assertEquals("ok\r\nok\r\n", f.take())
    }

    @Test
    fun `reset discards a partial program`() = runBlocking<Unit> {
        val f = SessionFixture()
        f.send("G0 X1\nG1 X2 F100\n")
        f.send("\u0018")
        assertFalse(f.session.isReceiving)
        assertNull(f.capture.finish())
        assertEquals(0, f.directory.listFiles()!!.size)
    }

    @Test
    fun `too large program is refused until reset`() = runBlocking<Unit> {
        val f = SessionFixture(maxBytes = 20L)
        f.send("G0 X1 Y1\nG1 X2 F100\n")
        assertEquals("ok\r\nok\r\n", f.take())
        // 9 + 11 + 11 bytes > 20: refused, capture discarded.
        f.send("G1 X3 F100\n")
        assertEquals("error:11\r\n", f.take())
        assertFalse(f.session.isReceiving)
        // The end of the refused transfer never starts a new program.
        f.send("G1 X4\nG1 X5\n")
        assertEquals("error:11\r\nerror:11\r\n", f.take())
        assertFalse(f.session.isReceiving)
        assertEquals(0, f.directory.listFiles()!!.size)
        // Queries still answered.
        f.send("\$I\n")
        assertTrue(f.take().endsWith("ok\r\n"))
        // Ctrl-X: a new transfer is accepted.
        f.send("\u0018")
        f.take()
        f.send("G0 X1\n")
        assertEquals("ok\r\n", f.take())
        assertTrue(f.session.isReceiving)
    }

    @Test
    fun `settings writes and homing are refused`() = runBlocking<Unit> {
        val f = SessionFixture()
        f.send("\$32=0\n\$H\n")
        assertEquals("error:9\r\nerror:9\r\n", f.take())
        assertFalse(f.session.isReceiving)
    }

    @Test
    fun `jog and unlock are relayed when enabled`() = runBlocking<Unit> {
        val relay = FakeRelay()
        val f = SessionFixture(relay)
        f.send("\$J=G91 X10 F1000\n")
        f.send(String(charArrayOf(0x85.toChar())))
        f.send("\$X\n")
        assertEquals("ok\r\nok\r\n", f.take())
        assertEquals(listOf("\$J=G91 X10 F1000"), relay.jogs)
        assertEquals(1, relay.cancels)
        assertEquals(1, relay.unlocks)
        assertFalse(f.session.isReceiving)
    }

    @Test
    fun `jog is refused without relay`() = runBlocking<Unit> {
        val f = SessionFixture(relay = null)
        f.send("\$J=G91 X10 F1000\n")
        assertEquals("error:9\r\n", f.take())
    }

    @Test
    fun `console commands without motion do not create a program`() = runBlocking<Unit> {
        val f = SessionFixture()
        f.send("M5\nG90\n")
        assertNull(f.capture.finish())
    }

    /* ---------------- RemoteManager over TCP ---------------- */

    private class Client(port: Int) : AutoCloseable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        private val input: InputStream = socket.getInputStream()
        fun send(text: String) {
            socket.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
        }
        fun readLine(): String {
            val builder = StringBuilder()
            while (true) {
                val value = input.read()
                if (value < 0) return builder.toString()
                if (value == '\n'.code) return builder.toString().trimEnd('\r')
                builder.append(value.toChar())
            }
        }
        override fun close() = socket.close()
    }

    private class ManagerFixture(mode: RemoteMode) {
        val received: MutableList<CapturedProgram> = Collections.synchronizedList(mutableListOf())
        val link = FakeLink()
        val relay = FakeRelay()
        val manager = RemoteManager(
            info = FakeInfo(),
            relay = relay,
            link = link,
            captureDirectory = Files.createTempDirectory("remote").toFile(),
            onProgramReceived = { received += it }
        )
        val config = RemoteConfig(
            enabled = true,
            mode = mode,
            telnetPort = 0,
            webSocketEnabled = false,
            idleTimeoutMillis = 500L
        )
        val port: Int get() = manager.status.value.telnetPort!!
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(10) }
    }

    @Test
    fun `reception of 10000 lines over telnet`() = runBlocking<Unit> {
        val f = ManagerFixture(RemoteMode.RECEPTION)
        f.manager.apply(f.config)
        try {
            Client(f.port).use { client ->
                client.send("\u0018")
                client.readLine()
                assertEquals("Grbl 1.1h ['\$' for help]", client.readLine())
                waitUntil { f.manager.status.value.clientConnected }

                // LaserGRBL style: stream ahead, count "ok".
                val text = buildString {
                    append("G90 M4 S0\n")
                    repeat(10_000) { append("G1 X${it % 100} Y${it / 100} S300 F2000\n") }
                    append("M5\n")
                }
                client.send(text)
                repeat(10_002) { assertEquals("ok", client.readLine()) }

                client.send("?")
                assertTrue(client.readLine().startsWith("<Run|"))
                waitUntil { f.received.size == 1 }
                client.send("?")
                assertTrue(client.readLine().startsWith("<Idle|"))
            }
            val program = f.received.single()
            assertEquals(10_002L, program.lines)
            assertEquals(10_002, program.file.readLines().size)
            assertEquals(1, f.manager.status.value.programsReceived)
            waitUntil { !f.manager.status.value.clientConnected }
        } finally {
            f.manager.close()
        }
    }

    @Test
    fun `disconnection during transfer discards the program`() = runBlocking<Unit> {
        val f = ManagerFixture(RemoteMode.RECEPTION)
        f.manager.apply(f.config)
        try {
            Client(f.port).use { client ->
                client.send("G0 X1\nG1 X2 F100\n")
                client.readLine()
                client.readLine()
            }
            waitUntil { !f.manager.status.value.clientConnected }
            delay(800)
            assertTrue(f.received.isEmpty())
        } finally {
            f.manager.close()
        }
    }

    @Test
    fun `only one PC at a time`() = runBlocking<Unit> {
        val f = ManagerFixture(RemoteMode.RECEPTION)
        f.manager.apply(f.config)
        try {
            Client(f.port).use { first ->
                first.send("?")
                first.readLine()
                Client(f.port).use { second ->
                    assertTrue(second.readLine().startsWith("LaserDroid busy"))
                }
                first.send("?")
                assertTrue(first.readLine().startsWith("<Idle"))
            }
        } finally {
            f.manager.close()
        }
    }

    @Test
    fun `bridge forwards raw bytes both ways`() = runBlocking<Unit> {
        val f = ManagerFixture(RemoteMode.BRIDGE)
        f.manager.apply(f.config)
        try {
            Client(f.port).use { client ->
                waitUntil { f.link.acquired }
                client.send("G1 X1 F100\n?\u0018")
                waitUntil { f.link.written.size == 13 }
                assertEquals("G1 X1 F100\n?\u0018", String(f.link.written.toByteArray(), Charsets.ISO_8859_1))
                f.link.incoming.emit("ok\r\n".toByteArray())
                assertEquals("ok", client.readLine())
            }
            waitUntil { f.link.releases == 1 }
            assertFalse(f.link.acquired)
        } finally {
            f.manager.close()
        }
    }

    @Test
    fun `bridge refused while the machine is busy`() = runBlocking<Unit> {
        val f = ManagerFixture(RemoteMode.BRIDGE)
        f.link.refusal = "a job is running"
        f.manager.apply(f.config)
        try {
            Client(f.port).use { client ->
                assertEquals("LaserDroid busy: a job is running", client.readLine())
            }
            assertEquals(0, f.link.releases)
        } finally {
            f.manager.close()
        }
    }
}
