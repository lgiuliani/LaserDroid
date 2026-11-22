package com.laserdroid.core.gcode

import kotlin.test.Test
import kotlin.test.assertEquals

class GCodeProgramTest {

    private val normalizer =
        GCodeNormalizer()

    @Test
    fun `program counts only executable commands`() {

        val program = GCodeProgram(
            name = "test.gcode",
            lines = listOf(
                normalizer.normalize(
                    1,
                    "; Test program"
                ),
                normalizer.normalize(
                    2,
                    "G0 X0 Y0"
                ),
                normalizer.normalize(
                    3,
                    ""
                ),
                normalizer.normalize(
                    4,
                    "G1 X10 Y20"
                )
            )
        )

        assertEquals(
            4,
            program.originalLineCount
        )

        assertEquals(
            2,
            program.executableCommandCount
        )

        assertEquals(
            listOf(
                "G0 X0 Y0",
                "G1 X10 Y20"
            ),
            program
                .executableLines()
                .mapNotNull { it.command }
                .toList()
        )
    }
}