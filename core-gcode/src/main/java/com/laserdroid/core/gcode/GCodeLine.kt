package com.laserdroid.core.gcode

/**
 * One line of a program: [originalContent] is kept unchanged,
 * [command] is the executable part without comments (or null).
 * Line numbers start at 1.
 */
data class GCodeLine(
    val originalLineNumber: Long,
    val originalContent: String,
    val command: String?,
    val type: GCodeLineType
) {
    val isExecutable: Boolean
        get() = type == GCodeLineType.COMMAND &&
                !command.isNullOrBlank()
}