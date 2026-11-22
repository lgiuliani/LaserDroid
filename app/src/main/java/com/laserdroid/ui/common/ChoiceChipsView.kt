package com.laserdroid.ui.common

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.laserdroid.R
import kotlin.math.abs

/**
 * Row of large selectable chips (single choice), for touch use.
 */
class ChoiceChipsView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null
) : LinearLayout(context, attributes) {

    var onValueSelected: ((Double) -> Unit)? = null

    private var values: List<Double> = emptyList()
    private val chips = mutableListOf<TextView>()

    var selectedValue: Double = 0.0
        private set

    init {
        orientation = HORIZONTAL
    }

    fun setChoices(
        choices: List<Double>,
        selected: Double,
        label: (Double) -> String
    ) {
        values = choices
        removeAllViews()
        chips.clear()

        val margin = (3 * resources.displayMetrics.density).toInt()
        choices.forEachIndexed { index, value ->
            val chip = TextView(context).apply {
                text = label(value)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                paint.isFakeBoldText = true
                maxLines = 1
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    select(index, notify = true)
                }
            }
            val params = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            params.setMargins(margin, margin, margin, margin)
            addView(chip, params)
            chips.add(chip)
        }
        selectValue(selected, notify = false)
    }

    /** Selects the choice closest to [value]. */
    fun selectValue(value: Double, notify: Boolean = false) {
        if (values.isEmpty()) return
        var best = 0
        values.forEachIndexed { index, candidate ->
            if (abs(candidate - value) < abs(values[best] - value)) {
                best = index
            }
        }
        select(best, notify)
    }

    private fun select(index: Int, notify: Boolean) {
        selectedValue = values[index]
        chips.forEachIndexed { chipIndex, chip ->
            chip.isSelected = chipIndex == index
        }
        if (notify) {
            onValueSelected?.invoke(selectedValue)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        chips.forEach { it.isEnabled = enabled }
    }
}
