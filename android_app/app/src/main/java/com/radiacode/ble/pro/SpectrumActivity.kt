package com.radiacode.ble.pro

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.radiacode.ble.IsotopeLibrary
import com.radiacode.ble.R
import com.radiacode.ble.SpectrumData
import com.radiacode.ble.analysis.PeakFinder
import com.radiacode.ble.analysis.SpectrumFiles
import com.radiacode.ble.analysis.StoredSpectrum
import com.radiacode.ble.device.DeviceDataHub
import com.radiacode.ble.device.DeviceGateway
import com.radiacode.ble.pro.views.SpectrumPlotView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accumulated device spectrum with log/linear view, smoothing, background overlay and
 * subtraction, library line markers, offline peak search and identification, and
 * N42/CSV export. Saved spectra live in the app's private storage.
 */
class SpectrumActivity : ProActivity() {
    private lateinit var plot: SpectrumPlotView
    private lateinit var info: TextView
    private lateinit var results: LinearLayout
    private lateinit var cursorText: TextView

    private var current: StoredSpectrum? = null
    private var bg: StoredSpectrum? = null
    private var subtract = false
    private var showBg = true
    private var smoothing = 1
    private var auto = false
    private var libraryIds = LinkedHashSet<String>()
    private var lastResult: PeakFinder.Result? = null

    private val spectraDir by lazy { File(filesDir, "spectra").apply { mkdirs() } }
    private val bgFile by lazy { File(spectraDir, "background.n42") }

