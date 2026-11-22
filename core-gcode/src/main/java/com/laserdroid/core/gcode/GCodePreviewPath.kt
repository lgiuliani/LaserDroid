package com.laserdroid.core.gcode

/**
 * Kind of a preview segment.
 */
object SegmentType {
    /** G0 rapid move. */
    const val RAPID: Byte = 0

    /** G1/G2/G3 with the laser off or at zero power. */
    const val TRAVEL: Byte = 1

    /** G1/G2/G3 with the laser emitting. */
    const val WORK: Byte = 2
}

/**
 * Compact, allocation-friendly list of line segments for preview.
 *
 * Segment i goes from (x1[i], y1[i]) to (x2[i], y2[i]), stored in
 * [coordinates] as 4 consecutive floats. [lineNumbers] gives the
 * original G-code line of each segment so that the preview can
 * separate executed and remaining parts.
 */
class GCodePreviewPath internal constructor(
    val segmentCount: Int,
    val coordinates: FloatArray,
    val types: ByteArray,
    val lineNumbers: IntArray,
    /** True when [GCodeAnalyzer.maxPreviewSegments] was reached. */
    val truncated: Boolean
) {
    companion object {
        val EMPTY = GCodePreviewPath(
            0, FloatArray(0), ByteArray(0), IntArray(0), false
        )
    }

    fun x1(index: Int): Float = coordinates[index * 4]
    fun y1(index: Int): Float = coordinates[index * 4 + 1]
    fun x2(index: Int): Float = coordinates[index * 4 + 2]
    fun y2(index: Int): Float = coordinates[index * 4 + 3]
}

/**
 * Growable builder for [GCodePreviewPath].
 */
internal class PreviewPathBuilder(
    private val maxSegments: Int
) {
    private var coordinates = FloatArray(1024 * 4)
    private var types = ByteArray(1024)
    private var lineNumbers = IntArray(1024)
    private var count = 0
    private var truncated = false

    fun add(
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        type: Byte,
        lineNumber: Long
    ) {
        if (count >= maxSegments) {
            truncated = true
            return
        }
        ensureCapacity(count + 1)
        val base = count * 4
        coordinates[base] = x1.toFloat()
        coordinates[base + 1] = y1.toFloat()
        coordinates[base + 2] = x2.toFloat()
        coordinates[base + 3] = y2.toFloat()
        types[count] = type
        lineNumbers[count] =
            lineNumber.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        count++
    }

    private fun ensureCapacity(required: Int) {
        if (required <= types.size) {
            return
        }
        val newSize = minOf(
            maxOf(types.size * 2, required),
            maxSegments
        )
        coordinates = coordinates.copyOf(newSize * 4)
        types = types.copyOf(newSize)
        lineNumbers = lineNumbers.copyOf(newSize)
    }

    fun build(): GCodePreviewPath {
        return GCodePreviewPath(
            segmentCount = count,
            coordinates = coordinates.copyOf(count * 4),
            types = types.copyOf(count),
            lineNumbers = lineNumbers.copyOf(count),
            truncated = truncated
        )
    }
}
