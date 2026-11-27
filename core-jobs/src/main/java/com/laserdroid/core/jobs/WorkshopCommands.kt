package com.laserdroid.core.jobs

import com.laserdroid.core.gcode.GCodeBounds
import com.laserdroid.core.gcode.GCodeModalState
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * G-code sequences for workshop functions and job recovery.
 * Pure functions: easy to test, no I/O.
 */
object WorkshopCommands {

    /** Current position becomes the work origin (G54, persistent). */
    const val SET_WORK_ZERO = "G10 L20 P1 X0 Y0"

    /**
     * Laser power value for [percent] of the maximum S ($30).
     */
    fun powerValue(percent: Double, maxPower: Double): Double {
        val safePercent = percent.coerceIn(0.0, 100.0)
        val safeMax = if (maxPower > 0.0) maxPower else 1_000.0
        return safeMax * safePercent / 100.0
    }

    /** Low power laser on, machine stopped (M3: constant power). */
    fun laserPointerOn(power: Double): String = "M3 S${format(power)}"

    const val LASER_OFF = "M5 S0"

    /**
     * Traces the rectangle [bounds] (work coordinates, mm).
     * With [power] > 0 the laser is on at constant low power (M3)
     * during the G1 moves; rapids are always laser off.
     */
    fun framing(
        bounds: GCodeBounds,
        feedRate: Double,
        power: Double
    ): List<String> {
        val minX = format(bounds.minX)
        val minY = format(bounds.minY)
        val maxX = format(bounds.maxX)
        val maxY = format(bounds.maxY)
        val commands = mutableListOf(
            "G21",
            "G90",
            LASER_OFF,
            "G0 X$minX Y$minY"
        )
        if (power > 0.0) {
            commands += laserPointerOn(power)
        }
        commands += "G1 X$maxX Y$minY F${format(feedRate)}"
        commands += "G1 X$maxX Y$maxY"
        commands += "G1 X$minX Y$maxY"
        commands += "G1 X$minX Y$minY"
        commands += LASER_OFF
        return commands
    }

    /** Length of the framing path, for the timeout. */
    fun framingLength(bounds: GCodeBounds): Double =
        2.0 * (bounds.width + bounds.height)

    /**
     * Declares the current head position as work position
     * ([workX], [workY]): used when GRBL lost its position
     * (hard reset after a USB disconnection).
     */
    fun resynchronize(workX: Double, workY: Double): List<String> {
        return listOf(
            "G21",
            "G90",
            "G10 L20 P1 X${format(workX)} Y${format(workY)}"
        )
    }

    /**
     * Restores the modal state before resuming a program:
     * laser off, move to the resume point, then units, distance
     * mode, feed, laser mode and motion mode of the program.
     */
    fun resumePreamble(
        state: GCodeModalState,
        laserOff: Boolean
    ): List<String> {
        val commands = mutableListOf(LASER_OFF, "G21", "G90")

        val x = state.x
        val y = state.y
        if (x != null && y != null) {
            commands += "G0 X${format(x)} Y${format(y)}"
        }
        if (state.inches) commands += "G20"
        if (state.relative) commands += "G91"
        if (state.feedRate > 0.0) commands += "F${format(state.feedRate)}"

        val power = if (laserOff) 0.0 else state.power
        when {
            !laserOff && (state.spindle == 3 || state.spindle == 4) ->
                commands += "M${state.spindle} S${format(power)}"
            else ->
                commands += "S${format(power)}"
        }
        if (state.motionMode == 0 || state.motionMode == 1) {
            commands += "G${state.motionMode}"
        }
        return commands
    }

    fun format(value: Double): String {
        return BigDecimal.valueOf(value)
            .setScale(3, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }
}
