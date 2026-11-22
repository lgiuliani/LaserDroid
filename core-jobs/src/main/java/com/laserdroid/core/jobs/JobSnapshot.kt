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
 * Result of [JobManager.start].
 */
sealed interface JobStartResult {
    data object Started : JobStartResult
    data class Rejected(val reason: String) : JobStartResult
}
