package com.laserdroid.core.gcode

/**
 * Dry-run transform applied while streaming: the program moves
 * exactly as the original one but the laser never emits.
 *
 * - M3 / M4 become M5.
 * - Every S word becomes S0.
 *
 * The original file is never modified.
 */
object LaserOffTransform {

    private val laserOnRegex =
        Regex("(?i)M0*[34](?![0-9.])")

    private val powerRegex =
        Regex("(?i)S\\s*[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)")

    fun apply(command: String): String {
        if (command.startsWith("$")) {
            return command
        }
        return command
            .replace(laserOnRegex, "M5")
            .replace(powerRegex, "S0")
    }

    fun apply(line: GCodeLine): GCodeLine {
        val command = line.command ?: return line
        val transformed = apply(command)
        if (transformed == command) {
            return line
        }
        return line.copy(command = transformed)
    }
}
