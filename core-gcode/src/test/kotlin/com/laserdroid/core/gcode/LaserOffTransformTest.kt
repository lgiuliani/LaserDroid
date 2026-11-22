package com.laserdroid.core.gcode

import kotlin.test.Test
import kotlin.test.assertEquals

class LaserOffTransformTest {

    @Test
    fun `laser on commands become M5`() {
        assertEquals("M5 S0", LaserOffTransform.apply("M3 S1000"))
        assertEquals("M5 S0", LaserOffTransform.apply("M4 S1000"))
        assertEquals("M5S0", LaserOffTransform.apply("M03S1000"))
        assertEquals("M5", LaserOffTransform.apply("m4"))
    }

    @Test
    fun `power words become S0`() {
        assertEquals("G1 X10 Y5 S0 F1000", LaserOffTransform.apply("G1 X10 Y5 S255 F1000"))
        assertEquals("G1X1S0", LaserOffTransform.apply("G1X1S12.5"))
        assertEquals("G1X1S0", LaserOffTransform.apply("G1X1S.5"))
    }

    @Test
    fun `other commands are unchanged`() {
        assertEquals("M30", LaserOffTransform.apply("M30"))
        assertEquals("M5", LaserOffTransform.apply("M5"))
        assertEquals("G0 X3 Y4", LaserOffTransform.apply("G0 X3 Y4"))
        assertEquals("\$32=1", LaserOffTransform.apply("\$32=1"))
    }

    @Test
    fun `line keeps its original content`() {
        val line = GCodeNormalizer().normalize(7, "M3 S800 ; burn")
        val transformed = LaserOffTransform.apply(line)
        assertEquals("M5 S0", transformed.command)
        assertEquals("M3 S800 ; burn", transformed.originalContent)
        assertEquals(7L, transformed.originalLineNumber)
    }
}
