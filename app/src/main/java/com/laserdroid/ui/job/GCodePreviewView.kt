package com.laserdroid.ui.job

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.laserdroid.R
import com.laserdroid.core.gcode.GCodeAnalysis
import com.laserdroid.core.gcode.GCodePreviewPath
import com.laserdroid.core.gcode.SegmentType

/**
 * Lightweight G-code preview.
 *
 * - Rapid / travel / engraving segments in distinct colors.
 * - Executed part (up to the current line) highlighted.
 * - Start point and current head position.
 * - Pinch to zoom, drag to pan, double tap to fit.
 *
 * Segments are grouped by type once per program, so a frame costs
 * at most six drawLines() calls whatever the program size. Lines are
 * hairlines (stroke width 0), unaffected by the zoom matrix.
 */
class GCodePreviewView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
    defaultStyleAttribute: Int = 0
) : View(context, attributes, defaultStyleAttribute) {

    private class TypedSegments(
        val points: FloatArray,
        val lineNumbers: IntArray
    ) {
        val count: Int get() = lineNumbers.size

        /** Number of segments whose line is <= [line]. */
        fun executedCount(line: Long): Int {
            var low = 0
            var high = count
            while (low < high) {
                val middle = (low + high) ushr 1
                if (lineNumbers[middle] <= line) {
                    low = middle + 1
                } else {
                    high = middle
                }
            }
            return low
        }
    }

    private var rapid: TypedSegments? = null
    private var travel: TypedSegments? = null
    private var work: TypedSegments? = null

    private var minX = 0f
    private var minY = 0f
    private var maxX = 1f
    private var maxY = 1f
    private var startX: Float? = null
    private var startY: Float? = null
    private var machineWidth: Float? = null
    private var machineHeight: Float? = null

    private var executedLine: Long = 0L
    private var headX: Float? = null
    private var headY: Float? = null

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val baseMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val pointBuffer = FloatArray(2)

    /* Colors follow the light / dark theme (values / values-night). */
    private fun themeColor(id: Int): Int = ContextCompat.getColor(context, id)

    private val rapidPaint = hairline(themeColor(R.color.ld_preview_rapid))
    private val travelPaint = hairline(themeColor(R.color.ld_preview_travel))
    private val workPaint = hairline(themeColor(R.color.ld_preview_work))
    private val donePaint = hairline(themeColor(R.color.ld_preview_done))
    private val machinePaint = hairline(themeColor(R.color.ld_preview_machine))
    private val axisPaint = hairline(themeColor(R.color.ld_preview_axis))

    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.ld_preview_head)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.ld_preview_done)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.color.ld_text_secondary)
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    private fun hairline(value: Int): Paint {
        return Paint().apply {
            color = value
            strokeWidth = 0f
            style = Paint.Style.STROKE
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val previous = zoom
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 50f)
                val factor = zoom / previous
                panX = detector.focusX - (detector.focusX - panX) * factor
                panY = detector.focusY - (detector.focusY - panY) * factor
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                panX -= distanceX
                panY -= distanceY
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetView()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        }
    )

    init {
        setBackgroundColor(themeColor(R.color.ld_preview_background))
    }

    /*
     * ------------------------------------------------------------
     * Public API
     * ------------------------------------------------------------
     */

    fun setProgram(
        analysis: GCodeAnalysis?,
        machineTravelX: Double?,
        machineTravelY: Double?
    ) {
        machineWidth = machineTravelX?.toFloat()
        machineHeight = machineTravelY?.toFloat()

        if (analysis == null) {
            rapid = null
            travel = null
            work = null
            startX = null
            startY = null
            executedLine = 0L
            computeBaseMatrix()
            invalidate()
            return
        }

        val preview = analysis.preview
        rapid = extract(preview, SegmentType.RAPID)
        travel = extract(preview, SegmentType.TRAVEL)
        work = extract(preview, SegmentType.WORK)

        val bounds = analysis.totalBounds
        minX = minOf(bounds?.minX?.toFloat() ?: 0f, 0f)
        minY = minOf(bounds?.minY?.toFloat() ?: 0f, 0f)
        maxX = maxOf(bounds?.maxX?.toFloat() ?: 1f, minX + 1f)
        maxY = maxOf(bounds?.maxY?.toFloat() ?: 1f, minY + 1f)
        startX = analysis.startX?.toFloat()
        startY = analysis.startY?.toFloat()
        executedLine = 0L

        resetView()
    }

    /** Segments up to [line] are drawn as executed. */
    fun setExecutedLine(line: Long) {
        if (line != executedLine) {
            executedLine = line
            invalidate()
        }
    }

    /** Head position in work coordinates, or null to hide it. */
    fun setHeadPosition(x: Double?, y: Double?) {
        val newX = x?.toFloat()
        val newY = y?.toFloat()
        if (newX != headX || newY != headY) {
            headX = newX
            headY = newY
            invalidate()
        }
    }

    fun resetView() {
        zoom = 1f
        panX = 0f
        panY = 0f
        computeBaseMatrix()
        invalidate()
    }

    /*
     * ------------------------------------------------------------
     * Geometry
     * ------------------------------------------------------------
     */

    private fun extract(preview: GCodePreviewPath, type: Byte): TypedSegments {
        var count = 0
        for (index in 0 until preview.segmentCount) {
            if (preview.types[index] == type) count++
        }
        val points = FloatArray(count * 4)
        val lines = IntArray(count)
        var target = 0
        for (index in 0 until preview.segmentCount) {
            if (preview.types[index] != type) continue
            System.arraycopy(preview.coordinates, index * 4, points, target * 4, 4)
            lines[target] = preview.lineNumbers[index]
            target++
        }
        return TypedSegments(points, lines)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        computeBaseMatrix()
    }

    /*
     * Fit the program (and the origin) in the view, Y axis upwards.
     */
    private fun computeBaseMatrix() {
        val margin = 12f * resources.displayMetrics.density
        val availableWidth = (width - 2 * margin).coerceAtLeast(1f)
        val availableHeight = (height - 2 * margin).coerceAtLeast(1f)
        val spanX = (maxX - minX).coerceAtLeast(0.001f)
        val spanY = (maxY - minY).coerceAtLeast(0.001f)
        val scale = minOf(availableWidth / spanX, availableHeight / spanY)
        val offsetX = margin + (availableWidth - spanX * scale) / 2f
        val offsetY = margin + (availableHeight - spanY * scale) / 2f

        baseMatrix.reset()
        baseMatrix.postTranslate(-minX, -maxY)
        baseMatrix.postScale(scale, -scale)
        baseMatrix.postTranslate(offsetX, offsetY)
    }

    private fun mapPoint(x: Float, y: Float): FloatArray {
        pointBuffer[0] = x
        pointBuffer[1] = y
        drawMatrix.mapPoints(pointBuffer)
        return pointBuffer
    }

    /*
     * ------------------------------------------------------------
     * Drawing
     * ------------------------------------------------------------
     */

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawMatrix.set(baseMatrix)
        drawMatrix.postScale(zoom, zoom)
        drawMatrix.postTranslate(panX, panY)

        if (rapid == null && travel == null && work == null) {
            canvas.drawText("No program", 16f, height / 2f, textPaint)
            return
        }

        canvas.save()
        canvas.concat(drawMatrix)

        drawMachineArea(canvas)
        drawGroup(canvas, rapid, rapidPaint)
        drawGroup(canvas, travel, travelPaint)
        drawGroup(canvas, work, workPaint)

        canvas.restore()

        drawMarkers(canvas)
    }

    private fun drawMachineArea(canvas: Canvas) {
        val machineX = machineWidth
        val machineY = machineHeight
        if (machineX != null && machineY != null) {
            canvas.drawRect(0f, 0f, machineX, machineY, machinePaint)
        }
        val length = maxOf(maxX - minX, maxY - minY) * 0.05f
        canvas.drawLine(0f, 0f, length, 0f, axisPaint)
        canvas.drawLine(0f, 0f, 0f, length, axisPaint)
    }

    private fun drawGroup(canvas: Canvas, group: TypedSegments?, paint: Paint) {
        if (group == null || group.count == 0) return
        val executed = if (executedLine > 0L) group.executedCount(executedLine) else 0
        if (executed > 0) {
            canvas.drawLines(group.points, 0, executed * 4, donePaint)
        }
        val remaining = group.count - executed
        if (remaining > 0) {
            canvas.drawLines(group.points, executed * 4, remaining * 4, paint)
        }
    }

    private fun drawMarkers(canvas: Canvas) {
        val density = resources.displayMetrics.density

        val sx = startX
        val sy = startY
        if (sx != null && sy != null) {
            val point = mapPoint(sx, sy)
            canvas.drawCircle(point[0], point[1], 4f * density, startPaint)
        }

        val hx = headX
        val hy = headY
        if (hx != null && hy != null) {
            val point = mapPoint(hx, hy)
            val size = 8f * density
            canvas.drawLine(point[0] - size, point[1], point[0] + size, point[1], headPaint)
            canvas.drawLine(point[0], point[1] - size, point[0], point[1] + size, headPaint)
        }
    }

    /*
     * ------------------------------------------------------------
     * Touch
     * ------------------------------------------------------------
     */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
}
