package com.laserdroid.ui.jog

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.laserdroid.R
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import com.laserdroid.ui.common.ChoiceChipsView
import java.math.BigDecimal

/**
 * Jog page: circular X/Y dial, step and speed choices, STOP JOG.
 *
 * Pure view: actions are forwarded through the callbacks.
 * The machine position is displayed by the main status bar.
 */
class JogPanelView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
    defaultStyleAttribute: Int = 0
) : FrameLayout(context, attributes, defaultStyleAttribute) {

    companion object {

        /*
         * Jog speeds in mm/min.
         * Sculpfun S10: $110 = $111 = 6000.
         */
        private val SPEED_VALUES =
            listOf(200.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 6_000.0)

        /* Jog steps in millimeters. */
        private val STEP_VALUES =
            listOf(0.1, 0.5, 1.0, 5.0, 10.0, 50.0, 100.0)

        private const val DEFAULT_SPEED = 1_000.0
        private const val DEFAULT_STEP = 1.0
    }

    /** Short tap on a direction: one step. */
    var onJogClick:
            (direction: JogDirection, step: Double, speed: Double) -> Unit =
        { _, _, _ -> }

    /** Long press: continuous jog until release. */
    var onContinuousJogStart:
            (direction: JogDirection, step: Double, speed: Double) -> Unit =
        { _, _, _ -> }

    /** Finger released after a continuous jog. */
    var onContinuousJogStop: () -> Unit = { }

    /** Center button: absolute move to X0 Y0 at the selected speed. */
    var onHomeRequested: (speed: Double) -> Unit = { }

    /** STOP JOG button. */
    var onCancelRequested: () -> Unit = { }

    private val dial: JogDialView
    private val stepChips: ChoiceChipsView
    private val speedChips: ChoiceChipsView
    private val cancelButton: View

    init {
        LayoutInflater.from(context).inflate(R.layout.view_jog_panel, this, true)

        dial = findViewById(R.id.jogDial)
        stepChips = findViewById(R.id.chipsJogStep)
        speedChips = findViewById(R.id.chipsJogSpeed)
        cancelButton = findViewById(R.id.buttonJogCancel)

        stepChips.setChoices(STEP_VALUES, DEFAULT_STEP) { formatNumber(it) }
        speedChips.setChoices(SPEED_VALUES, DEFAULT_SPEED) { formatSpeed(it) }

        dial.onDirectionClick = { direction ->
            onJogClick(direction, getSelectedStep(), getSelectedSpeed())
        }
        dial.onContinuousStart = { direction ->
            onContinuousJogStart(direction, getSelectedStep(), getSelectedSpeed())
        }
        dial.onContinuousStop = { onContinuousJogStop() }
        dial.onCenterClick = { onHomeRequested(getSelectedSpeed()) }

        cancelButton.setOnClickListener { onCancelRequested() }

        updateMachineState(MachineState.Disconnected)
    }

    fun getSelectedSpeed(): Double = speedChips.selectedValue

    fun getSelectedStep(): Double = stepChips.selectedValue

    /** Restores a saved speed (closest available choice). */
    fun setSelectedSpeed(value: Double) {
        speedChips.selectValue(value)
    }

    /** Restores a saved step (closest available choice). */
    fun setSelectedStep(value: Double) {
        stepChips.selectValue(value)
    }

    /**
     * GRBL accepts jog commands while Idle or already jogging.
     * STOP JOG stays available while the machine is connected.
     */
    fun updateMachineState(state: MachineState) {
        val jogEnabled =
            state == MachineState.Idle || state == MachineState.Jog
        val connected =
            state != MachineState.Disconnected &&
                    state != MachineState.Connecting &&
                    state !is MachineState.Error

        dial.isEnabled = jogEnabled
        stepChips.isEnabled = connected
        speedChips.isEnabled = connected
        cancelButton.isEnabled = connected
    }

    /** Kept for compatibility: the position is shown by the status bar. */
    @Suppress("UNUSED_PARAMETER")
    fun updateMachinePosition(position: MachinePosition) = Unit

    private fun formatNumber(value: Double): String {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    private fun formatSpeed(value: Double): String {
        return if (value >= 1_000.0) {
            formatNumber(value / 1_000.0) + "k"
        } else {
            formatNumber(value)
        }
    }
}
