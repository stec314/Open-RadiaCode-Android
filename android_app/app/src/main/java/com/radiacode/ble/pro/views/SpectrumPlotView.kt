package com.radiacode.ble.pro.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Gamma spectrum plot: counts against energy with log or linear scale, optional
 * background overlay, library line markers and found peaks. Pinch to zoom the energy
 * axis, drag to pan, tap for a cursor readout, double tap to reset the view.
 */
class SpectrumPlotView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity

    data class Marker(val energyKeV: Double, val label: String, val color: Int)

    var values: DoubleArray = DoubleArray(0)
    var background: DoubleArray? = null
    var a0 = 0.0
    var a1 = 3.0
    var a2 = 0.0
    var logScale = true
    var lineColor: Int = Color.parseColor("#69F0AE")
    var fillColor: Int = Color.parseColor("#3369F0AE")
    var markers: List<Marker> = emptyList()
    var peaks: List<Double> = emptyList()
    var onCursor: ((channel: Int, energy: Double, value: Double) -> Unit)? = null

    private var viewStart = 0.0
    private var viewEnd = 1024.0
    private var cursor: Int = -1

    private val plot = RectF()
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; strokeJoin = Paint.Join.ROUND }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = Color.parseColor("#B0FFB74D") }
    private val pGrid = Paint().apply { color = Color.parseColor("#26FFFFFF"); strokeWidth = 1f }
    private val pAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9E9EA8"); textSize = 10f * sd }
    private val pMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.2f * d }
    private val pPeak = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF5252"); style = Paint.Style.FILL }
    private val pCursor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 1f * d; pathEffect = DashPathEffect(floatArrayOf(6f * d, 4f * d), 0f) }
    private val pPanel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0D0D0F") }

    fun setData(values: DoubleArray, a0: Double, a1: Double, a2: Double, resetView: Boolean = false) {
        val sizeChanged = values.size != this.values.size
        this.values = values
        this.a0 = a0; this.a1 = a1; this.a2 = a2
        if (resetView || sizeChanged) { viewStart = 0.0; viewEnd = values.size.toDouble() }
        invalidate()
    }

    fun energy(ch: Double) = a0 + a1 * ch + a2 * ch * ch

    fun channel(e: Double): Double {
        if (kotlin.math.abs(a2) < 1e-12) return (e - a0) / a1
        val disc = a1 * a1 - 4 * a2 * (a0 - e)
        return if (disc < 0) (e - a0) / a1 else (-a1 + kotlin.math.sqrt(disc)) / (2 * a2)
    }

    private fun xOf(ch: Double) = plot.left + ((ch - viewStart) / (viewEnd - viewStart) * plot.width()).toFloat()
    private fun chOf(x: Float) = viewStart + (x - plot.left) / plot.width() * (viewEnd - viewStart)

    private var yMin = 0.0
    private var yMax = 1.0
    private fun yOf(v: Double): Float {
        val frac = if (logScale) {
            val lv = log10(max(v, 0.5))
            (lv - yMin) / (yMax - yMin)
        } else (v - yMin) / (yMax - yMin)
        return plot.bottom - (frac.coerceIn(-0.05, 1.05) * plot.height()).toFloat()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(c: Canvas) {
        plot.set(44f * d, 8f * d, width - 8f * d, height - 22f * d)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pPanel)
        val n = values.size
        if (n < 2) {
            pAxis.textAlign = Paint.Align.CENTER
            c.drawText("No spectrum yet", width / 2f, height / 2f, pAxis)
            return
        }
        val s = max(0, floor(viewStart).toInt())
        val e = min(n - 1, kotlin.math.ceil(viewEnd).toInt())

        // y range from visible data
        var vmax = 1.0
        for (i in s..e) vmax = max(vmax, values[i])
        background?.let { b -> for (i in s..min(e, b.size - 1)) vmax = max(vmax, b[i]) }
        if (logScale) { yMin = log10(0.5); yMax = log10(vmax * 1.6) } else { yMin = 0.0; yMax = vmax * 1.08 }

        // grid + y labels
        pAxis.textAlign = Paint.Align.RIGHT
        if (logScale) {
            var dec = 0
            while (10.0.pow(dec) <= vmax * 1.6) {
                val y = yOf(10.0.pow(dec))
                c.drawLine(plot.left, y, plot.right, y, pGrid)
                c.drawText(compact(10.0.pow(dec)), plot.left - 4 * d, y + 3 * d, pAxis)
                dec++
            }
        } else {
            for (k in 0..4) {
                val v = vmax * k / 4.0
                val y = yOf(v)
                c.drawLine(plot.left, y, plot.right, y, pGrid)
                c.drawText(compact(v), plot.left - 4 * d, y + 3 * d, pAxis)
            }
        }
        // x labels in keV, at round energies
        val eLo = energy(viewStart)
        val eHi = energy(viewEnd)
        val step = niceStep((eHi - eLo) / 5.0)
        pAxis.textAlign = Paint.Align.CENTER
        var ev = kotlin.math.ceil(eLo / step) * step
        while (ev <= eHi) {
            val x = xOf(channel(ev))
            if (x >= plot.left && x <= plot.right) {
                c.drawLine(x, plot.top, x, plot.bottom, pGrid)
                c.drawText(String.format(Locale.US, "%.0f", ev), x, plot.bottom + 14 * d, pAxis)
            }
            ev += step
        }
        pAxis.textAlign = Paint.Align.RIGHT
        c.drawText("keV", plot.right, plot.bottom + 14 * d, pAxis)

        c.save()
        c.clipRect(plot)
        // background overlay
        background?.let { b ->
            val path = Path()
            for (i in s..min(e, b.size - 1)) {
                val x = xOf(i.toDouble()); val y = yOf(b[i])
                if (i == s) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, pBg)
        }
        // spectrum
        val line = Path()
        val fill = Path()
        fill.moveTo(xOf(s.toDouble()), plot.bottom)
        for (i in s..e) {
            val x = xOf(i.toDouble()); val y = yOf(values[i])
            if (i == s) line.moveTo(x, y) else line.lineTo(x, y)
            fill.lineTo(x, y)
        }
        fill.lineTo(xOf(e.toDouble()), plot.bottom)
        fill.close()
        pFill.color = fillColor
        c.drawPath(fill, pFill)
        pLine.color = lineColor
        c.drawPath(line, pLine)

        // library markers
        pAxis.textAlign = Paint.Align.LEFT
        markers.forEachIndexed { idx, m ->
            val x = xOf(channel(m.energyKeV))
            if (x < plot.left || x > plot.right) return@forEachIndexed
            pMark.color = m.color
            c.drawLine(x, plot.top, x, plot.bottom, pMark)
            pAxis.color = m.color
            // labels staggered over four rows so neighbouring lines stay readable
            c.drawText(m.label, x + 3 * d, plot.top + 22 * d + (idx % 4) * 12 * d, pAxis)
        }
        pAxis.color = Color.parseColor("#9E9EA8")
        // found peaks
        for (pe in peaks) {
            val ch = channel(pe)
            val i = ch.toInt().coerceIn(0, n - 1)
            val x = xOf(ch)
            if (x < plot.left || x > plot.right) continue
            c.drawCircle(x, yOf(values[i]) - 6 * d, 3.5f * d, pPeak)
        }
        // cursor
        if (cursor in 0 until n) {
            val x = xOf(cursor.toDouble())
            c.drawLine(x, plot.top, x, plot.bottom, pCursor)
            val label = String.format(Locale.US, "%.0f keV  ch %d  %s", energy(cursor.toDouble()), cursor, compact(values[cursor]))
            pAxis.textAlign = if (x > plot.centerX()) Paint.Align.RIGHT else Paint.Align.LEFT
            pAxis.color = Color.WHITE
            c.drawText(label, if (x > plot.centerX()) x - 6 * d else x + 6 * d, plot.top + 14 * d, pAxis)
            pAxis.color = Color.parseColor("#9E9EA8")
        }
        c.restore()
    }

    private fun compact(v: Double): String = when {
        v >= 1e6 -> String.format(Locale.US, "%.1fM", v / 1e6)
        v >= 1e3 -> String.format(Locale.US, "%.1fk", v / 1e3)
        v >= 10 -> String.format(Locale.US, "%.0f", v)
        else -> String.format(Locale.US, "%.1f", v)
    }

    private fun niceStep(raw: Double): Double {
        if (raw <= 0) return 100.0
        val p = 10.0.pow(floor(log10(raw)))
        val f = raw / p
        return p * when { f < 1.5 -> 1.0; f < 3.5 -> 2.0; f < 7.5 -> 5.0; else -> 10.0 }
    }

    // ---- gestures --------------------------------------------------------------------------------
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val focus = chOf(det.focusX)
            val span = (viewEnd - viewStart) / det.scaleFactor
            val clamped = span.coerceIn(16.0, values.size.toDouble().coerceAtLeast(16.0))
            val frac = (focus - viewStart) / (viewEnd - viewStart)
            viewStart = focus - frac * clamped
            viewEnd = viewStart + clamped
            clampView()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val shift = dx / plot.width() * (viewEnd - viewStart)
            viewStart += shift; viewEnd += shift
            clampView()
            invalidate()
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            cursor = chOf(e.x).toInt().coerceIn(0, max(0, values.size - 1))
            if (values.isNotEmpty()) onCursor?.invoke(cursor, energy(cursor.toDouble()), values[cursor])
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            viewStart = 0.0; viewEnd = values.size.toDouble(); cursor = -1
            invalidate()
            return true
        }
    })

    private fun clampView() {
        val n = values.size.toDouble()
        val span = viewEnd - viewStart
        if (viewStart < 0) { viewStart = 0.0; viewEnd = span }
        if (viewEnd > n) { viewEnd = n; viewStart = max(0.0, n - span) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }
}
