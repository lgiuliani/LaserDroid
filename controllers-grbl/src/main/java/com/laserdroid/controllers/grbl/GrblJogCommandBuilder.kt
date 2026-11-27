package com.laserdroid.controllers.grbl

import com.laserdroid.controllers.api.JogRequest
import java.math.BigDecimal

/**
 * Builds GRBL 1.1 relative jog commands (`$J=G21 G91 X.. Y.. Z.. F..`).
 */
class GrblJogCommandBuilder {

    companion object {

        /** Plain decimal notation, dot separator, no trailing zeros. */
        fun format(value: Double): String {
            return BigDecimal
                .valueOf(value)
                .stripTrailingZeros()
                .toPlainString()
        }
    }


    fun build(
        request: JogRequest
    ): String {

        val parts =
            mutableListOf(
                GrblCommands.JOG_PREFIX +
                        "G21",
                "G91"
            )

        request.x?.let { value ->
            parts.add(
                "X${format(value)}"
            )
        }

        request.y?.let { value ->
            parts.add(
                "Y${format(value)}"
            )
        }

        request.z?.let { value ->
            parts.add(
                "Z${format(value)}"
            )
        }

        parts.add(
            "F${format(request.feedRate)}"
        )

        return parts.joinToString(
            separator = " "
        )
    }
}