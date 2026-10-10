package com.radiacode.ble.pro

import android.os.Bundle
import android.widget.LinearLayout
import com.radiacode.ble.device.AlarmUnits
import com.radiacode.ble.device.DeviceDataHub
import com.radiacode.ble.device.DeviceGateway
import com.radiacode.ble.device.ProSettings
import com.radiacode.ble.device.Vsfr
import com.radiacode.ble.pro.views.GaugeView
import kotlin.math.max

/**
 * Search mode: two large auto-ranging gauges for count rate and dose rate with
 * optional particle clicks, for walking around and locating a source.
 */
class SearchActivity : ProActivity() {
    private lateinit var cpsGauge: GaugeView
    private lateinit var doseGauge: GaugeView
    private lateinit var stats: android.widget.TextView
    private val clicks = ClickPlayer()

    private var cpsAvg = 0.0
    private var doseAvg = 0.0
    private var cpsMax = 0.0
    private var doseMax = 0.0
    private var samples = 0
    private val tau: Double get() = ProSettings.averagingSeconds(this).toDouble()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("Search", "Move slowly; watch the needle, not the arc")

        val controls = card(column)
        switchRow(controls, "Particle clicks", "One click every ${ProSettings.clickDivider(this)} pulse(s)", ProSettings.clicksEnabled(this)) {
            ProSettings.setClicksEnabled(this, it)
            if (it) clicks.start() else clicks.stop()
        }
        buttons(controls, 1, button("Reset max and average", Btn.SECONDARY) {
            cpsAvg = 0.0; doseAvg = 0.0; cpsMax = 0.0; doseMax = 0.0; samples = 0
        })

        cpsGauge = GaugeView(this).apply {
            title = "Count rate"
            unit = "cps"
            accent = cMagenta
            baseScale = 1.0
            rangeLabels = listOf("x1", "x10", "x100", "x1000")
        }
        doseGauge = GaugeView(this).apply {
            title = "Dose rate"
            unit = "nSv/h"
            accent = cCyan
            baseScale = 10.0
            rangeLabels = listOf("x10 nSv/h", "x100 nSv/h", "x1 µSv/h", "x10 µSv/h")
        }
        applyThresholds()
        addFixed(column, cpsGauge, 300)
        addFixed(column, doseGauge, 300)
        stats = text("Waiting for readings...", 13f, cMuted, mono = true)
        card(column).addView(stats)

        // Readings come from the poll loop through the data hub; a 1 s tick also reports when
        // nothing arrives (device busy with the official app, out of range, service stopped).
        ui.post(tick)
    }

    private var lastSeen = 0L
    private val tick = object : Runnable {
        override fun run() {
            val t = DeviceDataHub.lastUpdateMs
            val age = System.currentTimeMillis() - t
            if (t > 0 && t != lastSeen && age < 5_000) {
                lastSeen = t
                onReading(DeviceDataHub.lastCps.toDouble(), DeviceDataHub.lastDoseRateUsvH * 1000.0)
            } else if (t == 0L || age >= 5_000) {
                stats.text = when {
                    !DeviceGateway.isServiceRunning -> "Connection service is not running. Open the dashboard to start it."
                    DeviceGateway.connectedDevices().isEmpty() -> "No device connected. The RadiaCode accepts one connection: close the official app."
                    else -> "No readings for ${age / 1000} s"
                }
                stats.setTextColor(cYellow)
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun applyThresholds() {
        val id = DeviceGateway.activeDeviceId(this) ?: return
        val regs = ProSettings.lastRegisters(this, id) ?: return
        regs.value(Vsfr.CR_LEV1_cp10s)?.let { cpsGauge.warn = AlarmUnits.cp10sToCps(it) }
        regs.value(Vsfr.CR_LEV2_cp10s)?.let { cpsGauge.alarm = AlarmUnits.cp10sToCps(it) }
        regs.value(Vsfr.DR_LEV1_uR_h)?.let { doseGauge.warn = AlarmUnits.urToUsv(it) * 1000.0 }
        regs.value(Vsfr.DR_LEV2_uR_h)?.let { doseGauge.alarm = AlarmUnits.urToUsv(it) * 1000.0 }
    }

    private fun onReading(cps: Double, nSvH: Double) {
        samples++
        val k = if (samples == 1) 1.0 else 1.0 / tau
        cpsAvg += (cps - cpsAvg) * k
        doseAvg += (nSvH - doseAvg) * k
        cpsMax = max(cpsMax, cps)
        doseMax = max(doseMax, nSvH)

        cpsGauge.valueText = Units.number(cps, 3)
        cpsGauge.set(cps, cpsAvg)
        val dr = Units.doseRate(this, nSvH / 1000.0)
        doseGauge.unit = dr.unit
        doseGauge.valueText = Units.number(dr.value, 3)
        doseGauge.set(nSvH, doseAvg)
        clicks.divider = ProSettings.clickDivider(this)
        clicks.volume = ProSettings.clicksVolume(this) / 100f
        clicks.setCountRate(cps)
        stats.setTextColor(cMuted)
        stats.text = "avg ${Units.number(cpsAvg)} cps   max ${Units.number(cpsMax)} cps\n" +
            "avg ${Units.doseRate(this, doseAvg / 1000.0).text()}   max ${Units.doseRate(this, doseMax / 1000.0).text()}"
    }

    override fun onResume() {
        super.onResume()
        if (ProSettings.clicksEnabled(this)) clicks.start()
    }

    override fun onPause() {
        super.onPause()
        clicks.stop()
    }
}
