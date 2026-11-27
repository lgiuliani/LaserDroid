package com.laserdroid.core.jobs

import com.laserdroid.core.gcode.GCodeBounds
import com.laserdroid.core.gcode.GCodeModalState
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkshopCommandsTest {

    @Test
    fun `framing with low power laser`() {
        val commands = WorkshopCommands.framing(
            GCodeBounds(5.0, 5.0, 25.5, 30.0),
            feedRate = 3_000.0,
            power = WorkshopCommands.powerValue(2.0, 1_000.0)
        )
        assertEquals(
            listOf(
                "G21", "G90", "M5 S0", "G0 X5 Y5", "M3 S20",
                "G1 X25.5 Y5 F3000", "G1 X25.5 Y30", "G1 X5 Y30", "G1 X5 Y5", "M5 S0"
            ),
            commands
        )
    }

    @Test
    fun `framing without laser`() {
        val commands = WorkshopCommands.framing(GCodeBounds(0.0, 0.0, 1.0, 1.0), 1_000.0, 0.0)
        assertEquals(0, commands.count { it.startsWith("M3") })
    }

    @Test
    fun `power percent of max`() {
        assertEquals(20.0, WorkshopCommands.powerValue(2.0, 1_000.0))
        assertEquals(20.0, WorkshopCommands.powerValue(2.0, 0.0))
        assertEquals(255.0, WorkshopCommands.powerValue(150.0, 255.0))
    }

    @Test
    fun `resume preamble restores the modal state`() {
        val state = GCodeModalState(
            motionMode = 1, feedRate = 1200.0, power = 500.0, spindle = 4, x = 26.0, y = 0.0
        )
        assertEquals(
            listOf("M5 S0", "G21", "G90", "G0 X26 Y0", "F1200", "M4 S500", "G1"),
            WorkshopCommands.resumePreamble(state, laserOff = false)
        )
        assertEquals(
            listOf("M5 S0", "G21", "G90", "G0 X26 Y0", "F1200", "S0", "G1"),
            WorkshopCommands.resumePreamble(state, laserOff = true)
        )
    }

    @Test
    fun `resynchronization uses G10 L20`() {
        assertEquals(
            listOf("G21", "G90", "G10 L20 P1 X12.346 Y-3"),
            WorkshopCommands.resynchronize(12.3456, -3.0)
        )
    }

    @Test
    fun `center of the bounds`() {
        val (x, y) = WorkshopCommands.center(GCodeBounds(5.0, 10.0, 25.0, 30.0))
        assertEquals(15.0, x)
        assertEquals(20.0, y)
    }
}
