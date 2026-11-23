package com.laserdroid.ui.job

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.laserdroid.R
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.core.gcode.GCodeAnalysis
import com.laserdroid.core.jobs.JobSnapshot
import com.laserdroid.core.jobs.JobState
import java.util.Locale

/**
 * Job panel: open, preview, start, pause, resume, stop.
 *
 * Pure view: every action is forwarded through callbacks, the
 * job logic lives in JobManager (core-jobs).
 */
class JobPanelView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
    defaultStyleAttribute: Int = 0
) : FrameLayout(context, attributes, defaultStyleAttribute) {

    var onOpenRequested: (() -> Unit)? = null
    var onStartRequested: ((laserOff: Boolean) -> Unit)? = null
    var onPauseRequested: (() -> Unit)? = null
    var onResumeRequested: (() -> Unit)? = null
    var onStopRequested: (() -> Unit)? = null
    var onFrameRequested: (() -> Unit)? = null

    private val preview: GCodePreviewView
    private val infoText: TextView
    private val statusText: TextView
    private val progressBar: ProgressBar
    private val laserOffCheck: CompoundButton
    private val openButton: View
    private val startButton: TextView
    private val frameButton: View
    private val pauseButton: View
    private val resumeButton: View
    private val stopButton: View

    private var displayedAnalysis: GCodeAnalysis? = null
    private var jobSnapshot: JobSnapshot = JobSnapshot.EMPTY
    private var machineState: MachineState = MachineState.Disconnected
    private var machineTravelX: Double? = null
    private var machineTravelY: Double? = null
    private var framingActive = false

    init {
        LayoutInflater.from(context).inflate(R.layout.view_job_panel, this, true)

        preview = findViewById(R.id.jobPreview)
        infoText = findViewById(R.id.textJobInfo)
        statusText = findViewById(R.id.textJobStatus)
        progressBar = findViewById(R.id.progressJob)
        laserOffCheck = findViewById(R.id.checkJobLaserOff)
        openButton = findViewById(R.id.buttonJobOpen)
        startButton = findViewById(R.id.buttonJobStart)
        frameButton = findViewById(R.id.buttonJobFrame)
        pauseButton = findViewById(R.id.buttonJobPause)
        resumeButton = findViewById(R.id.buttonJobResume)
        stopButton = findViewById(R.id.buttonJobStop)

        openButton.setOnClickListener { onOpenRequested?.invoke() }
        frameButton.setOnClickListener { onFrameRequested?.invoke() }
        startButton.setOnClickListener {
            onStartRequested?.invoke(laserOffCheck.isChecked)
        }
        pauseButton.setOnClickListener { onPauseRequested?.invoke() }
        resumeButton.setOnClickListener { onResumeRequested?.invoke() }
        stopButton.setOnClickListener { onStopRequested?.invoke() }

        updateButtons()
    }

    var isLaserOffChecked: Boolean
        get() = laserOffCheck.isChecked
        set(value) {
            laserOffCheck.isChecked = value
        }

    /** Machine travel ($130/$131) for the preview frame. */
    fun setMachineTravel(travelX: Double?, travelY: Double?) {
        if (travelX == machineTravelX && travelY == machineTravelY) return
        machineTravelX = travelX
        machineTravelY = travelY
        preview.setProgram(displayedAnalysis, travelX, travelY)
    }

    fun updateJob(snapshot: JobSnapshot, nowMillis: Long) {
        jobSnapshot = snapshot

        if (snapshot.analysis !== displayedAnalysis) {
            displayedAnalysis = snapshot.analysis
            preview.setProgram(snapshot.analysis, machineTravelX, machineTravelY)
            infoText.text = formatAnalysis(snapshot)
        }

        preview.setExecutedLine(
            if (snapshot.state == JobState.COMPLETED) Long.MAX_VALUE
            else snapshot.currentLine ?: 0L
        )

        progressBar.progress = (snapshot.percent * 10.0).toInt()
        statusText.text = formatStatus(snapshot, nowMillis)
        updateButtons()
    }

    /** Frame tracing in progress: STOP stays available. */
    fun setFramingActive(active: Boolean) {
        framingActive = active
        updateButtons()
    }

    fun updateMachineState(state: MachineState) {
        machineState = state
        updateButtons()
    }

    /** Head position in work coordinates. */
    fun updateHeadPosition(position: MachinePosition?) {
        preview.setHeadPosition(position?.x, position?.y)
    }

    private fun updateButtons() {
        val state = jobSnapshot.state
        val connected = machineState != MachineState.Disconnected &&
                machineState != MachineState.Connecting &&
                machineState !is MachineState.Error

        val idle = machineState == MachineState.Idle && !framingActive

        openButton.isEnabled = !state.isActive && state != JobState.LOADING && !framingActive
        startButton.isEnabled = connected &&
                state.canStart &&
                jobSnapshot.analysis != null &&
                idle
        startButton.text = if (jobSnapshot.isRecoverable) "RECOVER" else "START"
        frameButton.isEnabled = connected &&
                idle &&
                !state.isActive &&
                jobSnapshot.analysis != null
        pauseButton.isEnabled = connected && state.canPause
        resumeButton.isEnabled = connected && state.canResume
        stopButton.isEnabled = connected && (state.isActive || framingActive)
        laserOffCheck.isEnabled = !state.isActive
    }

    /*
     * ------------------------------------------------------------
     * Formatting
     * ------------------------------------------------------------
     */

    private fun formatAnalysis(snapshot: JobSnapshot): String {
        val analysis = snapshot.analysis ?: return snapshot.message
            ?: (snapshot.name ?: "No program loaded")

        return buildString {
            append(analysis.name)
            append("  ")
            append(formatSize(analysis.sizeBytes))
            append("  ")
            append(analysis.executableCommands)
            append(" cmd\n")

            val bounds = analysis.workBounds ?: analysis.totalBounds
            if (bounds != null) {
                append(
                    String.format(
                        Locale.US,
                        "%.1f x %.1f mm  X[%.1f..%.1f] Y[%.1f..%.1f]\n",
                        bounds.width, bounds.height,
                        bounds.minX, bounds.maxX,
                        bounds.minY, bounds.maxY
                    )
                )
            }

            append("Estimated ")
            append(formatDuration(analysis.estimatedDurationMillis))
            append(
                String.format(
                    Locale.US,
                    "  Fmax %.0f  Smax %.0f",
                    analysis.maxFeedRate,
                    analysis.maxPower
                )
            )
            if (!analysis.usesLaser) {
                append("  (no laser)")
            }
            if (analysis.preview.truncated) {
                append("  preview truncated")
            }

            if (analysis.errorCount > 0 || analysis.warningCount > 0) {
                append("\n")
                append("Errors ${analysis.errorCount}, warnings ${analysis.warningCount}")
                analysis.warnings.firstOrNull {
                    it.severity.name != "INFO"
                }?.let { first ->
                    append(" - L${first.lineNumber}: ${first.message}")
                }
            }
        }
    }

    private fun formatStatus(snapshot: JobSnapshot, nowMillis: Long): String {
        return buildString {
            append(snapshot.state.name)
            if (snapshot.state.isActive && snapshot.laserOff) {
                append(" [LASER OFF]")
            }
            if (snapshot.totalCommands > 0 && snapshot.state != JobState.READY) {
                append(
                    String.format(
                        Locale.US,
                        "  %.1f%%  %d/%d",
                        snapshot.percent,
                        snapshot.commandsAcknowledged,
                        snapshot.totalCommands
                    )
                )
            }
            if (snapshot.startedAtMillis != null) {
                append("\n")
                append(formatDuration(snapshot.elapsedMillis(nowMillis)))
                snapshot.remainingMillis(nowMillis)?.let { remaining ->
                    append(" / remaining ")
                    append(formatDuration(remaining))
                }
                snapshot.currentLine?.let { line ->
                    append("  line ")
                    append(line)
                }
            }
            if (!snapshot.state.isActive && snapshot.message != null &&
                snapshot.state != JobState.READY
            ) {
                append("\n")
                append(snapshot.message)
            }
        }
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 0 -> "?"
            bytes < 1_024 -> "$bytes B"
            bytes < 1_048_576 -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        }
    }
}
