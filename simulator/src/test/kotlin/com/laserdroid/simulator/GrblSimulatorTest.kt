package com.laserdroid.simulator

import kotlin.test.Test
import kotlin.test.assertEquals

class GrblSimulatorTest {

    private val simulator = GrblSimulator()

    @Test
    fun `G1 command returns ok`() {
        val response = simulator.processCommand("G1 X10 Y20")

        assertEquals("ok", response)
    }

    @Test
    fun `G0 command returns ok`() {
        val response = simulator.processCommand("G0 X10 Y20")

        assertEquals("ok", response)
    }

    @Test
    fun `status command returns idle state`() {
        val response = simulator.processCommand("?")

        assertEquals(
            "<Idle|MPos:0.000,0.000,0.000|FS:0,0>",
            response
        )
    }

    @Test
    fun `unknown command returns error`() {
        val response = simulator.processCommand("INVALID")

        assertEquals("error:1", response)
    }
}