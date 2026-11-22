package com.laserdroid.core.gcode

/**
 * Axis-aligned rectangle in work coordinates (millimeters).
 */
data class GCodeBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
) {
    val width: Double
        get() = maxX - minX

    val height: Double
        get() = maxY - minY
}

/**
 * Mutable bounds accumulator used during analysis.
 */
internal class BoundsBuilder {

    private var minX = Double.POSITIVE_INFINITY
    private var minY = Double.POSITIVE_INFINITY
    private var maxX = Double.NEGATIVE_INFINITY
    private var maxY = Double.NEGATIVE_INFINITY

    val isEmpty: Boolean
        get() = minX > maxX

    fun include(x: Double, y: Double) {
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }

    fun build(): GCodeBounds? {
        if (isEmpty) {
            return null
        }
        return GCodeBounds(minX, minY, maxX, maxY)
    }
}

enum class GCodeWarningSeverity {
    /** Information only, the program can run. */
    INFO,

    /** The analysis may be inaccurate or the result unexpected. */
    WARNING,

    /** GRBL will reject this line. */
    ERROR
}

data class GCodeWarning(
    val lineNumber: Long,
    val severity: GCodeWarningSeverity,
    val message: String
)

/**
 * Machine characteristics used for estimations and limit checks.
 */
data class MachineProfile(
    /** $110, mm/min. */
    val maxRateX: Double = 6_000.0,
    /** $111, mm/min. */
    val maxRateY: Double = 6_000.0,
    /** $130, mm. Null when unknown. */
    val travelX: Double? = null,
    /** $131, mm. Null when unknown. */
    val travelY: Double? = null
) {
    companion object {

        /**
         * Builds a profile from GRBL settings ($110, $111, $130, $131).
         * Missing values keep the defaults.
         */
        fun fromGrblSettings(
            settings: Map<Int, String>
        ): MachineProfile {
            val defaults = MachineProfile()
            return MachineProfile(
                maxRateX = settings[110]?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 } ?: defaults.maxRateX,
                maxRateY = settings[111]?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 } ?: defaults.maxRateY,
                travelX = settings[130]?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 },
                travelY = settings[131]?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 }
            )
        }
    }
}

/**
 * Result of a full pass over a G-code program.
 *
 * Coordinates are work coordinates, in millimeters.
 */
data class GCodeAnalysis(
    val name: String,
    val sizeBytes: Long,
    val totalLines: Long,
    val executableCommands: Int,
    val commentLines: Long,
    val emptyLines: Long,

    /** Bounds of moves with the laser emitting. */
    val workBounds: GCodeBounds?,

    /** Bounds of every move, rapids included. */
    val totalBounds: GCodeBounds?,

    /** First programmed position. */
    val startX: Double?,
    val startY: Double?,

    val workDistanceMm: Double,
    val travelDistanceMm: Double,
    val rapidDistanceMm: Double,

    /**
     * Theoretical duration without acceleration.
     * Real jobs are usually longer.
     */
    val estimatedDurationMillis: Long,

    val maxFeedRate: Double,
    val maxPower: Double,
    val usesLaser: Boolean,
    val arcCount: Int,
    val longestLineLength: Int,

    val warnings: List<GCodeWarning>,
    val warningCount: Int,
    val errorCount: Int,

    val preview: GCodePreviewPath
) {
    val hasErrors: Boolean
        get() = errorCount > 0
}