    private val autoTick = object : Runnable {
        override fun run() {
            if (auto) {
                readDevice(quiet = true)
                ui.postDelayed(this, 10_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("Spectrum", "Accumulated on the device since the last reset")
        bg = runCatching { if (bgFile.exists()) SpectrumFiles.fromN42(bgFile.readText(), "Background") else null }.getOrNull()

        plot = SpectrumPlotView(this)
        addFixed(column, plot, 340)
        cursorText = text("Tap the plot for a readout. Pinch to zoom, double tap to reset.", 12f, cMuted, mono = true)
        column.addView(cursorText)
        plot.onCursor = { ch, e, v -> cursorText.text = String.format(Locale.US, "ch %d   %.1f keV   %.0f counts", ch, e, v) }

        info = text("No spectrum loaded", 13f, cText2, mono = true)
        card(column).addView(info)

        val view = card(column, "View")
        segmented(view, listOf("Log", "Linear"), 0) { plot.logScale = it == 0; plot.invalidate() }
        sliderRow(view, "Smoothing", 1, 15, 1, { if (it <= 1) "off" else "$it ch" }) { smoothing = it; redraw() }
        switchRow(view, "Show background", null, showBg) { showBg = it; redraw() }
        switchRow(view, "Subtract background", "Scaled by live time", subtract) { subtract = it; redraw() }

        val src = card(column, "Source")
        switchRow(src, "Auto refresh", "Read the device every 10 s", false) {
            auto = it
            ui.removeCallbacks(autoTick)
            if (it) ui.post(autoTick)
        }
        buttons(src, 2,
            button("Read device", Btn.PRIMARY) { readDevice(quiet = false) },
            button("Open saved", Btn.SECONDARY) { openSaved() },
            button("Save", Btn.SECONDARY) { saveCurrent() },
            button("Use as background", Btn.SECONDARY) { setBackground() },
            button("Export N42", Btn.SECONDARY) { export(n42 = true) },
            button("Export CSV", Btn.SECONDARY) { export(n42 = false) },
            button("Library lines", Btn.SECONDARY) { pickLibrary() },
            button("Reset device spectrum", Btn.DANGER) {
                confirm("Reset spectrum", "Clear the spectrum accumulated on the device?", "Reset") {
                    device(op = { it.resetSpectrum() }) { ok ->
                        if (ok) {
                            DeviceDataHub.logAppEvent(this, DeviceGateway.activeDeviceId(this), "Spectrum reset")
                            toast("Spectrum reset")
                            readDevice(quiet = true)
                        } else toast("Device rejected the reset")
                    }
                }
            })

        val id = card(column, "Identification (offline)", "Peak search with counting statistics and library matching, computed on the phone. Long acquisitions give more reliable results.")
        buttons(id, 1, button("Find peaks and identify", Btn.PRIMARY) { identify() })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        id.addView(results)

        readDevice(quiet = true)
    }

    override fun onDestroy() {
        auto = false
        ui.removeCallbacks(autoTick)
        super.onDestroy()
    }

    private fun readDevice(quiet: Boolean) {
        device(timeoutSec = 20, op = { c -> c.readDeviceSpectrum().thenCombine(c.readSerialNumber().exceptionally { "" }) { s, sn -> s to sn } },
            onError = { if (!quiet) toast(it) }) { (s, sn) ->
            current = fromDevice(s, sn)
            redraw(resetView = false)
        }
    }

    private fun fromDevice(s: SpectrumData, serial: String): StoredSpectrum {
        return StoredSpectrum(
            title = "RadiaCode " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
            startMs = System.currentTimeMillis() - s.durationSeconds * 1000L,
            liveSeconds = s.durationSeconds.toDouble(),
            a0 = s.a0.toDouble(), a1 = s.a1.toDouble(), a2 = s.a2.toDouble(),
            counts = s.counts,
            serial = serial,
        )
    }

    private fun scaledBackground(fg: StoredSpectrum): DoubleArray? {
        val b = bg ?: return null
        if (b.liveSeconds <= 0 || fg.liveSeconds <= 0) return null
        val k = fg.liveSeconds / b.liveSeconds
        return DoubleArray(fg.counts.size) { i -> k * b.counts.getOrElse(i) { 0 } }
    }

    private fun redraw(resetView: Boolean = false) {
        val s = current ?: return
        var v = if (subtract && bg != null) SpectrumFiles.subtract(s, bg!!) else DoubleArray(s.counts.size) { s.counts[it].toDouble() }
        v = SpectrumFiles.smooth(v, smoothing).map { maxOf(it, 0.0) }.toDoubleArray()
        plot.background = if (showBg && !subtract) scaledBackground(s)?.let { SpectrumFiles.smooth(it, smoothing) } else null
        plot.markers = libraryIds.flatMap { id ->
            val iso = IsotopeLibrary.get(id) ?: return@flatMap emptyList()
            val col = MARKER_COLORS[libraryIds.indexOf(id) % MARKER_COLORS.size]
            iso.gammaLines.filter { it.intensity >= 0.03f }.map { SpectrumPlotView.Marker(it.energyKeV.toDouble(), "${iso.id} ${it.energyKeV.toInt()}", col) }
        }
        plot.setData(v, s.a0, s.a1, s.a2, resetView)
        val cps = if (s.liveSeconds > 0) s.total / s.liveSeconds else 0.0
        info.text = buildString {
            append(s.title).append('\n')
            append("Time ${Units.duration(s.liveSeconds.toLong())}   counts ${s.total}   ${Units.number(cps)} cps\n")
            append(String.format(Locale.US, "Calibration a0=%.2f a1=%.4f a2=%.6f", s.a0, s.a1, s.a2))
            if (bg != null) append("\nBackground: ${bg!!.title} (${Units.duration(bg!!.liveSeconds.toLong())})")
        }
    }

    private fun identify() {
        val s = current ?: run { toast("Read a spectrum first"); return }
        val counts = if (subtract && bg != null) SpectrumFiles.subtract(s, bg!!).map { maxOf(0, it.toInt()) }.toIntArray() else s.counts
        worker.execute {
            val r = PeakFinder().analyze(counts, s.a0, s.a1, s.a2)
            ui.post { showResult(r) }
        }
    }

    private fun showResult(r: PeakFinder.Result) {
        lastResult = r
        plot.peaks = r.peaks.map { it.energyKeV }
        plot.invalidate()
        results.removeAllViews()
        if (r.matches.isEmpty()) {
            results.addView(text(if (r.peaks.isEmpty()) "No significant peaks. Acquire longer." else "Peaks found but no library match.", 13f, cMuted).apply { setPadding(0, dp(8), 0, 0) })
        }
        for (m in r.matches.take(6)) {
            val color = when {
                m.score >= 0.7 -> cGreen
                m.score >= 0.4 -> cYellow
                else -> cMuted
            }
            val lines = m.lines.filter { it.peak != null }.joinToString { "${it.line.energyKeV.toInt()}" }
            val tv = text("${m.isotope.id}  ${m.isotope.name}\n  score ${(m.score * 100).toInt()}%   lines found: $lines keV", 14f, color)
            tv.setPadding(0, dp(8), 0, 0)
            tv.setOnClickListener { libraryIds.add(m.isotope.id); redraw() }
            results.addView(tv)
        }
        if (r.peaks.isNotEmpty()) {
            results.addView(text("Peaks", 12f, cMuted, bold = true).apply { setPadding(0, dp(12), 0, dp(2)) })
            val sb = StringBuilder()
            for (p in r.peaks.sortedByDescending { it.significance }.take(12)) {
                sb.append(String.format(Locale.US, "%7.1f keV  ch %6.1f  net %8.0f +- %5.0f  %4.1f sigma\n",
                    p.energyKeV, p.channel, p.netCounts, p.netUncertainty, p.significance))
            }
            results.addView(text(sb.toString().trimEnd(), 11f, cText2, mono = true))
        }
    }

    private fun pickLibrary() {
        val all = IsotopeLibrary.ALL_ISOTOPES
        val names = all.map { "${it.id}  ${it.name}" }.toTypedArray()
        val checked = all.map { libraryIds.contains(it.id) }.toBooleanArray()
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Show library lines")
            .setMultiChoiceItems(names, checked) { _, which, on ->
                if (on) libraryIds.add(all[which].id) else libraryIds.remove(all[which].id)
            }
            .setPositiveButton("Done") { _, _ -> redraw() }
            .setNeutralButton("Clear") { _, _ -> libraryIds.clear(); redraw() }
            .show()
    }

    private fun saveCurrent() {
        val s = current ?: run { toast("Nothing to save"); return }
        val field = textField(s.title, "Name")
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Save spectrum")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                val name = field.text.toString().ifBlank { s.title }
                val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(60)
                File(spectraDir, "$safe.n42").writeText(SpectrumFiles.toN42(s.copy(title = name)))
                toast("Saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSaved() {
        val files = spectraDir.listFiles { f -> f.name.endsWith(".n42") }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) { toast("No saved spectra"); return }
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Saved spectra")
            .setItems(files.map { it.nameWithoutExtension }.toTypedArray()) { _, which ->
                val s = runCatching { SpectrumFiles.fromN42(files[which].readText(), files[which].nameWithoutExtension) }.getOrNull()
                if (s == null) toast("Could not read file") else {
                    auto = false
                    current = s
                    redraw(resetView = true)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setBackground() {
        val s = current ?: run { toast("Read a spectrum first"); return }
        if (s.liveSeconds < 600) {
            confirm("Short background", "This spectrum is only ${Units.duration(s.liveSeconds.toLong())} long. A background should be at least several hours for useful subtraction. Use it anyway?", "Use") { storeBackground(s) }
        } else storeBackground(s)
    }

    private fun storeBackground(s: StoredSpectrum) {
        bg = s.copy(title = "Background " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
        bgFile.writeText(SpectrumFiles.toN42(bg!!))
        toast("Background stored")
        redraw()
    }

    private fun export(n42: Boolean) {
        val s = current ?: run { toast("Nothing to export"); return }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val f = if (n42) exportFile("spectrum_$stamp.n42", SpectrumFiles.toN42(s)) else exportFile("spectrum_$stamp.csv", SpectrumFiles.toCsv(s))
        share(f, if (n42) "application/xml" else "text/csv", f.name)
    }

    companion object {
        private val MARKER_COLORS = listOf(
            0xFFFF5252.toInt(), 0xFF40C4FF.toInt(), 0xFFFFD740.toInt(), 0xFFE040FB.toInt(), 0xFF69F0AE.toInt(), 0xFFFF9100.toInt()
        )
    }
}
