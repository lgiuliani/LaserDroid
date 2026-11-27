package com.laserdroid.communication.network

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Minimal RFC 6455 server side: handshake and frame codec.
 * Enough for LaserGRBL "LaserWebESP8266" (websocket-sharp client).
 */
object WebSocketProtocol {

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    const val MAX_PAYLOAD = 1_048_576

    const val OPCODE_CONTINUATION = 0x0
    const val OPCODE_TEXT = 0x1
    const val OPCODE_BINARY = 0x2
    const val OPCODE_CLOSE = 0x8
    const val OPCODE_PING = 0x9
    const val OPCODE_PONG = 0xA

    class Frame(
        val fin: Boolean,
        val opcode: Int,
        val payload: ByteArray
    )

    /** Sec-WebSocket-Accept for a client key. */
    fun acceptKey(clientKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((clientKey.trim() + GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    /**
     * Reads the HTTP upgrade request and returns the
     * Sec-WebSocket-Key, or throws if it is not a WebSocket request.
     */
    fun readHandshake(input: InputStream): String {
        val headers = mutableMapOf<String, String>()
        var first = true
        while (true) {
            val line = readHttpLine(input)
            if (line.isEmpty()) break
            if (first) {
                if (!line.startsWith("GET ")) throw IOException("Not a WebSocket request")
                first = false
                continue
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        if (headers["upgrade"]?.lowercase() != "websocket") {
            throw IOException("Missing Upgrade: websocket")
        }
        return headers["sec-websocket-key"] ?: throw IOException("Missing Sec-WebSocket-Key")
    }

    fun handshakeResponse(clientKey: String): ByteArray {
        return (
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${acceptKey(clientKey)}\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
    }

    private fun readHttpLine(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) throw EOFException("Handshake interrupted")
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
            if (buffer.size() > 8_192) throw IOException("Header too long")
        }
        return buffer.toString(Charsets.US_ASCII.name())
    }

    /** Reads one frame (client frames are masked). */
    fun readFrame(input: InputStream): Frame {
        val first = readByte(input)
        val second = readByte(input)
        val fin = first and 0x80 != 0
        val opcode = first and 0x0F
        val masked = second and 0x80 != 0
        var length = (second and 0x7F).toLong()
        if (length == 126L) {
            length = ((readByte(input) shl 8) or readByte(input)).toLong()
        } else if (length == 127L) {
            length = 0L
            repeat(8) { length = (length shl 8) or readByte(input).toLong() }
        }
        // A 64-bit length with the high bit set is negative.
        if (length !in 0L..MAX_PAYLOAD) throw IOException("Invalid frame length: $length")

        val mask = if (masked) ByteArray(4) { readByte(input).toByte() } else null
        val payload = ByteArray(length.toInt())
        var offset = 0
        while (offset < payload.size) {
            val count = input.read(payload, offset, payload.size - offset)
            if (count < 0) throw EOFException("Frame interrupted")
            offset += count
        }
        if (mask != null) {
            for (index in payload.indices) {
                payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
            }
        }
        return Frame(fin, opcode, payload)
    }

    /** Server frame (unmasked). */
    fun encodeFrame(opcode: Int, payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(payload.size + 10)
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size <= 0xFFFF -> {
                output.write(126)
                output.write(payload.size shr 8)
                output.write(payload.size and 0xFF)
            }
            else -> {
                output.write(127)
                for (shift in 56 downTo 0 step 8) {
                    output.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
        }
        output.write(payload)
        return output.toByteArray()
    }

    /** Client frame (masked), used by tests. */
    fun encodeClientFrame(opcode: Int, payload: ByteArray, mask: ByteArray = byteArrayOf(1, 2, 3, 4)): ByteArray {
        val output = ByteArrayOutputStream(payload.size + 14)
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> output.write(0x80 or payload.size)
            else -> {
                output.write(0x80 or 126)
                output.write(payload.size shr 8)
                output.write(payload.size and 0xFF)
            }
        }
        output.write(mask)
        payload.forEachIndexed { index, value ->
            output.write(value.toInt() xor mask[index % 4].toInt())
        }
        return output.toByteArray()
    }

    /**
     * Text payload -> GRBL bytes. websocket-sharp sends strings as
     * UTF-8: realtime characters above 0x7F (e.g. 0x85 jog cancel)
     * arrive as two bytes and must be restored to one.
     */
    fun textToBytes(payload: ByteArray): ByteArray {
        val text = String(payload, Charsets.UTF_8)
        return ByteArray(text.length) { index ->
            val code = text[index].code
            (if (code <= 0xFF) code else '?'.code).toByte()
        }
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException("Connection closed")
        return value
    }
}
