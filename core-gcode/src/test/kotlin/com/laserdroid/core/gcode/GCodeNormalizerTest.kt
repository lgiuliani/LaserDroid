package com.laserdroid.core.gcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GCodeNormalizerTest {

    private val normalizer =
        GCodeNormalizer()

    @Test
    fun `ordinary command is executable`() {

        val line = normalizer.normalize(
            lineNumber = 1,
            content = "G1 X10 Y20"
        )

        assertEquals(
            GCodeLineType.COMMAND,
            line.type
        )

        assertEquals(
            "G1 X10 Y20",
            line.command
        )

        assertTrue(line.isExecutable)
    }

    @Test
    fun `leading and trailing spaces are removed`() {

        val line = normalizer.normalize(
            lineNumber = 2,
            content = "   G1 X10 Y20   "
        )

        assertEquals(
            "G1 X10 Y20",
            line.command
        )
    }

    @Test
    fun `semicolon comment is ignored`() {

        val line = normalizer.normalize(
            lineNumber = 3,
            content = "; Move to origin"
        )

        assertEquals(
            GCodeLineType.COMMENT,
            line.type
        )

        assertNull(line.command)
        assertFalse(line.isExecutable)
    }

    @Test
    fun `parenthesis comment is ignored`() {

        val line = normalizer.normalize(
            lineNumber = 4,
            content = "(Move to origin)"
        )

        assertEquals(
            GCodeLineType.COMMENT,
            line.type
        )

        assertNull(line.command)
    }

    @Test
    fun `inline semicolon comment is removed`() {

        val line = normalizer.normalize(
            lineNumber = 5,
            content = "G1 X10 Y20 ; engraving move"
        )

        assertEquals(
            "G1 X10 Y20",
            line.command
        )
    }

    @Test
    fun `inline parenthesis comment is removed`() {

        val line = normalizer.normalize(
            lineNumber = 6,
            content = "G1 X10 (temporary comment) Y20"
        )

        assertEquals(
            "G1 X10  Y20",
            line.command
        )
    }

    @Test
    fun `empty line is not executable`() {

        val line = normalizer.normalize(
            lineNumber = 7,
            content = "   "
        )

        assertEquals(
            GCodeLineType.EMPTY,
            line.type
        )

        assertNull(line.command)
        assertFalse(line.isExecutable)
    }

    @Test
    fun `original content and line number are retained`() {

        val original =
            " G1 X10 ; original comment "

        val line = normalizer.normalize(
            lineNumber = 5327,
            content = original
        )

        assertEquals(
            5327,
            line.originalLineNumber
        )

        assertEquals(
            original,
            line.originalContent
        )

        assertEquals(
            "G1 X10",
            line.command
        )
    }
}