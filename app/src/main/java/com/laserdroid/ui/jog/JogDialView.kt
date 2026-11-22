package com.laserdroid.ui.jog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.laserdroid.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Circular X/Y jog dial: 8 direction sectors around a HOME button.
 *
 * - Short tap on a sector: one step ([onDirectionClick]).
 * - Long press on a sector: continuous jog until release
 *   ([onContinuousStart] / [onContinuousStop]).
 * - Tap on the center: [onCenterClick].
 */
class JogDialView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
    defaultStyleAttribute: Int = 0
) : View(context, attributes, defaultStyleAttribute) {

    companion object {
        private const val CENTER = 8
        private const val NONE = -1
        private const val INNER_RATIO = 0.36f
        private const val GAP_DEGREES = 2.5f

        /*
         * Sector index -> direction, counter-clockwise from +X.
         */
        private val DIRECTIONS = arrayOf(
            JogDirection.EAST,
            JogDirection.NORTH_EAST,
            JogDirection.NORTH,
            JogDirection.NORTH_WEST,
            JogDirection.WEST,
            JogDirection.SOUTH_WEST,
            JogDirection.SOUTH,
            JogDirection.SOUTH_EAST
        )

        private val LABELS = arrayOf("X+", null, "Y+", null, "X-", null, "Y-", null)
    }

    var onDirectionClick: ((JogDirection) -> Unit)? = null
    var onContinuousStart: ((JogDirection) -> Unit)? = null
    var onContinuousStop: (() -> Unit)? = null
    var onCenterClick: (() -> Unit)? = null

    private var pressed = NONE
    private var continuousActive = false

    private val sectorPaths = Array(8) { Path() }
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val arrowPath = Path()

    private var centerX = 0f
    private var centerY = 0f
    private var outerRadius = 0f
    private var innerRadius = 0f

    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private val colorSector = ContextCompat.getColor(context, R.color.ld_surface)
    private val colorDiagonal = ContextCompat.getColor(context, R.color.ld_bar)
    private val colorPressed = ContextCompat.getColor(context, R.color.ld_accent)
    private val colorDisabled = ContextCompat.getColor(context, R.color.ld_surface_disabled)
    private val colorBorder = ContextCompat.getColor(context, R.color.ld_border)
    private val colorText = ContextCompat.getColor(context, R.color.ld_text)
    private val colorTextDisabled = ContextCompat.getColor(context, R.color.ld_text_disabled)

    private val homeIcon: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_home)?.mutate()

    private val startContinuous = Runnable {
        val sector = pressed
        if (sector in 0..7 && isEnabled) {
            continuousActive = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onContinuousStart?.invoke(DIRECTIONS[sector])
        }
    }

    /*
     * ------------------------------------------------------------
     * Layout
     * ------------------------------------------------------------
     */

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val fallback = (280 * resources.displayMetrics.density).roundToInt()
        val size = when {
            widthMode == MeasureSpec.UNSPECIFIED && heightMode == MeasureSpec.UNSPECIFIED -> fallback
            widthMode == MeasureSpec.UNSPECIFIED -> height
            heightMode == MeasureSpec.UNSPECIFIED -> width
            else -> min(width, height)
        }
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val margin = 4f * resources.displayMetrics.density
        centerX = width / 2f
        centerY = height / 2f
        outerRadius = min(width, height) / 2f - margin
        innerRadius = outerRadius * INNER_RATIO
        outerRect.set(
            centerX - outerRadius, centerY - outerRadius,
            centerX + outerRadius, centerY + outerRadius
        )
        val innerEdge = innerRadius + margin
        innerRect.set(
            centerX - innerEdge, centerY - innerEdge,
            centerX + innerEdge, centerY + innerEdge
        )
        buildSectors()
    }

    /*
     * Canvas angles are clockwise, math angles counter-clockwise:
     * sector i is centered on -45 * i degrees.
     */
    private fun buildSectors() {
        for (index in 0 until 8) {
            val center = -45f * index
            val start = center - 22.5f + GAP_DEGREES
            val sweep = 45f - 2 * GAP_DEGREES
            sectorPaths[index].apply {
                reset()
                arcTo(outerRect, start, sweep, true)
                arcTo(innerRect, start + sweep, -sweep, false)
                close()
            }
        }
    }

    /*
     * ------------------------------------------------------------
     * Drawing
     * ------------------------------------------------------------
     */

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (outerRadius <= 0f) return

        val enabled = isEnabled
        ringPaint.color = colorBorder

        for (index in 0 until 8) {
            sectorPaint.color = when {
                !enabled -> colorDisabled
                pressed == index -> colorPressed
                index % 2 == 1 -> colorDiagonal
                else -> colorSector
            }
            canvas.drawPath(sectorPaths[index], sectorPaint)
            canvas.drawPath(sectorPaths[index], ringPaint)
            drawArrow(canvas, index, enabled)
        }

        // Center HOME button.
        sectorPaint.color = when {
            !enabled -> colorDisabled
            pressed == CENTER -> colorPressed
            else -> colorSector
        }
        canvas.drawCircle(centerX, centerY, innerRadius, sectorPaint)
        canvas.drawCircle(centerX, centerY, innerRadius, ringPaint)

        homeIcon?.let { icon ->
            val half = (innerRadius * 0.45f).roundToInt()
            icon.setTint(if (enabled) colorText else colorTextDisabled)
            icon.setBounds(
                (centerX - half).roundToInt(),
                (centerY - half - innerRadius * 0.12f).roundToInt(),
                (centerX + half).roundToInt(),
                (centerY + half - innerRadius * 0.12f).roundToInt()
            )
            icon.draw(canvas)
        }
        labelPaint.color = if (enabled) colorText else colorTextDisabled
        labelPaint.textSize = innerRadius * 0.26f
        canvas.drawText("X0 Y0", centerX, centerY + innerRadius * 0.62f, labelPaint)
    }

    private fun drawArrow(canvas: Canvas, index: Int, enabled: Boolean) {
        val angle = Math.toRadians(45.0 * index)
        val directionX = cos(angle).toFloat()
        val directionY = -sin(angle).toFloat()
        val middle = (outerRadius + innerRadius) / 2f
        val diagonal = index % 2 == 1
        val size = (outerRadius - innerRadius) * if (diagonal) 0.20f else 0.26f

        val tipX = centerX + directionX * (middle + size * 0.7f)
        val tipY = centerY + directionY * (middle + size * 0.7f)
        val baseX = centerX + directionX * (middle - size * 0.5f)
        val baseY = centerY + directionY * (middle - size * 0.5f)
        val normalX = -directionY * size * 0.75f
        val normalY = directionX * size * 0.75f

        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(baseX + normalX, baseY + normalY)
        arrowPath.lineTo(baseX - normalX, baseY - normalY)
        arrowPath.close()

        arrowPaint.color = when {
            !enabled -> colorTextDisabled
            pressed == index -> 0xFF000000.toInt()
            else -> colorText
        }
        canvas.drawPath(arrowPath, arrowPaint)

        val label = LABELS[index] ?: return
        labelPaint.color = arrowPaint.color
        labelPaint.textSize = (outerRadius - innerRadius) * 0.17f
        val labelRadius = middle - size * 1.25f
        canvas.drawText(
            label,
            centerX + directionX * labelRadius,
            centerY + directionY * labelRadius + labelPaint.textSize * 0.35f,
            labelPaint
        )
    }

    /*
     * ------------------------------------------------------------
     * Touch
     * ------------------------------------------------------------
     */

    private fun zoneAt(x: Float, y: Float): Int {
        val dx = x - centerX
        val dy = centerY - y
        val distance = hypot(dx, dy)
        if (distance > outerRadius * 1.08f) return NONE
        if (distance <= innerRadius) return CENTER
        var degrees = Math.toDegrees(atan2(dy, dx).toDouble())
        if (degrees < 0) degrees += 360.0
        return (((degrees + 22.5) / 45.0).toInt()) % 8
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                release(cancelled = true)
            }
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val zone = zoneAt(event.x, event.y)
                if (zone == NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                pressed = zone
                continuousActive = false
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (zone != CENTER) {
                    postDelayed(
                        startContinuous,
                        ViewConfiguration.getLongPressTimeout().toLong()
                    )
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                release(cancelled = false)
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                release(cancelled = true)
                return true
            }
        }
        return true
    }

    private fun release(cancelled: Boolean) {
        removeCallbacks(startContinuous)
        val zone = pressed
        pressed = NONE
        invalidate()

        if (continuousActive) {
            continuousActive = false
            onContinuousStop?.invoke()
            return
        }
        if (cancelled) return

        when (zone) {
            CENTER -> onCenterClick?.invoke()
            in 0..7 -> onDirectionClick?.invoke(DIRECTIONS[zone])
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        // Never leave a continuous jog running.
        release(cancelled = true)
        super.onDetachedFromWindow()
    }
}
