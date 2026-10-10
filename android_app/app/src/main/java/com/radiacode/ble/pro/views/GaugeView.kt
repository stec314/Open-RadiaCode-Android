package com.radiacode.ble.pro.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Search-mode gauge: a 0..100 dial with automatic decade ranges (x1, x10, x100, ...),
 * a fast needle for the current value, a slow arc for the averaged value, and a
 * colored scale that turns yellow and red above the alarm thresholds.
 */
class GaugeView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity

    var title: String = ""
    var unit: String = ""
    var accent: Int = Color.parseColor("#00E5FF")
    /** Displayed value text (already formatted by the caller). */
    var valueText: String = "-"
    var rangeLabels: List<String> = listOf("x1", "x10", "x100", "x1000")
    /** Thresholds in the same base unit as [value]; 0 = none. */
    var warn: Double = 0.0
    var alarm: Double = 0.0

    private var value = 0.0
    private var average = 0.0
    private var decade = 0
    /** Smallest decade multiplier; e.g. 10.0 when the scale starts at x10 nSv/h. */
    var baseScale: Double = 1.0

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9E9EA8"); textAlign = Paint.Align.CENTER }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A1E") }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2A2E"); style = Paint.Style.STROKE }

    fun set(value: Double, average: Double) {
        this.value = max(0.0, value)
        this.average = max(0.0, average)
        val ref = max(this.value, this.average)
        // Choose the decade so the reading sits below 100 on the dial
        var dec = 0
        while (dec < rangeLabels.size - 1 && ref > 100.0 * baseScale * 10.0.pow(dec)) dec++
        decade = dec
        invalidate()
    }

    private fun scaleMax() = 100.0 * baseScale * 10.0.pow(decade)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 16 * d
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, bg)
        border.strokeWidth = d
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, border)

        txt.textAlign = Paint.Align.LEFT
        txt.textSize = 14 * sd
        txt.color = Color.WHITE
        txt.typeface = Typeface.DEFAULT_BOLD
        c.drawText(title, 14 * d, 26 * d, txt)

        // Range indicator on the right
        txt.typeface = Typeface.DEFAULT
        txt.textAlign = Paint.Align.RIGHT
        rangeLabels.forEachIndexed { i, l ->
            val on = i == decade
            txt.textSize = (if (on) 14 else 11) * sd
            txt.color = if (on) Color.WHITE else Color.parseColor("#55555C")
            c.drawText(l, w - 14 * d, 24 * d + i * 15 * d, txt)
        }

        val cx = w / 2f
        val radius = minOf(w * 0.40f, h * 0.48f)
        val cy = h * 0.58f
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val start = 150f
        val sweep = 240f
        val sm = scaleMax()

        // Colored scale bands
        arc.strokeWidth = 6 * d
        fun band(from: Double, to: Double, color: Int) {
            if (to <= from) return
            arc.color = color
            c.drawArc(oval, start + (sweep * (from / sm)).toFloat(), (sweep * ((to - from) / sm)).toFloat(), false, arc)
        }
        val w1 = if (warn > 0) minOf(warn, sm) else sm
        val a1 = if (alarm > 0) minOf(alarm, sm) else sm
        band(0.0, w1, Color.parseColor("#2E7D4F"))
        band(w1, a1, Color.parseColor("#8A7A1F"))
        band(a1, sm, Color.parseColor("#8A2A2A"))

        // Ticks and labels 0..100
        txt.textAlign = Paint.Align.CENTER
        txt.color = Color.parseColor("#C8C8D0")
        txt.textSize = 11 * sd
        for (i in 0..50) {
            val a = Math.toRadians((start + sweep * i / 50f).toDouble())
            val major = i % 5 == 0
            val r1 = radius - (if (major) 14 else 8) * d
            tick.strokeWidth = (if (major) 2f else 1f) * d
            tick.color = if (major) Color.parseColor("#E0E0E6") else Color.parseColor("#6E6E78")
            c.drawLine(cx + (radius * cos(a)).toFloat(), cy + (radius * sin(a)).toFloat(),
                cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(), tick)
            if (major) {
                val rl = radius - 26 * d
                c.drawText("${i * 2}", cx + (rl * cos(a)).toFloat(), cy + (rl * sin(a)).toFloat() + 4 * d, txt)
            }
        }

        // Averaged value arc
        arc.strokeWidth = 10 * d
        arc.color = Color.argb(160, Color.red(accent), Color.green(accent), Color.blue(accent))
        val avgSweep = (sweep * (minOf(average, sm) / sm)).toFloat()
        val inner = RectF(oval.left - 10 * d, oval.top - 10 * d, oval.right + 10 * d, oval.bottom + 10 * d)
        if (avgSweep > 0.5f) c.drawArc(inner, start, avgSweep, false, arc)

        // Needle
        val va = Math.toRadians((start + sweep * (minOf(value, sm) / sm)).toDouble())
        needle.color = Color.parseColor("#FF5252")
        needle.strokeWidth = 4 * d
        c.drawLine(cx, cy, cx + ((radius - 18 * d) * cos(va)).toFloat(), cy + ((radius - 18 * d) * sin(va)).toFloat(), needle)
        c.drawCircle(cx, cy, 5 * d, needle.apply { style = Paint.Style.FILL })
        needle.style = Paint.Style.STROKE

        // Value
        big.textSize = 40 * sd
        val tw = big.measureText(valueText)
        if (tw > radius * 1.6f) big.textSize *= radius * 1.6f / tw
        c.drawText(valueText, cx, cy - radius * 0.12f, big)
        txt.textSize = 14 * sd
        txt.color = Color.parseColor("#9E9EA8")
        c.drawText(unit, cx, cy + radius * 0.55f, txt)
    }

    companion object {
        /** Decade index helper for tests and callers. */
        fun decadeFor(value: Double, base: Double): Int = if (value <= 100 * base) 0 else (log10(value / (100 * base))).toInt() + 1
    }
}
