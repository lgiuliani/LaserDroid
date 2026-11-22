package com.laserdroid.core.gcode

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GCodeAnalyzerTest {

    private val analyzer =
        GCodeAnalyzer(
            profile = MachineProfile(
                maxRateX = 6_000.0,
                maxRateY = 6_000.0,
                travelX = 410.0,
                travelY = 400.0
            )
        )

    private fun analyze(text: String): GCodeAnalysis =
        analyzer.analyze(TextGCodeSource("test.nc", text))

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "expected $expected but was $actual"
        )
    }

    private val square = """
        ; 20 mm square
        G21 G90
        M4 S0
        G0 X10 Y10
        G1 X30 Y10 S500 F1000
        G1 X30 Y30
        G1 X10 Y30
        G1 X10 Y10
        M5
        G0 X0 Y0
    """.trimIndent()

    @Test
    fun `square bounds distances and duration`() {
        val result = analyze(square)

        assertEquals(10L, result.totalLines)
        assertEquals(1L, result.commentLines)
        assertEquals(9, result.executableCommands)

        val work = assertNotNull(result.workBounds)
        assertClose(10.0, work.minX)
        assertClose(10.0, work.minY)
        assertClose(30.0, work.maxX)
        assertClose(30.0, work.maxY)
        assertClose(20.0, work.width)

        assertClose(80.0, result.workDistanceMm)
        assertTrue(result.usesLaser)
        assertEquals(500.0, result.maxPower)
        assertEquals(1000.0, result.maxFeedRate)

        // 80 mm at 1000 mm/min = 4.8 s, rapid 10*sqrt(2) mm at 6000 mm/min = 0.1 s
        assertEquals(4_900L, result.estimatedDurationMillis)

        assertEquals(10.0, result.startX)
        assertEquals(10.0, result.startY)
        assertEquals(0, result.errorCount)
        assertEquals(0, result.warningCount)
    }

    @Test
    fun `preview keeps segment types and line numbers`() {
        val result = analyze(square)
        val preview = result.preview

        // 4 work segments + final rapid
        assertEquals(5, preview.segmentCount)
        assertEquals(SegmentType.WORK, preview.types[0])
        assertEquals(5, preview.lineNumbers[0])
        assertEquals(SegmentType.RAPID, preview.types[4])
        assertEquals(30.0f, preview.x2(0))
        assertFalse(preview.truncated)
    }

    @Test
    fun `moves with laser off are travel`() {
        val result = analyze(
            """
            G90 G21
            G0 X0 Y0
            G1 X10 F600 S0
            M3 S100
            G1 X20
            """.trimIndent()
        )
        assertClose(10.0, result.travelDistanceMm)
        assertClose(10.0, result.workDistanceMm)
        val work = assertNotNull(result.workBounds)
        assertClose(10.0, work.minX)
        assertClose(20.0, work.maxX)
    }

    @Test
    fun `full circle with IJ`() {
        val result = analyze(
            """
            G90 G21 M3 S1000 F600
            G0 X10 Y0
            G2 X10 Y0 I-10 J0
            """.trimIndent()
        )
        assertEquals(1, result.arcCount)
        assertClose(2 * PI * 10.0, result.workDistanceMm, 1e-6)
        val bounds = assertNotNull(result.workBounds)
        assertClose(-10.0, bounds.minX, 1e-6)
        assertClose(10.0, bounds.maxX, 1e-6)
        assertClose(-10.0, bounds.minY, 1e-6)
        assertClose(10.0, bounds.maxY, 1e-6)
    }

    @Test
    fun `quarter arc with radius`() {
        val result = analyze(
            """
            G90 G21 M3 S1000 F600
            G0 X10 Y0
            G3 X0 Y10 R10
            """.trimIndent()
        )
        assertClose(PI * 10.0 / 2.0, result.workDistanceMm, 1e-6)
        val bounds = assertNotNull(result.workBounds)
        assertClose(10.0, bounds.maxY, 1e-6)
        assertClose(0.0, bounds.minX, 1e-6)
    }

    @Test
    fun `relative moves and inches`() {
        val result = analyze(
            """
            G20 G90
            G0 X0 Y0
            G91
            M3 S10
            G1 X1 F10
            G1 Y1
            """.trimIndent()
        )
        val bounds = assertNotNull(result.workBounds)
        assertClose(25.4, bounds.maxX)
        assertClose(25.4, bounds.maxY)
        assertClose(50.8, result.workDistanceMm)
    }

    @Test
    fun `compact syntax is parsed`() {
        val result = analyze("G0X0Y0\nM3S200\nG1X5Y0F300")
        assertClose(5.0, result.workDistanceMm)
        assertEquals(200.0, result.maxPower)
    }

    @Test
    fun `missing feed rate is an error`() {
        val result = analyze("G0 X0 Y0\nM3 S100\nG1 X10")
        assertEquals(1, result.errorCount)
        assertEquals(3L, result.warnings.first().lineNumber)
    }

    @Test
    fun `too long line is an error`() {
        val command = "G1X" + "1".repeat(80)
        val result = analyze(command)
        assertEquals(1, result.errorCount)
        assertEquals(83, result.longestLineLength)
    }

    @Test
    fun `unknown commands are reported not removed`() {
        val result = analyze("G0 X0 Y0\nM800\nG1 X1 F100")
        assertEquals(3, result.executableCommands)
        assertEquals(1, result.warningCount)
    }

    @Test
    fun `system commands are reported`() {
        val result = analyze("\$H\nG0 X0 Y0")
        assertEquals(1, result.warningCount)
    }

    @Test
    fun `travel limits are checked`() {
        val result = analyze("G0 X0 Y0\nM3 S10\nG1 X500 F1000")
        assertEquals(1, result.warningCount)
    }

    @Test
    fun `dwell is added to duration`() {
        val result = analyze("G4 P1.5")
        assertEquals(1_500L, result.estimatedDurationMillis)
    }

    @Test
    fun `empty program`() {
        val result = analyze("; nothing\n\n")
        assertNull(result.workBounds)
        assertNull(result.totalBounds)
        assertEquals(0, result.executableCommands)
        assertEquals(0L, result.estimatedDurationMillis)
    }

    @Test
    fun `preview is capped`() {
        val text = buildString {
            appendLine("G0 X0 Y0")
            appendLine("M3 S100 F1000")
            repeat(100) { index -> appendLine("G1 X${index + 1}") }
        }
        val capped = GCodeAnalyzer(maxPreviewSegments = 10)
            .analyze(TextGCodeSource("big.nc", text))
        assertEquals(10, capped.preview.segmentCount)
        assertTrue(capped.preview.truncated)
        assertClose(100.0, capped.workDistanceMm)
    }

    @Test
    fun `large program is analyzed lazily`() {
        val lines = generateSequence(0) { it + 1 }
            .take(200_000)
            .map { index -> "G1 X${index % 100} Y${index / 1000} F1000 S100" }
        val result = GCodeAnalyzer(maxPreviewSegments = 1_000)
            .analyze("big.nc", -1, lines.toGCodeLines())
        assertEquals(200_000, result.executableCommands)
        assertEquals(1_000, result.preview.segmentCount)
    }
}
