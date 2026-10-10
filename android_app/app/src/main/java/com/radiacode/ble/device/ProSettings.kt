package com.radiacode.ble.device

import android.content.Context

/**
 * Settings for the instrument features (signals, voice, network, intervention).
 * Stored separately from the legacy Prefs file to keep that file untouched.
 */
object ProSettings {
    private const val FILE = "pro_settings"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Signals --------------------------------------------------------------------------------
    fun duplicateDeviceAlarms(c: Context) = sp(c).getBoolean("dup_alarms", true)
    fun setDuplicateDeviceAlarms(c: Context, v: Boolean) = sp(c).edit().putBoolean("dup_alarms", v).apply()

    /** Speak device alarms with the phone voice. */
    fun voiceAlarms(c: Context) = sp(c).getBoolean("voice_alarms", false)
    fun setVoiceAlarms(c: Context, v: Boolean) = sp(c).edit().putBoolean("voice_alarms", v).apply()

    /** Periodic spoken dose rate, minutes; 0 = off. */
    fun voiceIntervalMin(c: Context) = sp(c).getInt("voice_interval_min", 0)
    fun setVoiceIntervalMin(c: Context, v: Int) = sp(c).edit().putInt("voice_interval_min", v).apply()

    fun clicksEnabled(c: Context) = sp(c).getBoolean("clicks", true)
    fun setClicksEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("clicks", v).apply()

    /** Play one click every N detected pulses. */
    fun clickDivider(c: Context) = sp(c).getInt("click_divider", 1).coerceAtLeast(1)
    fun setClickDivider(c: Context, v: Int) = sp(c).edit().putInt("click_divider", v.coerceAtLeast(1)).apply()

    fun clicksVolume(c: Context) = sp(c).getInt("click_volume", 60).coerceIn(0, 100)
    fun setClicksVolume(c: Context, v: Int) = sp(c).edit().putInt("click_volume", v.coerceIn(0, 100)).apply()

    /** Averaging window for Search mode, seconds. */
    fun averagingSeconds(c: Context) = sp(c).getInt("avg_window_s", 8).coerceIn(1, 120)
    fun setAveragingSeconds(c: Context, v: Int) = sp(c).edit().putInt("avg_window_s", v).apply()

    /** Screen opened at app start: 0 dashboard, 1 search, 2 spectrum, 3 dose, 4 intervention. */
    fun startPage(c: Context) = sp(c).getInt("start_page", 0)
    fun setStartPage(c: Context, v: Int) = sp(c).edit().putInt("start_page", v).apply()

    fun keepScreenOn(c: Context) = sp(c).getBoolean("keep_screen_on", false)
    fun setKeepScreenOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("keep_screen_on", v).apply()

    // ---- Units for instrument screens ------------------------------------------------------------
    enum class DoseSystem { SIEVERT, REM, ROENTGEN }

    fun doseSystem(c: Context): DoseSystem =
        runCatching { DoseSystem.valueOf(sp(c).getString("dose_system", DoseSystem.SIEVERT.name)!!) }
            .getOrDefault(DoseSystem.SIEVERT)
    fun setDoseSystem(c: Context, v: DoseSystem) = sp(c).edit().putString("dose_system", v.name).apply()

    fun temperatureFahrenheit(c: Context) = sp(c).getBoolean("temp_f", false)
    fun setTemperatureFahrenheit(c: Context, v: Boolean) = sp(c).edit().putBoolean("temp_f", v).apply()

    // ---- Network (opt-in) -----------------------------------------------------------------------
    fun remoteIsotopeEnabled(c: Context) = sp(c).getBoolean("remote_iso", false)
    fun setRemoteIsotopeEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("remote_iso", v).apply()

    fun remoteIsotopeUrl(c: Context) = sp(c).getString("remote_iso_url", "") ?: ""
    fun setRemoteIsotopeUrl(c: Context, v: String) = sp(c).edit().putString("remote_iso_url", v.trim()).apply()

    /** Endpoint for remote isotope identification, or null when disabled or not HTTPS. */
    fun remoteIsotopeEndpoint(c: Context): String? {
        if (!remoteIsotopeEnabled(c)) return null
        return remoteIsotopeUrl(c).takeIf { isHttps(it) }?.trimEnd('/')
    }

    fun remoteVoiceEnabled(c: Context) = sp(c).getBoolean("remote_voice", false)
    fun setRemoteVoiceEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("remote_voice", v).apply()

    fun isHttps(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true) && url.trim().length > 10

    // ---- Intervention mode -----------------------------------------------------------------------
    fun doseBudgetUsv(c: Context) = sp(c).getFloat("dose_budget_usv", 1000f)
    fun setDoseBudgetUsv(c: Context, v: Float) = sp(c).edit().putFloat("dose_budget_usv", v).apply()

    fun missionStartMs(c: Context) = sp(c).getLong("mission_start", 0L)
    fun missionStartDoseUsv(c: Context) = sp(c).getFloat("mission_start_dose", 0f)
    fun missionName(c: Context) = sp(c).getString("mission_name", "") ?: ""
    fun startMission(c: Context, name: String, startDoseUsv: Float) = sp(c).edit()
        .putLong("mission_start", System.currentTimeMillis())
        .putFloat("mission_start_dose", startDoseUsv)
        .putString("mission_name", name)
        .putFloat("mission_dose", 0f)
        .putFloat("mission_max_rate", 0f)
        .putInt("mission_alert", 0)
        .remove("mission_last_seen")
        .apply()
    fun missionActive(c: Context) = missionStartMs(c) > 0L
    fun missionDoseUsv(c: Context) = sp(c).getFloat("mission_dose", 0f)
    fun missionMaxRate(c: Context) = sp(c).getFloat("mission_max_rate", 0f)
    fun missionAlertLevel(c: Context) = sp(c).getInt("mission_alert", 0)
    fun missionLastSeen(c: Context): Float? = if (sp(c).contains("mission_last_seen")) sp(c).getFloat("mission_last_seen", 0f) else null
    fun updateMission(c: Context, dose: Float, maxRate: Float, lastSeen: Float, alert: Int) = sp(c).edit()
        .putFloat("mission_dose", dose).putFloat("mission_max_rate", maxRate)
        .putFloat("mission_last_seen", lastSeen).putInt("mission_alert", alert).apply()
    fun stopMission(c: Context) = sp(c).edit().putLong("mission_start", 0L).apply()

    // ---- Device register backup ------------------------------------------------------------------
    fun registerBackup(c: Context, deviceId: String): RegisterSnapshot? =
        sp(c).getString("reg_backup_$deviceId", null)?.let { RegisterSnapshot.fromJson(it) }
    fun registerBackupTime(c: Context, deviceId: String) = sp(c).getLong("reg_backup_ts_$deviceId", 0L)
    fun saveRegisterBackup(c: Context, deviceId: String, snap: RegisterSnapshot) = sp(c).edit()
        .putString("reg_backup_$deviceId", snap.toJson())
        .putLong("reg_backup_ts_$deviceId", snap.timestampMs)
        .apply()

    /** Last register snapshot read from a device (used for dose alarm bars). */
    fun lastRegisters(c: Context, deviceId: String): RegisterSnapshot? =
        sp(c).getString("reg_last_$deviceId", null)?.let { RegisterSnapshot.fromJson(it) }
    fun saveLastRegisters(c: Context, deviceId: String, snap: RegisterSnapshot) =
        sp(c).edit().putString("reg_last_$deviceId", snap.toJson()).apply()
}
