package com.laserdroid.communication.network

import java.io.ByteArrayOutputStream

/**
 * Removes Telnet negotiation (IAC sequences) from a raw TCP stream.
 *
 * LaserGRBL sends raw bytes, but a real Telnet client (PuTTY,
 * Windows telnet) sends IAC WILL/DO... sequences that must never
 * reach GRBL. 0xFF is not used by GRBL: every other byte, extended
 * realtime commands (0x80..0xA4) included, passes unchanged.
 *
 * Stateful: a sequence may be split between two TCP packets.
 */
class TelnetFilter {

    private companion object {
        const val IAC = 0xFF
        const val SB = 0xFA
        const val SE = 0xF0
        const val WILL = 0xFB
        const val WONT = 0xFC
        const val DO = 0xFD
        const val DONT = 0xFE
    }

    private enum class State { DATA, COMMAND, OPTION, SUBNEGOTIATION, SUBNEGOTIATION_IAC }

    private var state = State.DATA

    fun filter(data: ByteArray, length: Int = data.size): ByteArray {
        val output = ByteArrayOutputStream(length)
        for (index in 0 until length) {
            val value = data[index].toInt() and 0xFF
            when (state) {
                State.DATA ->
                    if (value == IAC) state = State.COMMAND else output.write(value)

                State.COMMAND -> state = when (value) {
                    IAC -> {
                        // Escaped 0xFF.
                        output.write(IAC)
                        State.DATA
                    }
                    WILL, WONT, DO, DONT -> State.OPTION
                    SB -> State.SUBNEGOTIATION
                    else -> State.DATA
                }

                State.OPTION -> state = State.DATA

                State.SUBNEGOTIATION ->
                    if (value == IAC) state = State.SUBNEGOTIATION_IAC

                State.SUBNEGOTIATION_IAC ->
                    state = if (value == SE) State.DATA else State.SUBNEGOTIATION
            }
        }
        return output.toByteArray()
    }
}
