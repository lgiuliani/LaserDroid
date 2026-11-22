package com.laserdroid.controllers.grbl

import com.laserdroid.controllers.api.JogRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GrblJogCommandBuilderTest {

    private val builder =
        GrblJogCommandBuilder()

    @Test
    fun `positive X jog is generated`() {

        val command =
            builder.build(
                JogRequest(
                    x = 1.0,
                    feedRate = 100.0
                )
            )

        assertEquals(
            "\$J=G21 G91 X1 F100",
            command
        )
    }

    @Test
    fun `negative X jog is generated`() {

        val command =
            builder.build(
                JogRequest(
                    x = -1.0,
                    feedRate = 100.0
                )
            )

        assertEquals(
            "\$J=G21 G91 X-1 F100",
            command
        )
    }

    @Test
    fun `combined XY jog is generated`() {

        val command =
            builder.build(
                JogRequest(
                    x = 0.1,
                    y = -10.0,
                    feedRate = 500.0
                )
            )

        assertEquals(
            "\$J=G21 G91 X0.1 Y-10 F500",
            command
        )
    }

    @Test
    fun `decimal separator is always a dot`() {

        val command =
            builder.build(
                JogRequest(
                    x = 0.25,
                    feedRate = 123.5
                )
            )

        assertEquals(
            "\$J=G21 G91 X0.25 F123.5",
            command
        )
    }

    @Test
    fun `request without axis is rejected`() {

        assertFailsWith<
                IllegalArgumentException
                > {
            JogRequest(
                feedRate = 100.0
            )
        }
    }

    @Test
    fun `zero distance is rejected`() {

        assertFailsWith<
                IllegalArgumentException
                > {
            JogRequest(
                x = 0.0,
                feedRate = 100.0
            )
        }
    }

    @Test
    fun `negative feed rate is rejected`() {

        assertFailsWith<
                IllegalArgumentException
                > {
            JogRequest(
                x = 1.0,
                feedRate = -100.0
            )
        }
    }
}