package com.laserdroid.core.gcode

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