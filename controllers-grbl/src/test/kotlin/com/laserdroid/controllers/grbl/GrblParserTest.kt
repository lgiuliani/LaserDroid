package com.laserdroid.controllers.grbl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GrblParserTest {

    private val parser = GrblParser()

    @Test
    fun `ok response is parsed`() {

        val response = parser.parse("ok")

        assertIs<GrblResponse.Ok>(response)
    }

    @Test
    fun `error response is parsed`() {

        val response = parser.parse("error:2")

        assertIs<GrblResponse.Error>(response)

        assertEquals(
            2,
            response.code
        )
    }

    @Test
    fun `alarm response is parsed`() {

        val response = parser.parse("ALARM:1")

        assertIs<GrblResponse.Alarm>(response)

        assertEquals(
            1,
            response.code
        )
    }

    @Test
    fun `status response is parsed`() {

        val response = parser.parse(
            "<Idle|MPos:10.000,20.000,0.000|FS:500,1000>"
        )

        assertIs<GrblResponse.Status>(response)

        assertEquals("Idle", response.state)

        assertEquals(10.0, response.machineX)
        assertEquals(20.0, response.machineY)
        assertEquals(0.0, response.machineZ)

        assertEquals(500, response.feedRate)
        assertEquals(1000, response.spindleSpeed)
    }

    @Test
    fun `incomplete status does not fail`() {

        val response = parser.parse(
            "<Idle|MPos:10.000,20.000>"
        )

        assertIs<GrblResponse.Status>(response)

        assertEquals(10.0, response.machineX)
        assertEquals(20.0, response.machineY)
        assertEquals(null, response.machineZ)
        assertEquals(null, response.feedRate)
    }
}