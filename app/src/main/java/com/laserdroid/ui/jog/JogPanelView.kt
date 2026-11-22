package com.laserdroid.ui.jog

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.laserdroid.R
import com.laserdroid.controllers.api.MachinePosition
import com.laserdroid.controllers.api.MachineState
import java.math.BigDecimal
import java.util.Locale

class JogPanelView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
    defaultStyleAttribute: Int = 0
) : ConstraintLayout(
    context,
    attributes,
    defaultStyleAttribute
) {

    companion object {

        /*
         * Jog speeds in mm/min.
         *
         * Sculpfun configuration:
         * $110 = 6000
         * $111 = 6000
         */
        private val SPEED_VALUES =
            listOf(
                100.0,
                200.0,
                500.0,
                1_000.0,
                2_000.0,
                3_000.0,
                4_000.0,
                5_000.0,
                6_000.0
            )

        /*
         * Jog step values in millimeters.
         */
        private val STEP_VALUES =
            listOf(
                0.1,
                0.2,
                0.5,
                1.0,
                2.0,
                5.0,
                10.0,
                20.0,
                50.0,
                100.0
            )

        private const val DEFAULT_SPEED =
            500.0

        private const val DEFAULT_STEP =
            1.0
    }

    /*
     * UI references.
     */
    private val speedValueText:
            TextView

    private val stepValueText:
            TextView

    private val machineStateText:
            TextView

    private val machinePositionText:
            TextView

    private val speedSlider:
            SeekBar

    private val stepSlider:
            SeekBar

    /*
     * Current Jog parameters.
     */
    private var selectedSpeed:
            Double = DEFAULT_SPEED

    private var selectedStep:
            Double = DEFAULT_STEP

    /*
     * Short press.
     */
    var onJogClick:
                (
        direction: JogDirection,
        step: Double,
        speed: Double
    ) -> Unit =
        { _, _, _ -> }

    /*
     * Long press.
     *
     * MainActivity starts continuous Jog.
     */
    var onContinuousJogStart:
                (
        direction: JogDirection,
        step: Double,
        speed: Double
    ) -> Unit =
        { _, _, _ -> }

    /*
     * Finger released after continuous Jog.
     *
     * MainActivity sends Jog Cancel.
     */
    var onContinuousJogStop:
                () -> Unit =
        { }

    /*
     * HOME ignores the selected step.
     *
     * It only uses the selected speed.
     */
    var onHomeRequested:
                (
        speed: Double
    ) -> Unit =
        { }

    /*
     * Explicit CANCEL JOG button.
     */
    var onCancelRequested:
                () -> Unit =
        { }

    init {

        LayoutInflater
            .from(context)
            .inflate(
                R.layout.view_jog_panel,
                this,
                true
            )

        speedValueText =
            findViewById(
                R.id.textJogSpeedValue
            )

        stepValueText =
            findViewById(
                R.id.textJogStepValue
            )

        machineStateText =
            findViewById(
                R.id.textJogMachineState
            )

        machinePositionText =
            findViewById(
                R.id.textJogPosition
            )

        speedSlider =
            findViewById(
                R.id.sliderJogSpeed
            )

        stepSlider =
            findViewById(
                R.id.sliderJogStep
            )

        configureSliders()

        configureDirectionButtons()

        updateDisplayedValues()
    }

    /*
     * Configure left Speed slider
     * and right Step slider.
     */
    private fun configureSliders() {

        /*
         * LEFT SLIDER
         *
         * Speed:
         *
         * 100
         * 200
         * 500
         * 1000
         * ...
         * 6000 mm/min
         */
        speedSlider.max =
            SPEED_VALUES.lastIndex

        speedSlider.progress =
            SPEED_VALUES
                .indexOf(
                    DEFAULT_SPEED
                )
                .coerceAtLeast(0)

        speedSlider.setOnSeekBarChangeListener(
            object :
                SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {

                    val safeIndex =
                        progress.coerceIn(
                            0,
                            SPEED_VALUES.lastIndex
                        )

                    selectedSpeed =
                        SPEED_VALUES[
                            safeIndex
                        ]

                    updateDisplayedValues()
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {
                    // Nothing required.
                }

                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {
                    // Value already updated.
                }
            }
        )

        /*
         * RIGHT SLIDER
         *
         * Step:
         *
         * 0.1
         * 0.2
         * 0.5
         * 1
         * ...
         * 100 mm
         */
        stepSlider.max =
            STEP_VALUES.lastIndex

        stepSlider.progress =
            STEP_VALUES
                .indexOf(
                    DEFAULT_STEP
                )
                .coerceAtLeast(0)

        stepSlider.setOnSeekBarChangeListener(
            object :
                SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {

                    val safeIndex =
                        progress.coerceIn(
                            0,
                            STEP_VALUES.lastIndex
                        )

                    selectedStep =
                        STEP_VALUES[
                            safeIndex
                        ]

                    updateDisplayedValues()
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {
                    // Nothing required.
                }

                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {
                    // Value already updated.
                }
            }
        )
    }

    /*
     * Configure the eight Jog directions
     * plus HOME and CANCEL.
     */
    private fun configureDirectionButtons() {

        bindDirection(
            R.id.buttonJogNorth,
            JogDirection.NORTH
        )

        bindDirection(
            R.id.buttonJogNorthEast,
            JogDirection.NORTH_EAST
        )

        bindDirection(
            R.id.buttonJogEast,
            JogDirection.EAST
        )

        bindDirection(
            R.id.buttonJogSouthEast,
            JogDirection.SOUTH_EAST
        )

        bindDirection(
            R.id.buttonJogSouth,
            JogDirection.SOUTH
        )

        bindDirection(
            R.id.buttonJogSouthWest,
            JogDirection.SOUTH_WEST
        )

        bindDirection(
            R.id.buttonJogWest,
            JogDirection.WEST
        )

        bindDirection(
            R.id.buttonJogNorthWest,
            JogDirection.NORTH_WEST
        )

        /*
         * HOME
         *
         * HOME uses the left slider speed.
         * The right Step slider is ignored.
         *
         * MainActivity will generate:
         *
         * $J=G21 G90 X0 Y0 F...
         */
        findViewById<Button>(
            R.id.buttonJogHome
        ).setOnClickListener {

            onHomeRequested(
                selectedSpeed
            )
        }

        /*
         * Explicit Jog Cancel.
         */
        findViewById<Button>(
            R.id.buttonJogCancel
        ).setOnClickListener {

            onCancelRequested()
        }
    }

    /*
     * Configure short and long press behaviour
     * for one direction button.
     */
    private fun bindDirection(
        buttonId: Int,
        direction: JogDirection
    ) {

        val button =
            findViewById<Button>(
                buttonId
            )

        var longPressStarted =
            false

        val startContinuousJog =
            Runnable {

                /*
                 * Do not start if the button was
                 * disabled while waiting.
                 */
                if (!button.isEnabled) {
                    return@Runnable
                }

                longPressStarted =
                    true

                onContinuousJogStart(
                    direction,
                    selectedStep,
                    selectedSpeed
                )
            }

        button.setOnTouchListener {
                view,
                event ->

            when (
                event.actionMasked
            ) {

                MotionEvent.ACTION_DOWN -> {

                    longPressStarted =
                        false

                    /*
                     * Wait for Android's standard
                     * long-press duration.
                     */
                    view.postDelayed(
                        startContinuousJog,
                        ViewConfiguration
                            .getLongPressTimeout()
                            .toLong()
                    )

                    true
                }

                MotionEvent.ACTION_UP -> {

                    view.removeCallbacks(
                        startContinuousJog
                    )

                    if (longPressStarted) {

                        /*
                         * Finger released:
                         * stop continuous motion.
                         */
                        onContinuousJogStop()

                    } else {

                        /*
                         * Normal short press.
                         */
                        onJogClick(
                            direction,
                            selectedStep,
                            selectedSpeed
                        )
                    }

                    view.performClick()

                    true
                }

                MotionEvent.ACTION_CANCEL -> {

                    view.removeCallbacks(
                        startContinuousJog
                    )

                    if (longPressStarted) {

                        onContinuousJogStop()
                    }

                    true
                }

                else -> true
            }
        }
    }

    /*
     * Refresh visible Speed and Step values.
     */
    private fun updateDisplayedValues() {

        speedValueText.text =
            buildString {

                append(
                    formatNumber(
                        selectedSpeed
                    )
                )

                appendLine()

                append(
                    "mm/min"
                )
            }

        stepValueText.text =
            buildString {

                append(
                    formatNumber(
                        selectedStep
                    )
                )

                appendLine()

                append(
                    "mm"
                )
            }
    }

    /*
     * PUBLIC STATE API
     *
     * These methods are used by MainActivity
     * to save and restore the sliders during
     * portrait/landscape rotation.
     */

    fun getSelectedSpeed(): Double {

        return selectedSpeed
    }

    fun getSelectedStep(): Double {

        return selectedStep
    }

    fun setSelectedSpeed(
        value: Double
    ) {

        /*
         * Only accept values present in
         * SPEED_VALUES.
         */
        val index =
            SPEED_VALUES.indexOf(
                value
            )

        if (
            index < 0
        ) {
            return
        }

        selectedSpeed =
            value

        speedSlider.progress =
            index

        updateDisplayedValues()
    }

    fun setSelectedStep(
        value: Double
    ) {

        /*
         * Only accept values present in
         * STEP_VALUES.
         */
        val index =
            STEP_VALUES.indexOf(
                value
            )

        if (
            index < 0
        ) {
            return
        }

        selectedStep =
            value

        stepSlider.progress =
            index

        updateDisplayedValues()
    }

    /*
     * Update GRBL machine state displayed
     * by the Jog panel.
     */
    fun updateMachineState(
        state: MachineState
    ) {

        machineStateText.text =
            when (state) {

                MachineState.Disconnected ->
                    "DISCONNECTED"

                MachineState.Connecting ->
                    "CONNECTING"

                MachineState.Idle ->
                    "IDLE"

                MachineState.Running ->
                    "RUNNING"

                MachineState.Jog ->
                    "JOG"

                MachineState.Hold ->
                    "HOLD"

                MachineState.Alarm ->
                    "ALARM"

                is MachineState.Error ->
                    "ERROR"
            }

        /*
         * GRBL accepts Jog commands while
         * IDLE or already JOGGING.
         */
        val jogEnabled =
            state == MachineState.Idle ||
                    state == MachineState.Jog

        setJogButtonsEnabled(
            jogEnabled
        )

        /*
         * Keep the sliders usable whenever
         * the controller is not disconnected.
         */
        val controlsEnabled =
            state != MachineState.Disconnected &&
                    state !is MachineState.Error

        speedSlider.isEnabled =
            controlsEnabled

        stepSlider.isEnabled =
            controlsEnabled

        /*
         * CANCEL JOG stays available whenever
         * communication with the controller exists.
         */
        findViewById<Button>(
            R.id.buttonJogCancel
        ).isEnabled =
            controlsEnabled
    }

    /*
     * Update X/Y display.
     *
     * Z is intentionally not displayed in
     * the LaserDroid Jog interface.
     */
    fun updateMachinePosition(
        position: MachinePosition
    ) {

        machinePositionText.text =
            buildString {

                append(
                    "X "
                )

                append(
                    formatCoordinate(
                        position.x
                    )
                )

                append(
                    "     Y "
                )

                append(
                    formatCoordinate(
                        position.y
                    )
                )
            }
    }

    /*
     * Enable or disable the directional
     * controls and HOME.
     */
    private fun setJogButtonsEnabled(
        enabled: Boolean
    ) {

        val buttons =
            listOf(
                R.id.buttonJogNorth,
                R.id.buttonJogNorthEast,
                R.id.buttonJogEast,
                R.id.buttonJogSouthEast,
                R.id.buttonJogSouth,
                R.id.buttonJogSouthWest,
                R.id.buttonJogWest,
                R.id.buttonJogNorthWest,
                R.id.buttonJogHome
            )

        buttons.forEach { id ->

            findViewById<Button>(
                id
            ).isEnabled =
                enabled
        }
    }

    /*
     * Format slider values.
     *
     * Examples:
     *
     * 500.0 -> "500"
     * 0.1   -> "0.1"
     * 1000  -> "1000"
     */
    private fun formatNumber(
        value: Double
    ): String {

        return BigDecimal
            .valueOf(value)
            .stripTrailingZeros()
            .toPlainString()
    }

    /*
     * Display position with fixed precision.
     */
    private fun formatCoordinate(
        value: Double
    ): String {

        return String.format(
            Locale.US,
            "%.3f",
            value
        )
    }
}