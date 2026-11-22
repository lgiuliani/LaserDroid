package com.laserdroid.controllers.grbl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrblLineBufferTest {

    @Test
    fun `complete line is returned`() {

        val buffer = GrblLineBuffer()

        val lines = buffer.append("ok\r\n")

        assertEquals(
            listOf("ok"),
            lines
        )
    }

    @Test
    fun `incomplete line is retained`() {

        val buffer = GrblLineBuffer()

        val lines = buffer.append(
            "<Idle|MPos:10.000"
        )

        assertTrue(lines.isEmpty())

        assertEquals(
            "<Idle|MPos:10.000",
            buffer.pendingContent()
        )
    }

    @Test
    fun `fragmented status is reconstructed`() {

        val buffer = GrblLineBuffer()

        val firstResult = buffer.append(
            "<Idle|MPos:10.000"
        )

        val secondResult = buffer.append(
            ",20.000,0.000|FS:0,0>\r\n"
        )

        assertTrue(firstResult.isEmpty())

        assertEquals(
            listOf(
                "<Idle|MPos:10.000,20.000,0.000|FS:0,0>"
            ),
            secondResult
        )
    }

    @Test
    fun `multiple lines in one packet are returned`() {

        val buffer = GrblLineBuffer()

        val lines = buffer.append(
            "ok\r\nok\r\nerror:2\r\n"
        )

        assertEquals(
            listOf(
                "ok",
                "ok",
                "error:2"
            ),
            lines
        )
    }

    @Test
    fun `mixed line endings are supported`() {

        val buffer = GrblLineBuffer()

        val lines = buffer.append(
            "ok\nerror:1\rALARM:2\r\n"
        )

        assertEquals(
            listOf(
                "ok",
                "error:1",
                "ALARM:2"
            ),
            lines
        )
    }

    @Test
    fun `empty lines are ignored`() {

        val buffer = GrblLineBuffer()

        val lines = buffer.append(
            "\r\nok\r\n\r\n"
        )

        assertEquals(
            listOf("ok"),
            lines
        )
    }

    @Test
    fun `clear removes pending content`() {

        val buffer = GrblLineBuffer()

        buffer.append("partial response")

        buffer.clear()

        assertEquals(
            "",
            buffer.pendingContent()
        )
    }
}