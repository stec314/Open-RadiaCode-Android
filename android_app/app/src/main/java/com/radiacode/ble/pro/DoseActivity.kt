package com.radiacode.ble.pro

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.radiacode.ble.device.AlarmUnits
import com.radiacode.ble.device.DeviceDataHub
import com.radiacode.ble.device.DeviceGateway
import com.radiacode.ble.device.ProSettings
import com.radiacode.ble.device.Vsfr
import com.radiacode.ble.pro.views.TimeSeriesView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accumulated dose as counted by the device, with the device dose alarm levels,
 * average dose rate, a simple forecast and the dose history recorded by the app.
 */
class DoseActivity : ProActivity() {
    private lateinit var doseText: TextView
    private lateinit var detail: TextView
    private lateinit var bar: LinearLayout
    private lateinit var chart: TimeSeriesView
    private var rangeMs = 86_400_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("Dose", "Accumulated by the device since the last dose reset")

        val head = card(column)
        doseText = text("-", 34f, cGreen, bold = true, mono = true)
        head.addView(doseText)
        detail = text("Waiting for the device to report its dose...", 13f, cText2, mono = true)
        head.addView(detail)
        bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        head.addView(bar)

        val hist = card(column, "History", "Sampled once a minute while the app is connected.")
        segmented(hist, listOf("24 h", "7 days", "30 days", "All"), 0) {
            rangeMs = when (it) { 0 -> 86_400_000L; 1 -> 7 * 86_400_000L; 2 -> 30 * 86_400_000L; else -> Long.MAX_VALUE / 4 }
            loadHistory()
        }
        chart = TimeSeriesView(this).apply { color = cGreen; emptyText = "No dose history yet" }
        addFixed(hist, chart, 220)

        val act = card(column, "Actions")
        buttons(act, 2,
            button("Export history", Btn.SECONDARY) {
                val f = DeviceDataHub.doseHistoryFile(this)
                if (!f.exists()) toast("No history yet") else share(f, "text/csv", "Dose history")
            },
            button("Reset dose", Btn.DANGER) {
                confirm("Reset dose", "Set the accumulated dose on the device to zero? This cannot be undone.", "Reset") {
                    device(op = { it.resetDose() }) { ok ->
                        if (ok) { DeviceDataHub.logAppEvent(this, DeviceGateway.activeDeviceId(this), "Dose reset"); toast("Dose reset") }
                        else toast("Device rejected the reset")
                    }
                }
            })

        listen(DeviceDataHub.ACTION_DOSE_UPDATE) { refresh() }
        refresh()
        loadHistory()
    }

    private fun refresh() {
        val id = DeviceGateway.activeDeviceId(this)
        val st = DeviceDataHub.dose(id) ?: return
        val dose = Units.dose(this, st.doseUsv.toDouble())
        doseText.text = dose.text(3)
        val hours = st.durationSeconds / 3600.0
        val ader = if (hours > 0) st.doseUsv / hours else 0.0
        val current = DeviceDataHub.lastDoseRateUsvH.toDouble()
        detail.text = buildString {
            append("in ${Units.duration(st.durationSeconds)}\n")
            append("Average rate  ${Units.doseRate(this@DoseActivity, ader.toDouble()).text()}\n")
            append("At current rate: ${Units.dose(this@DoseActivity, current * 24).text()} per day, ")
            append("${Units.dose(this@DoseActivity, current * 24 * 365).text()} per year")
        }
        drawBar(id, st.doseUsv.toDouble())
    }

    private fun drawBar(id: String?, doseUsv: Double) {
        bar.removeAllViews()
        val regs = id?.let { ProSettings.lastRegisters(this, it) } ?: run {
            bar.addView(text("Open Device control once to show the device dose alarm levels.", 12f, cMuted).apply { setPadding(0, dp(8), 0, 0) })
            return
        }
        val l1 = regs.value(Vsfr.DS_LEV1_uR)?.let { AlarmUnits.urToUsv(it) } ?: return
        val l2 = regs.value(Vsfr.DS_LEV2_uR)?.let { AlarmUnits.urToUsv(it) } ?: return
        val max = maxOf(l2 * 1.15, doseUsv * 1.05, 1e-6)
        val v = object : android.view.View(this) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: android.graphics.Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                p.color = cBorder; c.drawRoundRect(0f, h * 0.3f, w, h * 0.7f, 6 * density, 6 * density, p)
                p.color = when { doseUsv >= l2 -> cRed; doseUsv >= l1 -> cYellow; else -> cGreen }
                c.drawRoundRect(0f, h * 0.3f, (w * (doseUsv / max)).toFloat().coerceAtLeast(2f), h * 0.7f, 6 * density, 6 * density, p)
                p.strokeWidth = 2 * density
                p.color = cYellow; val x1 = (w * (l1 / max)).toFloat(); c.drawLine(x1, 0f, x1, h, p)
                p.color = cRed; val x2 = (w * (l2 / max)).toFloat(); c.drawLine(x2, 0f, x2, h, p)
            }
        }
        addFixed(bar, v, 26)
        bar.addView(text("Alarm 1 ${Units.dose(this, l1).text()}    Alarm 2 ${Units.dose(this, l2).text()}", 12f, cMuted, mono = true))
    }

    private fun loadHistory() {
        val id = DeviceGateway.activeDeviceId(this)
        val since = System.currentTimeMillis() - rangeMs
        worker.execute {
            val samples = DeviceDataHub.readDoseHistory(this, id, since)
            ui.post {
                val t = LongArray(samples.size) { samples[it].timestampMs }
                val v = DoubleArray(samples.size) { samples[it].doseUsv.toDouble() }
                chart.formatter = { Units.dose(this, it).text(2) }
                chart.set(t, v)
            }
        }
    }

    @Suppress("unused")
    private fun stamp(ms: Long) = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ms))
}
