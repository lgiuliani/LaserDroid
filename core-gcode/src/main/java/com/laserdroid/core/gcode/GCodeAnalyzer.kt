package com.laserdroid.core.gcode

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Single-pass G-code analyzer.
 *
 * Tracks the modal state (units, distance mode, motion mode, feed,
 * laser state) and computes bounds, distances, a theoretical
 * duration, warnings and a compact preview path.
 *
 * Memory usage does not depend on the file size except for the
 * preview, which is capped by [maxPreviewSegments].
 *
 * Unknown commands are reported, never removed: the program
 * streamed to the machine is always the original one.
 */
class GCodeAnalyzer(
    private val profile: MachineProfile = MachineProfile(),
    private val maxPreviewSegments: Int = DEFAULT_MAX_PREVIEW_SEGMENTS,
    private val maxStoredWarnings: Int = DEFAULT_MAX_STORED_WARNINGS
) {

    companion object {
        const val DEFAULT_MAX_PREVIEW_SEGMENTS = 200_000
        const val DEFAULT_MAX_STORED_WARNINGS = 200

        /** GRBL 1.1 LINE_BUFFER_SIZE - 1. */
        const val GRBL_MAX_LINE_LENGTH = 79

        private const val INCH = 25.4
        private const val ARC_SEGMENT_LENGTH_MM = 0.5
        private const val MAX_ARC_SEGMENTS = 360

        private val SUPPORTED_G_CODES = setOf(
            0, 10, 20, 30, 40, 100, 170, 180, 190, 200, 210,
            280, 281, 300, 301, 382, 383, 384, 385, 400, 431,
            490, 530, 540, 550, 560, 570, 580, 590, 610, 800,
            900, 901, 910, 911, 920, 921, 930, 940
        )

        private val SUPPORTED_M_CODES = setOf(
            0, 1, 2, 3, 4, 5, 7, 8, 9, 30, 56
        )

        private const val SUPPORTED_LETTERS = "FGIJKLMNPRSTXYZ"
    }

    /**
     * Analyzes every line of [source]. Blocking: call from a
     * background dispatcher.
     */
    fun analyze(
        source: GCodeSource,
        normalizer: GCodeNormalizer = GCodeNormalizer()
    ): GCodeAnalysis {
        return source.useLines { lines ->
            analyze(
                name = source.name,
                sizeBytes = source.sizeBytes,
                lines = lines.toGCodeLines(normalizer)
            )
        }
    }

    fun analyze(
        name: String,
        sizeBytes: Long,
        lines: Sequence<GCodeLine>
    ): GCodeAnalysis {
        val state = AnalysisState(profile, maxPreviewSegments, maxStoredWarnings)
        lines.forEach { line -> state.process(line) }
        return state.build(name, sizeBytes)
    }

    /*
     * ------------------------------------------------------------
     * Internal state machine
     * ------------------------------------------------------------
     */

    private class AnalysisState(
        private val profile: MachineProfile,
        maxPreviewSegments: Int,
        private val maxStoredWarnings: Int
    ) {
        // Counters
        var totalLines = 0L
        var executable = 0
        var comments = 0L
        var empty = 0L
        var arcCount = 0
        var longestLine = 0

        // Modal state
        var inches = false
        var relative = false
        var arcCenterAbsolute = false
        var planeXY = true
        var motionMode = 0          // 0, 1, 2, 3 (G code number)
        var feedRate = 0.0          // mm/min
        var power = 0.0
        var laserOn = false

        // Position
        var x = 0.0
        var y = 0.0
        var positionKnown = false
        var startX: Double? = null
        var startY: Double? = null

        // Results
        val workBounds = BoundsBuilder()
        val totalBounds = BoundsBuilder()
        var workDistance = 0.0
        var travelDistance = 0.0
        var rapidDistance = 0.0
        var durationMillis = 0.0
        var maxFeed = 0.0
        var maxPower = 0.0
        var usesLaser = false

        val warnings = ArrayList<GCodeWarning>()
        var warningCount = 0
        var errorCount = 0

        val preview = PreviewPathBuilder(maxPreviewSegments)

        private var warnedArcPlane = false
        private var warnedInverseTime = false

        fun process(line: GCodeLine) {
            totalLines++
            when (line.type) {
                GCodeLineType.EMPTY -> {
                    empty++
                    return
                }
                GCodeLineType.COMMENT -> {
                    comments++
                    return
                }
                GCodeLineType.COMMAND -> Unit
            }
            val command = line.command ?: return
            executable++

            val strippedLength = command.count { !it.isWhitespace() }
            if (strippedLength > longestLine) {
                longestLine = strippedLength
            }
            if (strippedLength > GRBL_MAX_LINE_LENGTH) {
                warn(
                    line.originalLineNumber,
                    GCodeWarningSeverity.ERROR,
                    "Line longer than $GRBL_MAX_LINE_LENGTH characters"
                )
            }

            if (command.startsWith("$")) {
                processSystemCommand(line.originalLineNumber, command)
                return
            }

            val words = parseWords(line.originalLineNumber, command)
                ?: return
            processWords(line.originalLineNumber, words)
        }

        private fun processSystemCommand(lineNumber: Long, command: String) {
            val upper = command.uppercase()
            val severity =
                if (upper.startsWith($$"$H") || upper.startsWith($$"$J") ||
                    upper.startsWith($$"$X") || upper.startsWith($$"$C")
                ) {
                    GCodeWarningSeverity.WARNING
                } else {
                    GCodeWarningSeverity.INFO
                }
            warn(lineNumber, severity, "System command in program: $command")
        }

        /*
         * Splits "G1X10Y-2.5F1000" into letter/value pairs.
         * Returns null (with an error warning) on a malformed line.
         */
        private fun parseWords(lineNumber: Long, command: String): List<Word>? {
            val words = ArrayList<Word>(8)
            var index = 0
            val length = command.length
            while (index < length) {
                val character = command[index]
                if (character.isWhitespace()) {
                    index++
                    continue
                }
                if (!character.isLetter()) {
                    warn(
                        lineNumber,
                        GCodeWarningSeverity.WARNING,
                        "Unexpected character '$character'"
                    )
                    return null
                }
                val letter = character.uppercaseChar()
                index++
                while (index < length && command[index].isWhitespace()) {
                    index++
                }
                val numberStart = index
                if (index < length && (command[index] == '-' || command[index] == '+')) {
                    index++
                }
                while (index < length && (command[index].isDigit() || command[index] == '.')) {
                    index++
                }
                val value = command
                    .substring(numberStart, index)
                    .toDoubleOrNull()
                if (value == null) {
                    warn(
                        lineNumber,
                        GCodeWarningSeverity.WARNING,
                        "Word '$letter' without a valid number"
                    )
                    return null
                }
                if (SUPPORTED_LETTERS.indexOf(letter) < 0) {
                    warn(
                        lineNumber,
                        GCodeWarningSeverity.WARNING,
                        "Unsupported word '$letter' (kept, GRBL may reject it)"
                    )
                }
                words.add(Word(letter, value))
            }
            return words
        }

        private fun processWords(lineNumber: Long, words: List<Word>) {
            var lineMotion: Int? = null
            var dwell = false
            var nonModalWithAxes = false
            var xWord: Double? = null
            var yWord: Double? = null
            var iWord: Double? = null
            var jWord: Double? = null
            var rWord: Double? = null
            var pWord: Double? = null
            var lineFeed: Double? = null
            var linePower: Double? = null

            // First pass: modal changes that affect values (units, mode).
            for ((letter, value) in words) {
                when (letter) {
                    'G' -> {
                        val code = (value * 10.0).roundToInt()
                        if (code !in SUPPORTED_G_CODES) {
                            warn(
                                lineNumber,
                                GCodeWarningSeverity.WARNING,
                                "Unsupported G${formatCode(value)} (kept, GRBL may reject it)"
                            )
                        }
                        when (code) {
                            0 -> lineMotion = 0
                            10 -> lineMotion = 1
                            20 -> lineMotion = 2
                            30 -> lineMotion = 3
                            40 -> dwell = true
                            100, 280, 300, 920 -> {
                                nonModalWithAxes = true
                                if (code == 280 || code == 300) {
                                    // Goes to a stored position, axes or not.
                                    positionKnown = false
                                }
                                warn(
                                    lineNumber,
                                    GCodeWarningSeverity.WARNING,
                                    "G${formatCode(value)} changes positions/offsets: preview may be inaccurate"
                                )
                            }
                            530 -> warn(
                                lineNumber,
                                GCodeWarningSeverity.WARNING,
                                "G53 machine coordinates: preview may be inaccurate"
                            )
                            170 -> planeXY = true
                            180, 190 -> planeXY = false
                            200 -> inches = true
                            210 -> inches = false
                            900 -> relative = false
                            910 -> relative = true
                            901 -> arcCenterAbsolute = true
                            911 -> arcCenterAbsolute = false
                            930 -> if (!warnedInverseTime) {
                                warnedInverseTime = true
                                warn(
                                    lineNumber,
                                    GCodeWarningSeverity.WARNING,
                                    "G93 inverse time feed: duration estimate is inaccurate"
                                )
                            }
                            800 -> lineMotion = null
                        }
                    }
                    'M' -> {
                        val code = value.roundToInt()
                        if (code !in SUPPORTED_M_CODES) {
                            warn(
                                lineNumber,
                                GCodeWarningSeverity.WARNING,
                                "Unsupported M${formatCode(value)} (kept, GRBL may reject it)"
                            )
                        }
                        when (code) {
                            3, 4 -> laserOn = true
                            5, 2, 30 -> laserOn = false
                        }
                    }
                    'F' -> lineFeed = value
                    'S' -> linePower = value
                    'X' -> xWord = value
                    'Y' -> yWord = value
                    'I' -> iWord = value
                    'J' -> jWord = value
                    'R' -> rWord = value
                    'P' -> pWord = value
                }
            }

            val unit = if (inches) INCH else 1.0

            lineFeed?.let {
                feedRate = it * unit
                if (feedRate > maxFeed) maxFeed = feedRate
            }
            linePower?.let {
                power = it
                if (power > maxPower) maxPower = power
            }
            lineMotion?.let { motionMode = it }

            if (dwell) {
                val seconds = pWord ?: 0.0
                if (seconds > 0.0) {
                    durationMillis += seconds * 1_000.0
                }
                return
            }

            if (nonModalWithAxes) {
                // Axis words belong to G10/G28/G30/G92: position unknown.
                if (xWord != null || yWord != null) {
                    positionKnown = false
                }
                return
            }

            if (xWord == null && yWord == null) {
                return
            }

            val targetX = when {
                xWord == null -> x
                relative -> x + xWord * unit
                else -> xWord * unit
            }
            val targetY = when {
                yWord == null -> y
                relative -> y + yWord * unit
                else -> yWord * unit
            }

            if (!positionKnown) {
                // Start position before the first move is unknown.
                x = targetX
                y = targetY
                positionKnown = true
                if (startX == null) {
                    startX = targetX
                    startY = targetY
                }
                totalBounds.include(targetX, targetY)
                return
            }

            when (motionMode) {
                0 -> addRapid(lineNumber, targetX, targetY)
                1 -> addLinear(lineNumber, targetX, targetY)
                2, 3 -> addArc(
                    lineNumber, targetX, targetY,
                    clockwise = motionMode == 2,
                    i = iWord?.times(unit),
                    j = jWord?.times(unit),
                    r = rWord?.times(unit)
                )
            }

            x = targetX
            y = targetY
        }

        private fun addRapid(lineNumber: Long, targetX: Double, targetY: Double) {
            val dx = abs(targetX - x)
            val dy = abs(targetY - y)
            rapidDistance += hypot(dx, dy)
            durationMillis += maxOf(
                dx / profile.maxRateX,
                dy / profile.maxRateY
            ) * 60_000.0
            totalBounds.include(targetX, targetY)
            preview.add(x, y, targetX, targetY, SegmentType.RAPID, lineNumber)
        }

        private fun feedMoveType(): Byte {
            return if (laserOn && power > 0.0) {
                SegmentType.WORK
            } else {
                SegmentType.TRAVEL
            }
        }

        private fun addFeedDuration(lineNumber: Long, length: Double) {
            if (feedRate <= 0.0) {
                warn(
                    lineNumber,
                    GCodeWarningSeverity.ERROR,
                    "Feed move without feed rate (F)"
                )
                return
            }
            durationMillis += length / feedRate * 60_000.0
        }

        private fun addLinear(lineNumber: Long, targetX: Double, targetY: Double) {
            val length = hypot(targetX - x, targetY - y)
            val type = feedMoveType()
            accumulate(type, length)
            addFeedDuration(lineNumber, length)
            includePoint(type, x, y)
            includePoint(type, targetX, targetY)
            preview.add(x, y, targetX, targetY, type, lineNumber)
        }

        private fun addArc(
            lineNumber: Long,
            targetX: Double,
            targetY: Double,
            clockwise: Boolean,
            i: Double?,
            j: Double?,
            r: Double?
        ) {
            if (!planeXY) {
                if (!warnedArcPlane) {
                    warnedArcPlane = true
                    warn(
                        lineNumber,
                        GCodeWarningSeverity.WARNING,
                        "Arc outside XY plane: approximated as a line"
                    )
                }
                addLinear(lineNumber, targetX, targetY)
                return
            }

            val center = arcCenter(lineNumber, targetX, targetY, clockwise, i, j, r)
            if (center == null) {
                addLinear(lineNumber, targetX, targetY)
                return
            }
            arcCount++

            val (centerX, centerY) = center
            val radius = hypot(x - centerX, y - centerY)
            val startAngle = atan2(y - centerY, x - centerX)
            val endAngle = atan2(targetY - centerY, targetX - centerX)
            var sweep = endAngle - startAngle
            if (clockwise) {
                if (sweep >= -1e-9) sweep -= 2.0 * PI
            } else {
                if (sweep <= 1e-9) sweep += 2.0 * PI
            }

            val length = abs(sweep) * radius
            val type = feedMoveType()
            accumulate(type, length)
            addFeedDuration(lineNumber, length)

            val segments = ceil(length / ARC_SEGMENT_LENGTH_MM)
                .toInt()
                .coerceAtLeast(ceil(abs(sweep) / (PI / 18.0)).toInt())
                .coerceIn(1, MAX_ARC_SEGMENTS)

            includeArcExtremes(type, centerX, centerY, radius, startAngle, sweep)

            var previousX = x
            var previousY = y
            includePoint(type, previousX, previousY)
            for (index in 1..segments) {
                val pointX: Double
                val pointY: Double
                if (index == segments) {
                    pointX = targetX
                    pointY = targetY
                } else {
                    val angle = startAngle + sweep * index / segments
                    pointX = centerX + radius * cos(angle)
                    pointY = centerY + radius * sin(angle)
                }
                includePoint(type, pointX, pointY)
                preview.add(previousX, previousY, pointX, pointY, type, lineNumber)
                previousX = pointX
                previousY = pointY
            }
        }

        /*
         * Adds the exact axis extremes (0, 90, 180, 270 degrees)
         * crossed by the arc, so that bounds do not depend on the
         * preview sampling.
         */
        private fun includeArcExtremes(
            type: Byte,
            centerX: Double,
            centerY: Double,
            radius: Double,
            startAngle: Double,
            sweep: Double
        ) {
            val from = minOf(startAngle, startAngle + sweep)
            val to = maxOf(startAngle, startAngle + sweep)
            var quadrant = ceil(from / (PI / 2.0)).toInt()
            while (quadrant * (PI / 2.0) <= to) {
                val angle = quadrant * (PI / 2.0)
                includePoint(
                    type,
                    centerX + radius * cos(angle),
                    centerY + radius * sin(angle)
                )
                quadrant++
            }
        }

        /*
         * Arc center, using the same formulas as GRBL (gcode.c).
         */
        private fun arcCenter(
            lineNumber: Long,
            targetX: Double,
            targetY: Double,
            clockwise: Boolean,
            i: Double?,
            j: Double?,
            r: Double?
        ): Pair<Double, Double>? {
            if (r != null) {
                val dx = targetX - x
                val dy = targetY - y
                if (dx == 0.0 && dy == 0.0) {
                    warn(lineNumber, GCodeWarningSeverity.ERROR, "Radius arc with identical start and end")
                    return null
                }
                var h = 4.0 * r * r - dx * dx - dy * dy
                if (h < 0.0) {
                    warn(lineNumber, GCodeWarningSeverity.WARNING, "Arc radius too small for end point")
                    h = 0.0
                }
                var factor = -sqrt(h) / hypot(dx, dy)
                if (!clockwise) factor = -factor
                if (r < 0.0) factor = -factor
                val offsetI = 0.5 * (dx - dy * factor)
                val offsetJ = 0.5 * (dy + dx * factor)
                return Pair(x + offsetI, y + offsetJ)
            }
            if (i == null && j == null) {
                warn(lineNumber, GCodeWarningSeverity.ERROR, "Arc without I/J or R")
                return null
            }
            return if (arcCenterAbsolute) {
                Pair(i ?: x, j ?: y)
            } else {
                Pair(x + (i ?: 0.0), y + (j ?: 0.0))
            }
        }

        private fun accumulate(type: Byte, length: Double) {
            if (type == SegmentType.WORK) {
                workDistance += length
                usesLaser = true
            } else {
                travelDistance += length
            }
        }

        private fun includePoint(type: Byte, pointX: Double, pointY: Double) {
            totalBounds.include(pointX, pointY)
            if (type == SegmentType.WORK) {
                workBounds.include(pointX, pointY)
            }
        }

        private fun warn(lineNumber: Long, severity: GCodeWarningSeverity, message: String) {
            when (severity) {
                GCodeWarningSeverity.ERROR -> errorCount++
                GCodeWarningSeverity.WARNING -> warningCount++
                GCodeWarningSeverity.INFO -> Unit
            }
            if (warnings.size < maxStoredWarnings) {
                warnings.add(GCodeWarning(lineNumber, severity, message))
            }
        }

        private fun formatCode(value: Double): String {
            return if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                value.toString()
            }
        }

        fun build(name: String, sizeBytes: Long): GCodeAnalysis {
            val work = workBounds.build()
            val total = totalBounds.build()
            checkTravel(work ?: total)
            return GCodeAnalysis(
                name = name,
                sizeBytes = sizeBytes,
                totalLines = totalLines,
                executableCommands = executable,
                commentLines = comments,
                emptyLines = empty,
                workBounds = work,
                totalBounds = total,
                startX = startX,
                startY = startY,
                workDistanceMm = workDistance,
                travelDistanceMm = travelDistance,
                rapidDistanceMm = rapidDistance,
                estimatedDurationMillis = durationMillis.toLong(),
                maxFeedRate = maxFeed,
                maxPower = maxPower,
                usesLaser = usesLaser,
                arcCount = arcCount,
                longestLineLength = longestLine,
                warnings = warnings.toList(),
                warningCount = warningCount,
                errorCount = errorCount,
                preview = preview.build()
            )
        }

        /*
         * Work coordinates are compared with the machine travel,
         * assuming the work origin is at the machine origin.
         */
        private fun checkTravel(bounds: GCodeBounds?) {
            if (bounds == null) return
            val travelX = profile.travelX
            val travelY = profile.travelY
            val outsideX = travelX != null &&
                    (bounds.minX < -0.001 || bounds.maxX > travelX + 0.001)
            val outsideY = travelY != null &&
                    (bounds.minY < -0.001 || bounds.maxY > travelY + 0.001)
            if (outsideX || outsideY) {
                warn(
                    0,
                    GCodeWarningSeverity.WARNING,
                    "Program exceeds machine travel " +
                            "(${travelX ?: "?"} x ${travelY ?: "?"} mm) " +
                            "if the work origin is the machine origin"
                )
            }
        }
    }

    private data class Word(
        val letter: Char,
        val value: Double
    )
}
