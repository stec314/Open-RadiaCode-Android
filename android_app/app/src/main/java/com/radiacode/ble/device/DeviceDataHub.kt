package com.radiacode.ble.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.radiacode.ble.DeviceEvent
import com.radiacode.ble.DeviceEventNames
import com.radiacode.ble.FullDeviceData
import com.radiacode.ble.MainActivity
import com.radiacode.ble.R
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives every decoded DATA_BUF from the poll loop (background thread) and keeps:
 * accumulated dose per device, a persistent event log, a slow dose history,
 * spoken announcements and duplication of device alarms on the phone.
 */
internal object DeviceDataHub {
    private const val TAG = "DeviceDataHub"
    const val ACTION_DEVICE_EVENT = "com.radiacode.ble.action.DEVICE_EVENT"
    const val ACTION_DOSE_UPDATE = "com.radiacode.ble.action.DOSE_UPDATE"
    const val EXTRA_DEVICE_ID = "device_id"
    const val EXTRA_EVENT_NAME = "event_name"
    const val EXTRA_DOSE_USV = "dose_usv"
    const val EXTRA_DOSE_DURATION_S = "dose_duration_s"

    private const val EVENT_FILE = "device_events.jsonl"
    private const val DOSE_FILE = "dose_history.csv"
    private const val MAX_EVENT_LINES = 5000
    private const val DOSE_SAMPLE_MS = 60_000L
    private const val CHANNEL_ID = "device_alarms"

    data class DoseState(val doseUsv: Float, val durationSeconds: Long, val timestampMs: Long)

    data class LoggedEvent(
        val timestampMs: Long,
        val deviceId: String,
        val eventId: Int,
        val name: String,
        val source: String,
        val detail: String,
    ) {
        val isAlarm: Boolean get() = source == "device" && eventId in DeviceEventNames.ALARM_IDS
        val isLevel2: Boolean get() = eventId in DeviceEventNames.LEVEL2_IDS
    }

    private val doseByDevice = ConcurrentHashMap<String, DoseState>()
    private val lastDoseSample = ConcurrentHashMap<String, Long>()
    private val lastAlarmMs = ConcurrentHashMap<String, Long>()
    @Volatile private var lastVoiceMs = 0L
    @Volatile var lastDoseRateUsvH: Float = 0f
        private set
    @Volatile var lastCps: Float = 0f
        private set
    @Volatile var lastUpdateMs: Long = 0L
        private set
    private val fileLock = Any()

    fun dose(deviceId: String?): DoseState? =
        if (deviceId != null) doseByDevice[deviceId] else doseByDevice.values.maxByOrNull { it.timestampMs }

    fun onDecoded(context: Context, deviceId: String, data: FullDeviceData, uSvPerHour: Float, cps: Float) {
        try {
            lastDoseRateUsvH = uSvPerHour
            lastCps = cps
            val now = System.currentTimeMillis()
            lastUpdateMs = now

            data.dose?.let { d ->
                val st = DoseState(d.doseSv * 1_000_000f, d.durationSeconds, now)
                doseByDevice[deviceId] = st
                context.sendBroadcast(
                    Intent(ACTION_DOSE_UPDATE).setPackage(context.packageName)
                        .putExtra(EXTRA_DEVICE_ID, deviceId)
                        .putExtra(EXTRA_DOSE_USV, st.doseUsv)
                        .putExtra(EXTRA_DOSE_DURATION_S, st.durationSeconds)
                )
                val last = lastDoseSample[deviceId] ?: 0L
                if (now - last >= DOSE_SAMPLE_MS) {
                    lastDoseSample[deviceId] = now
                    appendDoseSample(context, now, deviceId, st.doseUsv, uSvPerHour)
                }
            }

            data.dose?.let { trackMission(context, deviceId, it.doseSv * 1_000_000f, uSvPerHour, now) }

            for (ev in data.events) handleEvent(context, deviceId, ev, now)

            val intervalMin = ProSettings.voiceIntervalMin(context)
            if (intervalMin > 0 && now - lastVoiceMs >= intervalMin * 60_000L) {
                lastVoiceMs = now
                LocalVoice.speak(context, "Dose rate ${LocalVoice.doseRatePhrase(uSvPerHour)}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onDecoded failed", t)
        }
    }

    private fun handleEvent(context: Context, deviceId: String, ev: DeviceEvent, now: Long) {
        logEvent(context, LoggedEvent(now, deviceId, ev.eventId, ev.name, "device", "param=${ev.param1}"))
        context.sendBroadcast(
            Intent(ACTION_DEVICE_EVENT).setPackage(context.packageName)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_EVENT_NAME, ev.name)
        )
        if (!ev.isAlarm) return
        val key = "$deviceId:${ev.eventId}"
        val last = lastAlarmMs[key] ?: 0L
        if (now - last < 10_000L) return
        lastAlarmMs[key] = now

        if (ProSettings.duplicateDeviceAlarms(context)) {
            notifyAlarm(context, ev.name, ev.eventId in DeviceEventNames.LEVEL2_IDS)
        }
        if (ProSettings.voiceAlarms(context)) {
            LocalVoice.speak(context, "Warning. ${ev.name}. ${LocalVoice.doseRatePhrase(lastDoseRateUsvH)}")
        }
    }

    // ---- Intervention mode -----------------------------------------------------------------------
    private const val MISSION_FILE = "mission_log.csv"
    @Volatile private var lastMissionSample = 0L

