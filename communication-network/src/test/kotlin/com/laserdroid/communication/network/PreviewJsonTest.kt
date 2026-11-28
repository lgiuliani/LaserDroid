package com.laserdroid.communication.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewJsonTest {

    @Test
    fun `small previews are kept and encoded`() {
        val reduced = PreviewJson.reduce(
            2,
            floatArrayOf(0f, 0f, 10f, 0f, 10f, 0f, 10f, 5.5f),
            byteArrayOf(0, 2),
            intArrayOf(3, 4)
        )
        assertEquals(
            "{\"id\":\"x\",\"n\":2,\"b\":[0,0,10,5.5],\"s\":[0,0,10,0,10,0,10,5.5],\"t\":\"02\",\"l\":[3,4]}",
            PreviewJson.encode("x", reduced, 0.0, 0.0, 10.0, 5.5)
        )
    }

    @Test
    fun `long connected paths are merged under the limit`() {
        val count = 10_000
        val coordinates = FloatArray(count * 4)
        val types = ByteArray(count) { if (it < count / 2) 2 else 1 }
        val lines = IntArray(count) { it + 1 }
        for (index in 0 until count) {
            coordinates[index * 4] = index * 0.01f
            coordinates[index * 4 + 2] = (index + 1) * 0.01f
            coordinates[index * 4 + 1] = (index % 2) * 0.001f
            coordinates[index * 4 + 3] = ((index + 1) % 2) * 0.001f
        }
        val reduced = PreviewJson.reduce(count, coordinates, types, lines, maxSegments = 500)
        assertTrue(reduced.count in 2..500)
        // Types never mixed; last line preserved; path continuous.
        assertEquals(count, reduced.lineNumbers.last())
        assertEquals(2.toByte(), reduced.types.first())
        assertEquals(1.toByte(), reduced.types.last())
        for (index in 1 until reduced.count) {
            assertEquals(reduced.coordinates[index * 4 - 2], reduced.coordinates[index * 4])
            assertTrue(reduced.lineNumbers[index] > reduced.lineNumbers[index - 1])
        }
        assertTrue(kotlin.math.abs(count * 0.01f - reduced.coordinates[reduced.count * 4 - 2]) < 1e-3f)
    }
}
