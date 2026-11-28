package com.laserdroid.communication.network

import java.util.Locale
import kotlin.math.hypot

/**
 * Encodes a job preview for the web page, reduced to at most
 * [maxSegments] segments.
 *
 * Consecutive, connected segments of the same type are merged while
 * the merged segment stays shorter than a tolerance; the tolerance
 * doubles until the result fits. A merged segment keeps the line
 * number of its last part, so "executed" coloring stays correct.
 *
 * JSON: {"id":..,"n":N,"b":[minX,minY,maxX,maxY],
 *        "s":[x1,y1,x2,y2,...],"t":"0122..","l":[line,...]}
 */
object PreviewJson {

    const val DEFAULT_MAX_SEGMENTS = 15_000

    /** Reduced segments: 4 floats, 1 type and 1 line per segment. */
    class Reduced(
        val coordinates: FloatArray,
        val types: ByteArray,
        val lineNumbers: IntArray
    ) {
        val count: Int get() = types.size
    }

    fun reduce(
        count: Int,
        coordinates: FloatArray,
        types: ByteArray,
        lineNumbers: IntArray,
        maxSegments: Int = DEFAULT_MAX_SEGMENTS
    ): Reduced {
        if (count <= maxSegments) {
            return Reduced(
                coordinates.copyOf(count * 4),
                types.copyOf(count),
                lineNumbers.copyOf(count)
            )
        }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (index in 0 until count * 4 step 2) {
            minX = minOf(minX, coordinates[index]); maxX = maxOf(maxX, coordinates[index])
            minY = minOf(minY, coordinates[index + 1]); maxY = maxOf(maxY, coordinates[index + 1])
        }
        var tolerance = (hypot((maxX - minX).toDouble(), (maxY - minY).toDouble()) / 2_000.0)
            .coerceAtLeast(1e-3)
        while (true) {
            val reduced = merge(count, coordinates, types, lineNumbers, tolerance, maxSegments)
            if (reduced != null) return reduced
            tolerance *= 2.0
        }
    }

    /** Returns null when the result exceeds [maxSegments]. */
    private fun merge(
        count: Int,
        coordinates: FloatArray,
        types: ByteArray,
        lineNumbers: IntArray,
        tolerance: Double,
        maxSegments: Int
    ): Reduced? {
        val outCoordinates = FloatArray(maxSegments * 4)
        val outTypes = ByteArray(maxSegments)
        val outLines = IntArray(maxSegments)
        var size = 0
        var index = 0
        while (index < count) {
            if (size == maxSegments) return null
            val type = types[index]
            val startX = coordinates[index * 4]
            val startY = coordinates[index * 4 + 1]
            var endX = coordinates[index * 4 + 2]
            var endY = coordinates[index * 4 + 3]
            var line = lineNumbers[index]
            index++
            while (index < count && types[index] == type) {
                val base = index * 4
                val connected = coordinates[base] == endX && coordinates[base + 1] == endY
                val nextX = coordinates[base + 2]
                val nextY = coordinates[base + 3]
                if (!connected || hypot((nextX - startX).toDouble(), (nextY - startY).toDouble()) > tolerance) {
                    break
                }
                endX = nextX
                endY = nextY
                line = lineNumbers[index]
                index++
            }
            val base = size * 4
            outCoordinates[base] = startX
            outCoordinates[base + 1] = startY
            outCoordinates[base + 2] = endX
            outCoordinates[base + 3] = endY
            outTypes[size] = type
            outLines[size] = line
            size++
        }
        return Reduced(outCoordinates.copyOf(size * 4), outTypes.copyOf(size), outLines.copyOf(size))
    }

    fun encode(
        id: String,
        reduced: Reduced,
        minX: Double?, minY: Double?, maxX: Double?, maxY: Double?
    ): String {
        val builder = StringBuilder(64 + reduced.count * 36)
        builder.append("{\"id\":").append(LiveStreamServer.jsonString(id))
        builder.append(",\"n\":").append(reduced.count)
        if (minX != null && minY != null && maxX != null && maxY != null) {
            builder.append(",\"b\":[").append(number(minX)).append(',').append(number(minY))
                .append(',').append(number(maxX)).append(',').append(number(maxY)).append(']')
        }
        builder.append(",\"s\":[")
        reduced.coordinates.forEachIndexed { index, value ->
            if (index > 0) builder.append(',')
            builder.append(number(value.toDouble()))
        }
        builder.append("],\"t\":\"")
        reduced.types.forEach { builder.append(('0'.code + it).toChar()) }
        builder.append("\",\"l\":[")
        reduced.lineNumbers.forEachIndexed { index, value ->
            if (index > 0) builder.append(',')
            builder.append(value)
        }
        return builder.append("]}").toString()
    }

    /** Two decimals, trailing zeros removed. */
    private fun number(value: Double): String {
        val text = String.format(Locale.US, "%.2f", value)
        return text.trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
    }
}