    /**
     * Mission dose is accumulated from positive increments of the device dose, so a dose reset
     * on the device during a mission does not lose or double-count anything.
     */
    private fun trackMission(context: Context, deviceId: String, doseUsv: Float, rate: Float, now: Long) {
        if (!ProSettings.missionActive(context)) return
        if (deviceId != DeviceGateway.activeDeviceId(context)) return
        val last = ProSettings.missionLastSeen(context)
        var mission = ProSettings.missionDoseUsv(context)
        if (last != null && doseUsv > last) mission += doseUsv - last
        val maxRate = maxOf(ProSettings.missionMaxRate(context), rate)
        val budget = ProSettings.doseBudgetUsv(context)
        var alert = ProSettings.missionAlertLevel(context)
        val frac = if (budget > 0) mission / budget else 0f
        val level = when { frac >= 1f -> 3; frac >= 0.8f -> 2; frac >= 0.5f -> 1; else -> 0 }
        if (level > alert) {
            alert = level
            val msg = when (level) { 3 -> "Dose budget reached"; 2 -> "80 percent of dose budget used"; else -> "Half of dose budget used" }
            logAppEvent(context, deviceId, msg, String.format(Locale.US, "%.2f uSv of %.0f uSv", mission, budget))
            notifyAlarm(context, msg, level >= 2)
            if (ProSettings.voiceAlarms(context) || ProSettings.voiceIntervalMin(context) > 0) LocalVoice.speak(context, msg)
        }
        ProSettings.updateMission(context, mission, maxRate, doseUsv, alert)
        if (now - lastMissionSample >= 30_000L) {
            lastMissionSample = now
            synchronized(fileLock) {
                File(context.filesDir, MISSION_FILE).appendText(
                    String.format(Locale.US, "%d,%s,%.4f,%.4f,%.3f\n", now, ProSettings.missionName(context).replace(",", " "), mission, rate, lastCps)
                )
            }
        }
    }

    fun missionLogFile(context: Context): File = File(context.filesDir, MISSION_FILE)

    /** Record an app-side event (dose reset, settings write, mission start) in the same log. */
    fun logAppEvent(context: Context, deviceId: String?, name: String, detail: String = "") {
        logEvent(context, LoggedEvent(System.currentTimeMillis(), deviceId ?: "-", -1, name, "app", detail))
    }

    private fun logEvent(context: Context, e: LoggedEvent) {
        val json = JSONObject()
            .put("t", e.timestampMs).put("d", e.deviceId).put("e", e.eventId)
            .put("n", e.name).put("s", e.source).put("x", e.detail)
        synchronized(fileLock) {
            val f = File(context.filesDir, EVENT_FILE)
            f.appendText(json.toString() + "\n")
            if (f.length() > 600_000L) {
                val lines = f.readLines()
                if (lines.size > MAX_EVENT_LINES) f.writeText(lines.takeLast(MAX_EVENT_LINES).joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun readEvents(context: Context): List<LoggedEvent> = synchronized(fileLock) {
        val f = File(context.filesDir, EVENT_FILE)
        if (!f.exists()) return emptyList()
        f.readLines().mapNotNull { line ->
            try {
                val o = JSONObject(line)
                LoggedEvent(o.getLong("t"), o.optString("d"), o.optInt("e", -1), o.optString("n"), o.optString("s"), o.optString("x"))
            } catch (_: Exception) {
                null
            }
        }.reversed()
    }

    fun clearEvents(context: Context) = synchronized(fileLock) { File(context.filesDir, EVENT_FILE).delete() }

    fun eventLogFile(context: Context): File = File(context.filesDir, EVENT_FILE)

    // ---- Dose history ----------------------------------------------------------------------------
    data class DoseSample(val timestampMs: Long, val deviceId: String, val doseUsv: Float, val doseRateUsvH: Float)

    private fun appendDoseSample(context: Context, ts: Long, deviceId: String, doseUsv: Float, rate: Float) {
        synchronized(fileLock) {
            val f = File(context.filesDir, DOSE_FILE)
            f.appendText(String.format(Locale.US, "%d,%s,%.5f,%.5f\n", ts, deviceId, doseUsv, rate))
        }
    }

    fun readDoseHistory(context: Context, deviceId: String?, sinceMs: Long): List<DoseSample> = synchronized(fileLock) {
        val f = File(context.filesDir, DOSE_FILE)
        if (!f.exists()) return emptyList()
        f.readLines().mapNotNull { line ->
            val p = line.split(",")
            if (p.size < 4) return@mapNotNull null
            val ts = p[0].toLongOrNull() ?: return@mapNotNull null
            if (ts < sinceMs) return@mapNotNull null
            if (deviceId != null && p[1] != deviceId) return@mapNotNull null
            DoseSample(ts, p[1], p[2].toFloatOrNull() ?: 0f, p[3].toFloatOrNull() ?: 0f)
        }
    }

    fun doseHistoryFile(context: Context): File = File(context.filesDir, DOSE_FILE)

    // ---- Phone alarm duplication -----------------------------------------------------------------
    private fun notifyAlarm(context: Context, title: String, level2: Boolean) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Device alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Alarms raised by the RadiaCode device, repeated on the phone"
                        enableVibration(true)
                    }
                )
            }
            val pi = PendingIntent.getActivity(
                context, 4711,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notifications)
                .setContentTitle(title)
                .setContentText("Dose rate ${String.format(Locale.US, "%.3f", lastDoseRateUsvH)} uSv/h, ${String.format(Locale.US, "%.1f", lastCps)} cps")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(9000 + title.hashCode() % 500, n)

            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            val pattern = if (level2) longArrayOf(0, 400, 150, 400, 150, 400) else longArrayOf(0, 300, 200, 300)
            if (Build.VERSION.SDK_INT >= 26) vib?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") vib?.vibrate(pattern, -1)

            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            tone.startTone(if (level2) ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK else ToneGenerator.TONE_PROP_BEEP2, 1200)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 1500)
        } catch (t: Throwable) {
            Log.w(TAG, "notifyAlarm failed", t)
        }
    }
}
