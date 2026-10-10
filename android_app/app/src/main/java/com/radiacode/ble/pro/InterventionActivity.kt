package com.radiacode.ble.pro

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.radiacode.ble.RadiaCodeForegroundService
import com.radiacode.ble.device.DeviceDataHub
import com.radiacode.ble.device.DeviceGateway
import com.radiacode.ble.device.ProSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Intervention mode: a dose budget for a task, the dose used so far, the remaining
 * stay time at the current dose rate, budget alarms at 50, 80 and 100 percent,
 * and an exportable log sampled every 30 seconds.
 *
 * Stay time assumes the current dose rate stays constant. It is a planning aid,
 * not a substitute for an operational dosimeter or a radiation protection officer.
 */
class InterventionActivity : ProActivity() {
    private lateinit var big: TextView
    private lateinit var stay: TextView
    private lateinit var detail: TextView
    private lateinit var barHolder: LinearLayout
    private lateinit var setup: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("Intervention", "Dose budget and stay time")

        val head = card(column)
        big = text("-", 32f, cText, bold = true, mono = true)
        stay = text("", 20f, cCyan, bold = true, mono = true)
        detail = text("", 13f, cText2, mono = true)
        head.addView(big); head.addView(stay); head.addView(detail)
        barHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        head.addView(barHolder)

        setup = card(column, "Mission")
        buildSetup()

        card(column, null, "Stay time assumes the dose rate stays as it is now. It does not replace an operational dosimeter or the instructions of the radiation protection officer.")

        listen(RadiaCodeForegroundService.ACTION_READING, DeviceDataHub.ACTION_DOSE_UPDATE) { refresh() }
        refresh()
    }

    private fun buildSetup() {
        setup.removeAllViews()
        setup.addView(text("MISSION", 11f, cMuted, bold = true))
        val active = ProSettings.missionActive(this)
        if (!active) {
            val name: EditText = textField("", "Name or location")
            setup.addView(name)
            val budget = numberField(Units.number(ProSettings.doseBudgetUsv(this).toDouble(), 4), widthDp = 120)
            row(setup, "Dose budget", "Microsieverts for this task", LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(budget); addView(text(" uSv", 13f, cMuted))
            })
            buttons(setup, 1, button("Start mission", Btn.PRIMARY) {
                val b = budget.text.toString().replace(',', '.').toFloatOrNull()
                if (b == null || b <= 0f) { toast("Enter a dose budget"); return@button }
                if (DeviceGateway.activeDeviceId(this) == null) { toast("Connect a device first"); return@button }
                ProSettings.setDoseBudgetUsv(this, b)
                val n = name.text.toString().ifBlank { "Mission " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()) }
                ProSettings.startMission(this, n, DeviceDataHub.dose(DeviceGateway.activeDeviceId(this))?.doseUsv ?: 0f)
                DeviceDataHub.logAppEvent(this, DeviceGateway.activeDeviceId(this), "Mission started", "$n, budget $b uSv")
                buildSetup(); refresh()
            })
        } else {
            setup.addView(text(ProSettings.missionName(this), 16f, cText, bold = true))
            setup.addView(text("Started ${SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(ProSettings.missionStartMs(this)))}", 12f, cMuted))
            buttons(setup, 2,
                button("Export log", Btn.SECONDARY) {
                    val f = DeviceDataHub.missionLogFile(this)
                    if (!f.exists()) toast("No samples yet") else share(f, "text/csv", "Mission log")
                },
                button("End mission", Btn.DANGER) {
                    confirm("End mission", "Stop tracking this mission?", "End") {
                        DeviceDataHub.logAppEvent(this, DeviceGateway.activeDeviceId(this), "Mission ended",
                            String.format(Locale.US, "%s, %.2f uSv", ProSettings.missionName(this), ProSettings.missionDoseUsv(this)))
                        ProSettings.stopMission(this)
                        buildSetup(); refresh()
                    }
                })
        }
    }

    private fun refresh() {
        val rate = DeviceDataHub.lastDoseRateUsvH.toDouble()
        if (!ProSettings.missionActive(this)) {
            big.text = Units.doseRate(this, rate).text()
            big.setTextColor(cText)
            stay.text = "No active mission"
            detail.text = "Set a budget and start a mission to track dose and stay time."
            barHolder.removeAllViews()
            return
        }
        val used = ProSettings.missionDoseUsv(this).toDouble()
        val budget = ProSettings.doseBudgetUsv(this).toDouble()
        val left = (budget - used).coerceAtLeast(0.0)
        val frac = if (budget > 0) used / budget else 0.0
        big.text = "${Units.dose(this, used).text()} / ${Units.dose(this, budget).text()}"
        big.setTextColor(when { frac >= 1 -> cRed; frac >= 0.8 -> cAmber; frac >= 0.5 -> cYellow; else -> cGreen })
        stay.text = when {
            left <= 0.0 -> "Budget used: leave the area"
            rate <= 0.0 -> "Stay time: no reading"
            else -> "Stay time  ${Units.duration((left / rate * 3600).toLong())}"
        }
        stay.setTextColor(if (left <= 0.0) cRed else cCyan)
        val elapsed = (System.currentTimeMillis() - ProSettings.missionStartMs(this)) / 1000
        detail.text = "Now ${Units.doseRate(this, rate).text()}   max ${Units.doseRate(this, ProSettings.missionMaxRate(this).toDouble()).text()}\n" +
            "Elapsed ${Units.duration(elapsed)}   remaining ${Units.dose(this, left).text()}"
        barHolder.removeAllViews()
        val v = object : android.view.View(this) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: android.graphics.Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                p.color = cBorder; c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
                p.color = when { frac >= 1 -> cRed; frac >= 0.8 -> cAmber; frac >= 0.5 -> cYellow; else -> cGreen }
                c.drawRoundRect(0f, 0f, (w * frac.coerceIn(0.0, 1.0)).toFloat().coerceAtLeast(h), h, h / 2, h / 2, p)
            }
        }
        addFixed(barHolder, v, 14)
    }
}
