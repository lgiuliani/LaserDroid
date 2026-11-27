package com.laserdroid.core.jobs

import com.laserdroid.core.gcode.GCodeAnalysis

/**
 * Immutable view of the current job, published by [JobManager].
 *
 * Three progress levels are kept separate (specification 7):
 * - [commandsSent]: transmitted to the controller,
 * - [commandsAcknowledged]: accepted by the controller (ok),
 * - [executionConfirmed]: the controller reported Idle after the
 *   last acknowledgement, i.e. motion physically finished.
 */
data class JobSnapshot(
    val state: JobState,
    val name: String? = null,
    val analysis: GCodeAnalysis? = null,
    val laserOff: Boolean = true,
    /** Air assist (M8) on during the job. */
    val airAssist: Boolean = false,
    val totalCommands: Int = 0,
    val commandsSent: Int = 0,
    val commandsAcknowledged: Int = 0,
    val currentLine: Long? = null,
    val executionConfirmed: Boolean = false,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    /** Reason of ERROR / ABORTED / DISCONNECTED, or last notice. */
    val message: String? = null,
    val errorLine: Long? = null,
    val errorCommand: String? = null
) {

    /**
     * Interrupted by a disconnection after at least one line:
     * the user must choose Resume, Restart or Cancel.
     */
    val isRecoverable: Boolean
        get() = state == JobState.DISCONNECTED &&
                currentLine != null &&
                commandsSent > 0 &&
                analysis != null

    /** Acknowledged commands, 0..100. */
    val percent: Double
        get() = if (totalCommands <= 0) {
            if (state == JobState.COMPLETED) 100.0 else 0.0
        } else {
            commandsAcknowledged * 100.0 / totalCommands
        }

    fun elapsedMillis(nowMillis: Long): Long {
        val start = startedAtMillis ?: return 0L
        val end = endedAtMillis ?: nowMillis
        return (end - start).coerceAtLeast(0L)
    }

    /**
     * Remaining time: extrapolated from the real pace once enough
     * progress is available, otherwise from the analysis estimate.
     */
    fun remainingMillis(nowMillis: Long): Long? {
        if (!state.isActive) {
            return null
        }
        val fraction = if (totalCommands > 0) {
            commandsAcknowledged.toDouble() / totalCommands
        } else {
            0.0
        }
        val elapsed = elapsedMillis(nowMillis)
        if (fraction >= 0.02 && elapsed > 0L) {
            return (elapsed * (1.0 - fraction) / fraction).toLong()
        }
        val estimate = analysis?.estimatedDurationMillis ?: return null
        return (estimate * (1.0 - fraction)).toLong()
    }

    companion object {
        val EMPTY = JobSnapshot(state = JobState.EMPTY)
    }
}

/**
 * How to continue a job interrupted by a disconnection.
 */
enum class RecoveryMode {
    /** Continue after the last line sent before the interruption. */
    RESUME,

    /** Run the whole program again from its first line. */
    RESTART
}

/**
 * Situation of an interrupted job, evaluated after reconnection.
 *
 * GRBL keeps executing its buffer when the USB link is lost: every
 * line sent before the interruption is considered executed.
 * If GRBL was then reset, its position is lost; the head is assumed
 * to be at the end of the last executed line ([expectedX],
 * [expectedY]) and the work origin is redefined with G10 L20.
 */
data class RecoveryAssessment(
    val lineNumber: Long,
    val commandsDone: Int,
    val totalCommands: Int,
    val expectedX: Double?,
    val expectedY: Double?,
    val currentX: Double?,
    val currentY: Double?,
    /** Reported position matches the expected one. */
    val positionTrusted: Boolean,
    /** G10/G28/G30/G92 in the program: the expected point may be wrong. */
    val positionUncertain: Boolean
) {
    val canResynchronize: Boolean
        get() = expectedX != null && expectedY != null
}

/**
 * Result of [JobManager.start].
 */
sealed interface JobStartResult {
    data object Started : JobStartResult
    data class Rejected(val reason: String) : JobStartResult
}
