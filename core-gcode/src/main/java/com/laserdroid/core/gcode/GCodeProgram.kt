package com.laserdroid.core.gcode

/**
 * In-memory program. Large files use a [GCodeSource] instead.
 */
data class GCodeProgram(
    val name: String,
    val lines: List<GCodeLine>
) {
    val originalLineCount: Int
        get() = lines.size

    val executableCommandCount: Int
        get() = lines.count { it.isExecutable }

    fun executableLines(): Sequence<GCodeLine> {
        return lines
            .asSequence()
            .filter { it.isExecutable }
    }
}