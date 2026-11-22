package com.laserdroid.controllers.grbl

import com.laserdroid.controllers.api.JogRequest
import java.math.BigDecimal

class GrblJogCommandBuilder {

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

    private fun format(
        value: Double
    ): String {

        return BigDecimal
            .valueOf(value)
            .stripTrailingZeros()
            .toPlainString()
    }
}