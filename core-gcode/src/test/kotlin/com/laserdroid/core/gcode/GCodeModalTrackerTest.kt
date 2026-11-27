package com.laserdroid.core.gcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GCodeModalTrackerTest {

    private fun stateAfter(text: String, line: Long) =
        GCodeModalTracker.stateAfter(text.lineSequence().toGCodeLines(), line)

    private val program = """
        ; header
        G21 G90
        M4 S0
        G0 X5 Y5
        G1 X25 Y5 S300 F1200
        G1 X25 Y25
        M5
        G0 X0 Y0
    """.trimIndent()

    @Test
    fun `state after a work line`() {
        val state = stateAfter(program, 6)
        assertEquals(25.0, state.x)
        assertEquals(25.0, state.y)
        assertEquals(1, state.motionMode)
        assertEquals(1200.0, state.feedRate)
        assertEquals(300.0, state.power)
        assertEquals(4, state.spindle)
    }

    @Test
    fun `state at the end`() {
        val state = stateAfter(program, 100)
        assertEquals(0.0, state.x)
        assertEquals(0, state.motionMode)
        assertEquals(5, state.spindle)
    }

    @Test
    fun `relative moves and inches`() {
        val state = stateAfter("G20 G91\nG1 X1 Y2 F10\nG1 X1", 3)
        assertEquals(50.8, state.x!!, 1e-9)
        assertEquals(50.8, state.y!!, 1e-9)
        assertTrue(state.inches)
        assertTrue(state.relative)
    }

    @Test
    fun `G92 marks the position as uncertain`() {
        val state = stateAfter("G0 X10 Y10\nG92 X0 Y0", 2)
        assertTrue(state.positionUncertain)
        assertEquals(10.0, state.x)
    }
}
