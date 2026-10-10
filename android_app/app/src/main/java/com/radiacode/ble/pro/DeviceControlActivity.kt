package com.radiacode.ble.pro

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.content.res.ColorStateList
import com.radiacode.ble.EnergyCalibration
import com.radiacode.ble.RadiacodeBleClient
import com.radiacode.ble.device.AlarmUnits
import com.radiacode.ble.device.CtrlFlags
import com.radiacode.ble.device.DeviceDataHub
import com.radiacode.ble.device.DeviceGateway
import com.radiacode.ble.device.FirmwareInfo
import com.radiacode.ble.device.ProSettings
import com.radiacode.ble.device.RegisterCodec
import com.radiacode.ble.device.RegisterSnapshot
import com.radiacode.ble.device.Vsfr
import java.time.LocalDateTime
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Remote control of the RadiaCode: alarm thresholds, signals, display, units, resets,
 * energy calibration and power. Every write is followed by a read-back; the settings
 * found on the device are backed up before the first write so they can be restored.
 */
class DeviceControlActivity : ProActivity() {

    private var deviceId: String? = null
    private var current: RegisterSnapshot? = null
    private val pending = LinkedHashMap<Vsfr, Double>()
    private var firmware: FirmwareInfo? = null
    private var serial: String? = null
    private var calibration: EnergyCalibration? = null
    private var status: String = "Reading settings from the device..."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("Device control", "Settings stored on the RadiaCode")
        deviceId = DeviceGateway.activeDeviceId(this)
        render()
        readAll()
    }

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    private fun readAll() {
        status = "Reading settings from the device..."
        render()
        deviceId = DeviceGateway.activeDeviceId(this)
        device(timeoutSec = 30, op = { c -> CompletableFuture.completedFuture(readEverything(c)) },
            onError = { status = "Read failed: $it"; render() }) { snap ->
            current = snap
            pending.clear()
            deviceId?.let { ProSettings.saveLastRegisters(this, it, snap) }
            status = "Read ${snap.values.size} settings at ${DateFormat.getTimeInstance().format(Date(snap.timestampMs))}"
            render()
        }
    }

    /** Blocking; runs on the worker thread. */
    private fun readEverything(c: RadiacodeBleClient): RegisterSnapshot {
        if (firmware == null) firmware = runCatching { c.readFirmwareVersion().get(10, TimeUnit.SECONDS) }.getOrNull()
        if (serial == null) serial = runCatching { c.readSerialNumber().get(10, TimeUnit.SECONDS) }.getOrNull()
        calibration = runCatching { c.readEnergyCalibration().get(10, TimeUnit.SECONDS) }.getOrNull()
        return readRegisters(c, Vsfr.SETTINGS)
    }

    private fun readRegisters(c: RadiacodeBleClient, regs: List<Vsfr>): RegisterSnapshot {
        val map = LinkedHashMap<Vsfr, Int>()
        val batch = runCatching { c.readVsfrs(regs.map { it.id }).get(15, TimeUnit.SECONDS) }
        if (batch.isSuccess) {
            val raw = batch.getOrThrow()
            regs.forEachIndexed { i, r -> raw.getOrNull(i)?.let { map[r] = it } }
        } else {
            // Some firmware rejects a batch containing an unknown register: read one by one.
            for (r in regs) {
                runCatching { c.readVsfrs(listOf(r.id)).get(5, TimeUnit.SECONDS).firstOrNull() }
                    .getOrNull()?.let { map[r] = it }
            }
            if (map.isEmpty()) throw batch.exceptionOrNull() ?: IllegalStateException("No register could be read")
        }
        // Registers the batch flagged invalid are skipped rather than guessed.
        return RegisterSnapshot(map)
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    private fun v(r: Vsfr): Double? = pending[r] ?: current?.value(r)

    private fun render() {
        column.removeAllViews()
        val snap = current

        val info = card(column, "Device")
        valueRow(info, "Device", DeviceGateway.displayName(deviceId))
        firmware?.let { valueRow(info, "Firmware", "${it.targetVersion}  (boot ${it.bootVersion})") }
        serial?.let { valueRow(info, "Serial number", it) }
        info.addView(text(status, 12f, if (status.startsWith("Read failed")) cRed else cMuted).apply { setPadding(0, dp(6), 0, 0) })
        buttons(info, 2,
            button("Read again", Btn.SECONDARY) { readAll() },
            button("Apply ${if (pending.isEmpty()) "" else "(${pending.size})"}".trim(), Btn.PRIMARY) { applyPending() })

        if (snap == null) return

        // ---- Alarms ------------------------------------------------------------------------------
        val alarms = card(column, "Alarm thresholds", "Values as used by the device. Dose rate and dose in microsieverts, count rate in counts per second.")
        thresholdRow(alarms, "Dose rate level 1", "uSv/h", Vsfr.DR_LEV1_uR_h, { AlarmUnits.urToUsv(it) }, { AlarmUnits.usvToUr(it).toDouble() })
        thresholdRow(alarms, "Dose rate level 2", "uSv/h", Vsfr.DR_LEV2_uR_h, { AlarmUnits.urToUsv(it) }, { AlarmUnits.usvToUr(it).toDouble() })
        thresholdRow(alarms, "Count rate level 1", "cps", Vsfr.CR_LEV1_cp10s, { AlarmUnits.cp10sToCps(it) }, { AlarmUnits.cpsToCp10s(it).toDouble() })
        thresholdRow(alarms, "Count rate level 2", "cps", Vsfr.CR_LEV2_cp10s, { AlarmUnits.cp10sToCps(it) }, { AlarmUnits.cpsToCp10s(it).toDouble() })
        thresholdRow(alarms, "Dose level 1", "uSv", Vsfr.DS_LEV1_uR, { AlarmUnits.urToUsv(it) }, { AlarmUnits.usvToUr(it).toDouble() })
        thresholdRow(alarms, "Dose level 2", "uSv", Vsfr.DS_LEV2_uR, { AlarmUnits.urToUsv(it) }, { AlarmUnits.usvToUr(it).toDouble() })

        // ---- Signals -----------------------------------------------------------------------------
        val signals = card(column, "Signals")
        boolRow(signals, "Sound", null, Vsfr.SOUND_ON)
        flagsBlock(signals, "Sound on", Vsfr.SOUND_CTRL)
        divider(signals)
        boolRow(signals, "Vibration", null, Vsfr.VIBRO_ON)
        flagsBlock(signals, "Vibrate on", Vsfr.VIBRO_CTRL)

        // ---- Display -----------------------------------------------------------------------------
        val display = card(column, "Display")
        v(Vsfr.DISP_BRT)?.let { b ->
            sliderRow(display, "Brightness", 0, 9, b.toInt(), { "$it" }) { pending[Vsfr.DISP_BRT] = it.toDouble(); refreshApplyLabel() }
        }
        v(Vsfr.DISP_OFF_TIME)?.let { t ->
            display.addView(text("Display off after", 15f, cText).apply { setPadding(0, dp(8), 0, 0) })
            segmented(display, listOf("5 s", "10 s", "15 s", "30 s"), t.toInt().coerceIn(0, 3)) { pending[Vsfr.DISP_OFF_TIME] = it.toDouble(); refreshApplyLabel() }
        }
        v(Vsfr.DISP_DIR)?.let { d ->
            display.addView(text("Orientation", 15f, cText).apply { setPadding(0, dp(8), 0, 0) })
            segmented(display, listOf("Auto", "Right hand", "Left hand"), d.toInt().coerceIn(0, 2)) { pending[Vsfr.DISP_DIR] = it.toDouble(); refreshApplyLabel() }
        }
        v(Vsfr.DEVICE_LANG)?.let { l ->
            display.addView(text("Device language", 15f, cText).apply { setPadding(0, dp(8), 0, 0) })
            segmented(display, listOf("Russian", "English"), l.toInt().coerceIn(0, 1)) { pending[Vsfr.DEVICE_LANG] = it.toDouble(); refreshApplyLabel() }
        }

        // ---- Units -------------------------------------------------------------------------------
        val units = card(column, "Units on the device display")
        v(Vsfr.DS_UNITS)?.let { u ->
            units.addView(text("Dose units", 15f, cText))
            segmented(units, listOf("Roentgen", "Sievert"), if (u != 0.0) 1 else 0) { pending[Vsfr.DS_UNITS] = it.toDouble(); refreshApplyLabel() }
        }
        v(Vsfr.CR_UNITS)?.let { u ->
            units.addView(text("Count rate units", 15f, cText))
            segmented(units, listOf("cps", "cpm"), if (u != 0.0) 1 else 0) { pending[Vsfr.CR_UNITS] = it.toDouble(); refreshApplyLabel() }
        }
        boolRow(units, "Show nSv/h", "Use nanosieverts for low dose rates", Vsfr.USE_nSv_h)

        // ---- Calibration -------------------------------------------------------------------------
        calibrationCard()

        // ---- Backup and actions ------------------------------------------------------------------
        val backup = card(column, "Backup", "Settings read from the device are saved automatically before the first change.")
        val id = deviceId
        val bTime = if (id != null) ProSettings.registerBackupTime(this, id) else 0L
        backup.addView(text(if (bTime > 0) "Last backup: ${DateFormat.getDateTimeInstance().format(Date(bTime))}" else "No backup yet", 13f, cText2))
        buttons(backup, 2,
            button("Back up now", Btn.SECONDARY) { backupNow(force = true) },
            button("Restore backup", Btn.SECONDARY) { restoreBackup() })

        val actions = card(column, "Actions")
        buttons(actions, 2,
            button("Sync time", Btn.SECONDARY) { syncTime() },
            button("Reset dose", Btn.DANGER) {
                confirm("Reset dose", "Set the accumulated dose on the device to zero? This cannot be undone.", "Reset") {
                    simpleAction("Dose reset") { it.resetDose() }
                }
            },
            button("Reset spectrum", Btn.DANGER) {
                confirm("Reset spectrum", "Clear the spectrum accumulated on the device?", "Reset") {
                    simpleAction("Spectrum reset") { it.resetSpectrum() }
                }
            },
            button("Power off", Btn.DANGER) {
                confirm("Power off", "Turn the RadiaCode off? You will need to press its button to turn it on again.", "Power off") {
                    simpleAction("Power off") { it.powerOff() }
                }
            })
    }

    private fun refreshApplyLabel() {
        // Re-render only the header buttons would need references; a light toast keeps it simple.
        toolbar.subtitle = if (pending.isEmpty()) "Settings stored on the RadiaCode" else "${pending.size} change(s) not applied"
    }

    private fun thresholdRow(parent: LinearLayout, label: String, unit: String, r: Vsfr, toUi: (Double) -> Double, fromUi: (Double) -> Double) {
        val raw = v(r) ?: return
        val shown = toUi(raw)
        val field = numberField(fmt(shown))
        field.setOnFocusChangeListener { _, has -> if (!has) commitNumber(field, r, fromUi) }
        field.setOnEditorActionListener { _, _, _ -> commitNumber(field, r, fromUi); false }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(field)
            addView(text(unit, 13f, cMuted).apply { setPadding(dp(8), 0, 0, 0); minWidth = dp(40) })
        }
        row(parent, label, null, right)
    }

    private fun commitNumber(field: EditText, r: Vsfr, fromUi: (Double) -> Double) {
        val d = field.text.toString().replace(',', '.').toDoubleOrNull() ?: return
        if (d < 0) { toast("Negative values are not allowed"); return }
        val raw = fromUi(d)
        if (current?.value(r) != raw) {
            pending[r] = raw
            refreshApplyLabel()
        }
    }

    private fun boolRow(parent: LinearLayout, label: String, desc: String?, r: Vsfr) {
        val value = v(r) ?: return
        switchRow(parent, label, desc, value != 0.0) { pending[r] = if (it) 1.0 else 0.0; refreshApplyLabel() }
    }

    private fun flagsBlock(parent: LinearLayout, title: String, r: Vsfr) {
        val value = v(r)?.toInt() ?: return
        parent.addView(text(title, 12f, cMuted).apply { setPadding(0, dp(4), 0, dp(2)) })
        var mask = value
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((bit, name) in CtrlFlags.ALL) {
            val cb = CheckBox(this).apply {
                text = name
                setTextColor(cText2)
                isChecked = mask and bit != 0
                buttonTintList = ColorStateList.valueOf(cCyan)
                setOnCheckedChangeListener { _, on ->
                    mask = if (on) mask or bit else mask and bit.inv()
                    pending[r] = mask.toDouble()
                    refreshApplyLabel()
                }
            }
            grid.addView(cb)
        }
        parent.addView(grid)
    }

    private fun calibrationCard() {
        val cal = calibration ?: return
        val card = card(column, "Energy calibration", "E(keV) = a0 + a1 * ch + a2 * ch^2. Changing it moves every peak; keep a backup of the values below.")
        val f0 = numberField(String.format(Locale.US, "%.4f", cal.a0), widthDp = 140)
        val f1 = numberField(String.format(Locale.US, "%.6f", cal.a1), widthDp = 140)
        val f2 = numberField(String.format(Locale.US, "%.8f", cal.a2), widthDp = 140)
        row(card, "a0 (offset)", null, f0)
        row(card, "a1 (linear)", null, f1)
        row(card, "a2 (quadratic)", null, f2)

        card.addView(text("Fit from known peaks: enter channel numbers of two or three lines (for example from the Spectrum screen).", 12f, cMuted).apply { setPadding(0, dp(8), 0, dp(4)) })
        val points = listOf(
            numberField("", widthDp = 90) to numberField("661.7", widthDp = 90),
            numberField("", widthDp = 90) to numberField("1460.8", widthDp = 90),
            numberField("", widthDp = 90) to numberField("", widthDp = 90),
        )
        points.forEachIndexed { i, (ch, e) ->
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ch); addView(text(" ch  ", 12f, cMuted)); addView(e); addView(text(" keV", 12f, cMuted))
            }
            row(card, "Point ${i + 1}", null, right)
        }
        buttons(card, 2,
            button("Fit", Btn.SECONDARY) {
                val pts = points.mapNotNull { (ch, e) ->
                    val c = ch.text.toString().replace(',', '.').toDoubleOrNull()
                    val en = e.text.toString().replace(',', '.').toDoubleOrNull()
                    if (c != null && en != null) c to en else null
                }
                val fit = CalibrationFit.fit(pts)
                if (fit == null) toast("Enter at least two distinct points") else {
                    f0.setText(String.format(Locale.US, "%.4f", fit[0]))
                    f1.setText(String.format(Locale.US, "%.6f", fit[1]))
                    f2.setText(String.format(Locale.US, "%.8f", fit[2]))
                }
            },
            button("Write calibration", Btn.PRIMARY) {
                val a0 = f0.text.toString().replace(',', '.').toFloatOrNull()
                val a1 = f1.text.toString().replace(',', '.').toFloatOrNull()
                val a2 = f2.text.toString().replace(',', '.').toFloatOrNull()
                if (a0 == null || a1 == null || a2 == null || a1 <= 0f) { toast("Invalid coefficients"); return@button }
                val old = String.format(Locale.US, "a0=%.4f a1=%.6f a2=%.8f", cal.a0, cal.a1, cal.a2)
                confirm("Write calibration", "Previous values:\n$old\n\nWrite the new coefficients to the device?", "Write") {
                    device(op = { c ->
                        c.writeEnergyCalibration(a0, a1, a2).thenCompose { ok ->
                            if (!ok) CompletableFuture.completedFuture(null) else c.readEnergyCalibration()
                        }
                    }) { back ->
                        if (back == null) toast("Device rejected the calibration")
                        else {
                            calibration = back
                            DeviceDataHub.logAppEvent(this, deviceId, "Energy calibration written", "previous $old")
                            val match = kotlin.math.abs(back.a1 - a1) < 1e-6f && kotlin.math.abs(back.a0 - a0) < 1e-3f
                            toast(if (match) "Calibration written and verified" else "Written, but the device reports different values")
                            render()
                        }
                    }
                }
            })
    }

    private fun fmt(d: Double): String = when {
        d == Math.floor(d) && d < 1e6 -> String.format(Locale.US, "%.0f", d)
        d < 1 -> String.format(Locale.US, "%.3f", d)
        else -> String.format(Locale.US, "%.2f", d)
    }

    // ---------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------

    private fun backupNow(force: Boolean) {
        val id = deviceId ?: return
        val snap = current ?: return
        if (!force && ProSettings.registerBackup(this, id) != null) return
        ProSettings.saveRegisterBackup(this, id, snap)
        if (force) { toast("Backup saved"); render() }
    }

    private fun applyPending() {
        if (pending.isEmpty()) { toast("Nothing to apply"); return }
        val changes = LinkedHashMap(pending)
        val summary = changes.entries.joinToString("\n") { "${it.key.label}: ${describe(it.key, current?.value(it.key))} -> ${describe(it.key, it.value)}" }
        confirm("Apply ${changes.size} change(s)", summary, "Apply") { write(changes, "Settings changed") }
    }

    private fun describe(r: Vsfr, raw: Double?): String {
        if (raw == null) return "?"
        return when (r) {
            Vsfr.DR_LEV1_uR_h, Vsfr.DR_LEV2_uR_h -> "${fmt(AlarmUnits.urToUsv(raw))} uSv/h"
            Vsfr.DS_LEV1_uR, Vsfr.DS_LEV2_uR -> "${fmt(AlarmUnits.urToUsv(raw))} uSv"
            Vsfr.CR_LEV1_cp10s, Vsfr.CR_LEV2_cp10s -> "${fmt(AlarmUnits.cp10sToCps(raw))} cps"
            Vsfr.SOUND_CTRL, Vsfr.VIBRO_CTRL -> "0x" + raw.toInt().toString(16)
            else -> fmt(raw)
        }
    }

    private fun write(changes: Map<Vsfr, Double>, logName: String) {
        backupNow(force = false)
        status = "Writing ${changes.size} setting(s)..."
        render()
        device(timeoutSec = 60, op = { c -> CompletableFuture.completedFuture(writeAndVerify(c, changes)) },
            onError = { status = "Write failed: $it"; render() }) { result ->
            val (snap, problems) = result
            current = snap
            pending.clear()
            deviceId?.let { ProSettings.saveLastRegisters(this, it, snap) }
            DeviceDataHub.logAppEvent(this, deviceId, logName, changes.keys.joinToString { it.label })
            status = if (problems.isEmpty()) "All ${changes.size} change(s) written and verified" else "Check: ${problems.joinToString()}"
            refreshApplyLabel()
            render()
            if (problems.isNotEmpty()) {
                confirm("Verification", "The device reports different values for:\n${problems.joinToString("\n")}\n\nRead again to see the current state.", "Read again") { readAll() }
            }
        }
    }

    /** Blocking; worker thread. Returns the read-back snapshot and the registers that did not match. */
    private fun writeAndVerify(c: RadiacodeBleClient, changes: Map<Vsfr, Double>): Pair<RegisterSnapshot, List<String>> {
        val problems = ArrayList<String>()
        for ((r, value) in changes) {
            val ok = runCatching { c.writeVsfr(r.id, RegisterCodec.encode(r.kind, value)).get(10, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!ok) problems.add("${r.label} (rejected)")
        }
        val back = readRegisters(c, Vsfr.SETTINGS)
        for ((r, value) in changes) {
            val got = back.value(r)
            if (got == null || kotlin.math.abs(got - RegisterCodec.decode(r.kind, RegisterCodec.encode(r.kind, value))) > 1e-6) {
                if (problems.none { it.startsWith(r.label) }) problems.add(r.label)
            }
        }
        return back to problems
    }

    private fun restoreBackup() {
        val id = deviceId ?: run { toast("No device"); return }
        val backup = ProSettings.registerBackup(this, id) ?: run { toast("No backup for this device"); return }
        val cur = current
        val changes = LinkedHashMap<Vsfr, Double>()
        for ((r, raw) in backup.values) {
            val want = RegisterCodec.decode(r.kind, raw)
            if (cur?.value(r) != want) changes[r] = want
        }
        if (changes.isEmpty()) { toast("Device already matches the backup"); return }
        confirm("Restore backup", "Restore ${changes.size} setting(s) saved on ${DateFormat.getDateTimeInstance().format(Date(backup.timestampMs))}?", "Restore") {
            write(changes, "Settings restored from backup")
        }
    }

    private fun syncTime() {
        device(op = { c ->
            val now = LocalDateTime.now()
            c.syncClock(now)
        }) { ok -> toast(if (ok) "Device clock set to phone time" else "Device rejected the time") }
    }

    private fun simpleAction(name: String, op: (RadiacodeBleClient) -> CompletableFuture<Boolean>) {
        device(op = op) { ok ->
            if (ok) {
                DeviceDataHub.logAppEvent(this, deviceId, name)
                toast("$name done")
            } else toast("$name rejected by the device")
        }
    }
}

