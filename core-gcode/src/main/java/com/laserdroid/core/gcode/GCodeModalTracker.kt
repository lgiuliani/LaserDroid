package com.laserdroid.core.gcode

import kotlin.math.roundToInt

/**
 * Modal state of a program after a given line, used to resume an
 * interrupted job.
 *
 * Coordinates are work coordinates in millimeters. [feedRate] and
 * [power] are the raw values written in the program (program units).
 */
data class GCodeModalState(
    val inches: Boolean = false,
    val relative: Boolean = false,
    /** Last motion mode: 0, 1, 2 or 3. */
    val motionMode: Int = 0,
    val feedRate: Double = 0.0,
    val power: Double = 0.0,
    /** 3 (M3), 4 (M4) or 5 (M5). */
    val spindle: Int = 5,
    val x: Double? = null,
    val y: Double? = null,
    /** G10/G28/G30/G92 seen: positions may be inaccurate. */
    val positionUncertain: Boolean = false
)

/**
 * Replays the modal G-code state without analysis or preview.
 */
object GCodeModalTracker {

    private const val INCH = 25.4

    /**
     * State after every executable line whose original line number
     * is <= [lastLine].
     */
    fun stateAfter(
        lines: Sequence<GCodeLine>,
        lastLine: Long
    ): GCodeModalState {
        var state = GCodeModalState()
        for (line in lines) {
            if (line.originalLineNumber > lastLine) break
            val command = line.command ?: continue
            if (!line.isExecutable || command.startsWith("$")) continue
            state = apply(state, command)
        }
        return state
    }

    fun apply(state: GCodeModalState, command: String): GCodeModalState {
        val words = parseWords(command) ?: return state

        var inches = state.inches
        var relative = state.relative
        var motion = state.motionMode
        var feed = state.feedRate
        var power = state.power
        var spindle = state.spindle
        var uncertain = state.positionUncertain
        var nonModalAxes = false
        var xWord: Double? = null
        var yWord: Double? = null

        for ((letter, value) in words) {
            when (letter) {
                'G' -> when ((value * 10.0).roundToInt()) {
                    0 -> motion = 0
                    10 -> motion = 1
                    20 -> motion = 2
                    30 -> motion = 3
                    200 -> inches = true
                    210 -> inches = false
                    900 -> relative = false
                    910 -> relative = true
                    100, 280, 300, 920 -> {
                        nonModalAxes = true
                        uncertain = true
                    }
                }
                'M' -> when (value.roundToInt()) {
                    3 -> spindle = 3
                    4 -> spindle = 4
                    5, 2, 30 -> spindle = 5
                }
                'F' -> feed = value
                'S' -> power = value
                'X' -> xWord = value
                'Y' -> yWord = value
            }
        }

        val unit = if (inches) INCH else 1.0
        var x = state.x
        var y = state.y

        if (!nonModalAxes) {
            xWord?.let { value ->
                x = if (relative) (x ?: 0.0) + value * unit else value * unit
            }
            yWord?.let { value ->
                y = if (relative) (y ?: 0.0) + value * unit else value * unit
            }
        }

        return GCodeModalState(
            inches = inches,
            relative = relative,
            motionMode = motion,
            feedRate = feed,
            power = power,
            spindle = spindle,
            x = x,
            y = y,
            positionUncertain = uncertain
        )
    }

    /*
     * "G1X10Y-2.5F1000" -> [(G,1), (X,10), (Y,-2.5), (F,1000)].
     * Null on a malformed line.
     */
    internal fun parseWords(command: String): List<Pair<Char, Double>>? {
        val words = ArrayList<Pair<Char, Double>>(8)
        var index = 0
        val length = command.length
        while (index < length) {
            val character = command[index]
            if (character.isWhitespace()) {
                index++
                continue
            }
            if (!character.isLetter()) return null
            index++
            while (index < length && command[index].isWhitespace()) index++
            val start = index
            if (index < length && (command[index] == '-' || command[index] == '+')) index++
            while (index < length && (command[index].isDigit() || command[index] == '.')) index++
            val value = command.substring(start, index).toDoubleOrNull() ?: return null
            words.add(character.uppercaseChar() to value)
        }
        return words
    }
}
