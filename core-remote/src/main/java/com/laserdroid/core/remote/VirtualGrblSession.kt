package com.laserdroid.core.remote

/**
 * RECEPTION mode: LaserDroid answers LaserGRBL like a GRBL 1.1
 * controller and records the program instead of executing it.
 *
 * - Every line gets "ok" immediately (empty lines included, exactly
 *   like GRBL, so that LaserGRBL character counting stays exact).
 * - G-code lines are recorded by [capture].
 * - $$, $I, $G, $# and '?' are answered from cached real values.
 * - Ctrl-X only answers the startup banner and discards a partial
 *   program: the real machine is never reset from here.
 * - $J=, 0x85 and $X are relayed when [relay] is set; any other
 *   $ command is refused (error:9): settings are not written.
 * - A program larger than the capture limit is refused (error:11)
 *   until the next Ctrl-X: the end of a refused transfer never
 *   becomes a new program.
 * - Nothing is ever started automatically.
 */
class VirtualGrblSession(
    private val info: MachineInfoProvider,
    private val capture: ProgramCapture,
    private val relay: MachineRelay?,
    private val send: suspend (String) -> Unit,
    private val log: (String) -> Unit = {}
) {

    private companion object {
        const val MAX_LINE = 1_024
        const val CTRL_X = 0x18
        const val JOG_CANCEL = 0x85
        const val REFUSED = "error:9"
        const val OVERFLOW = "error:11"
    }

    private val line = StringBuilder()

    /* Size limit reached: G-code lines are refused until Ctrl-X. */
    private var overflowed = false

    var commandsAnswered: Long = 0L
        private set

    val isReceiving: Boolean
        get() = capture.isCapturing

    suspend fun onBytes(data: ByteArray) {
        for (byte in data) {
            val value = byte.toInt() and 0xFF
            when {
                value == '?'.code -> send(info.statusReport(virtualState()) + "\r\n")
                value == '!'.code || value == '~'.code -> Unit
                value == CTRL_X -> reset()
                value == JOG_CANCEL -> relay?.cancelJog()
                value >= 0x80 -> Unit // overrides etc.: ignored.
                value == '\n'.code || value == '\r'.code -> {
                    val text = line.toString()
                    line.setLength(0)
                    handleLine(text)
                }
                else -> if (line.length < MAX_LINE) line.append(value.toChar())
            }
        }
    }

    /**
     * Run while a program is being received (LaserGRBL waits for Idle
     * before declaring its transfer finished).
     */
    private fun virtualState(): String {
        if (capture.isCapturing) return "Run"
        return relay?.liveState() ?: "Idle"
    }

    private suspend fun reset() {
        line.setLength(0)
        overflowed = false
        if (capture.isCapturing) {
            log("Reset from LaserGRBL: partial program discarded (${capture.lines} lines)")
            capture.cancel()
        }
        send("\r\n" + info.banner() + "\r\n")
    }

    private suspend fun handleLine(raw: String) {
        val text = raw.trim()
        val compact = text.replace(" ", "").uppercase()
        commandsAnswered++

        when {
            compact.isEmpty() -> ok()
            compact == "\$\$" -> respond(info.settingsLines())
            compact == "\$I" -> respond(info.buildInfoLines())
            compact == "\$G" -> respond(listOf(info.parserStateLine()))
            compact == "\$#" -> respond(info.parameterLines())
            compact.startsWith("\$J=") -> send((relay?.jog(text) ?: REFUSED) + "\r\n")
            compact == "\$X" -> send((relay?.unlock() ?: "ok") + "\r\n")
            compact.startsWith("$") -> {
                log("Refused from LaserGRBL: $text")
                send("$REFUSED\r\n")
            }
            overflowed -> send("$OVERFLOW\r\n")
            else -> {
                if (capture.append(text)) {
                    ok()
                } else {
                    overflowed = true
                    log("Program too large: transfer cancelled")
                    send("$OVERFLOW\r\n")
                }
            }
        }
    }

    private suspend fun respond(lines: List<String>) {
        val builder = StringBuilder()
        lines.forEach { builder.append(it).append("\r\n") }
        builder.append("ok\r\n")
        send(builder.toString())
    }

    private suspend fun ok() {
        send("ok\r\n")
    }
}
