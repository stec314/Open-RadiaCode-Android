package com.radiacode.ble.pro.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Minimal time series plot with horizontal threshold lines. */
class TimeSeriesView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity

    data class Threshold(val value: Double, val label: String, val color: Int)

    var times: LongArray = LongArray(0)
    var values: DoubleArray = DoubleArray(0)
    var thresholds: List<Threshold> = emptyList()
    var color: Int = Color.parseColor("#00E5FF")
    var formatter: (Double) -> String = { String.format(Locale.US, "%.3g", it) }
    var emptyText: String = "No data yet"

    private val plot = RectF()
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pGrid = Paint().apply { color = Color.parseColor("#22FFFFFF") }
    private val pAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9E9EA8"); textSize = 10f * sd }
    private val pThr = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.2f * d; pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f) }
    private val pBg = Paint().apply { color = Color.parseColor("#1A1A1E") }

    fun set(t: LongArray, v: DoubleArray) {
        times = t; values = v; invalidate()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(c: Canvas) {
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 12 * d, 12 * d, pBg)
        plot.set(52 * d, 10 * d, width - 10 * d, height - 22 * d)
        if (values.size < 2) {
            pAxis.textAlign = Paint.Align.CENTER
            c.drawText(emptyText, width / 2f, height / 2f, pAxis)
            return
        }
        var vmax = values.maxOrNull() ?: 1.0
        thresholds.forEach { if (it.value < vmax * 4) vmax = max(vmax, it.value) }
        vmax = max(vmax * 1.1, 1e-9)
        val vmin = 0.0
        val t0 = times.first()
        val t1 = max(times.last(), t0 + 1)
        fun x(t: Long) = plot.left + ((t - t0).toDouble() / (t1 - t0) * plot.width()).toFloat()
        fun y(v: Double) = plot.bottom - ((v - vmin) / (vmax - vmin) * plot.height()).toFloat()

        pAxis.textAlign = Paint.Align.RIGHT
        for (k in 0..4) {
            val v = vmax * k / 4
            val yy = y(v)
            c.drawLine(plot.left, yy, plot.right, yy, pGrid)
            c.drawText(formatter(v), plot.left - 4 * d, yy + 3 * d, pAxis)
        }
        val span = t1 - t0
        val fmt = SimpleDateFormat(if (span > 2 * 86_400_000L) "dd/MM" else "HH:mm", Locale.getDefault())
        for (k in 0..3) {
            val t = t0 + span * k / 3
            pAxis.textAlign = when (k) { 0 -> Paint.Align.LEFT; 3 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            c.drawText(fmt.format(Date(t)), x(t), plot.bottom + 14 * d, pAxis)
        }

        val line = Path()
        val fill = Path()
        for (i in values.indices) {
            val xx = x(times[i]); val yy = y(values[i])
            if (i == 0) { line.moveTo(xx, yy); fill.moveTo(xx, plot.bottom); fill.lineTo(xx, yy) } else { line.lineTo(xx, yy); fill.lineTo(xx, yy) }
        }
        fill.lineTo(x(times.last()), plot.bottom); fill.close()
        pFill.color = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
        c.drawPath(fill, pFill)
        pLine.color = color
        c.drawPath(line, pLine)

        pAxis.textAlign = Paint.Align.LEFT
        for (t in thresholds) {
            if (t.value > vmax) continue
            pThr.color = t.color
            val yy = y(t.value)
            c.drawLine(plot.left, yy, plot.right, yy, pThr)
            pAxis.color = t.color
            c.drawText(t.label, plot.left + 4 * d, yy - 3 * d, pAxis)
        }
        pAxis.color = Color.parseColor("#9E9EA8")
    }
}
