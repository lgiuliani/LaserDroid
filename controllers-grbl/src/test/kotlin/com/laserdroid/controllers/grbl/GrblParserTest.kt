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

    @Test
    fun `setting line is parsed`() {
        assertEquals(GrblResponse.Setting(110, "6000.000"), parser.parse("\$110=6000.000"))
    }

    @Test
    fun `work position, offset and buffer fields are parsed`() {
        val response = parser.parse("<Run|WPos:1.5,-2.000,0.000|Bf:15,128|FS:1000,500|WCO:10.000,20.000,0.000>")

        assertIs<GrblResponse.Status>(response)
        assertEquals("Run", response.state)
        assertEquals(null, response.machineX)
        assertEquals(1.5, response.workX)
        assertEquals(-2.0, response.workY)
        assertEquals(10.0, response.offsetX)
        assertEquals(15, response.plannerBlocksFree)
        assertEquals(128, response.serialBytesFree)
        assertEquals(1000, response.feedRate)
        assertEquals(500, response.spindleSpeed)
    }

    @Test
    fun `bracketed messages and unknown lines are kept`() {
        assertEquals(GrblResponse.Message("[MSG:Caution: Unlocked]"), parser.parse("[MSG:Caution: Unlocked]"))
        assertEquals(GrblResponse.Unknown("Grbl 1.1h ['\$' for help]"), parser.parse("Grbl 1.1h ['\$' for help]"))
    }
}