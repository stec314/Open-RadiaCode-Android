package com.radiacode.ble.device

/**
 * Virtual special function registers (VSFR) used for remote device control.
 *
 * IDs and value formats follow the cdump/radiacode Python library. Values travel as
 * 4-byte little-endian words. The library describes several small registers with a
 * padded format ("3xB", "2xH"), which places the value in the high byte(s); writes in
 * the same library use a plain little-endian integer. [RegisterCodec] therefore
 * decodes small registers defensively from either position, and every write in this
 * app is verified by reading the register back.
 */
enum class RegKind { U32, BYTE, BOOL, U16, F32 }

enum class Vsfr(val id: Int, val kind: RegKind, val label: String) {
    DEVICE_LANG(0x0502, RegKind.BYTE, "Language"),
    DEVICE_ON(0x0503, RegKind.BOOL, "Device power"),
    DISP_BRT(0x0511, RegKind.BYTE, "Display brightness"),
    DISP_OFF_TIME(0x0513, RegKind.U32, "Display auto-off"),
    DISP_DIR(0x0515, RegKind.BYTE, "Display orientation"),
    SOUND_CTRL(0x0520, RegKind.U16, "Sound events"),
    SOUND_ON(0x0522, RegKind.BOOL, "Sound"),
    VIBRO_CTRL(0x0530, RegKind.BYTE, "Vibration events"),
    VIBRO_ON(0x0531, RegKind.BOOL, "Vibration"),
    LEDS_ON(0x0545, RegKind.BOOL, "LEDs"),
    PLAY_SIGNAL(0x05E1, RegKind.BYTE, "Play signal"),
    DR_LEV1_uR_h(0x8000, RegKind.U32, "Dose rate alarm 1"),
    DR_LEV2_uR_h(0x8001, RegKind.U32, "Dose rate alarm 2"),
    DS_UNITS(0x8004, RegKind.BOOL, "Dose units"),
    DOSE_RESET(0x8007, RegKind.BOOL, "Dose reset"),
    CR_LEV1_cp10s(0x8008, RegKind.U32, "Count rate alarm 1"),
    CR_LEV2_cp10s(0x8009, RegKind.U32, "Count rate alarm 2"),
    USE_nSv_h(0x800C, RegKind.BOOL, "nSv/h display"),
    CR_UNITS(0x8013, RegKind.BOOL, "Count rate units"),
    DS_LEV1_uR(0x8014, RegKind.U32, "Dose alarm 1"),
    DS_LEV2_uR(0x8015, RegKind.U32, "Dose alarm 2"),
    TEMP_degC(0x8024, RegKind.F32, "Temperature");

    companion object {
        fun byId(id: Int): Vsfr? = values().firstOrNull { it.id == id }

        /** Registers shown and editable in the device control screen. */
        val SETTINGS: List<Vsfr> = listOf(
            DEVICE_LANG, DISP_BRT, DISP_OFF_TIME, DISP_DIR,
            SOUND_ON, SOUND_CTRL, VIBRO_ON, VIBRO_CTRL, LEDS_ON,
            DR_LEV1_uR_h, DR_LEV2_uR_h, CR_LEV1_cp10s, CR_LEV2_cp10s,
            DS_LEV1_uR, DS_LEV2_uR, DS_UNITS, CR_UNITS, USE_nSv_h,
        )
    }
}

/** Bits of SOUND_CTRL / VIBRO_CTRL (cdump CTRL enum). */
object CtrlFlags {
    const val BUTTONS = 0x01
    const val CLICKS = 0x02
    const val DOSE_RATE_ALARM_1 = 0x04
    const val DOSE_RATE_ALARM_2 = 0x08
    const val DOSE_RATE_OUT_OF_SCALE = 0x10
    const val DOSE_ALARM_1 = 0x20
    const val DOSE_ALARM_2 = 0x40
    const val DOSE_OUT_OF_SCALE = 0x80

    val ALL: List<Pair<Int, String>> = listOf(
        BUTTONS to "Buttons",
        CLICKS to "Particle clicks",
        DOSE_RATE_ALARM_1 to "Dose rate alarm 1",
        DOSE_RATE_ALARM_2 to "Dose rate alarm 2",
        DOSE_RATE_OUT_OF_SCALE to "Dose rate off scale",
        DOSE_ALARM_1 to "Dose alarm 1",
        DOSE_ALARM_2 to "Dose alarm 2",
        DOSE_OUT_OF_SCALE to "Dose off scale",
    )
}

object RegisterCodec {
    /** Decode a raw 4-byte register word (as a little-endian Int) into a number. */
    fun decode(kind: RegKind, raw: Int): Double = when (kind) {
        RegKind.U32 -> (raw.toLong() and 0xFFFF_FFFFL).toDouble()
        RegKind.F32 -> java.lang.Float.intBitsToFloat(raw).toDouble()
        RegKind.U16 -> {
            val low = raw and 0xFFFF
            val high = (raw ushr 16) and 0xFFFF
            (if (low != 0 || high == 0) low else high).toDouble()
        }
        RegKind.BYTE, RegKind.BOOL -> {
            val low = raw and 0xFF
            val high = (raw ushr 24) and 0xFF
            val v = if (low != 0 || high == 0) low else high
            if (kind == RegKind.BOOL) (if (v != 0) 1.0 else 0.0) else v.toDouble()
        }
    }

    /** Encode a value for WR_VIRT_SFR (plain little-endian word, as the reference library writes it). */
    fun encode(kind: RegKind, value: Double): Int = when (kind) {
        RegKind.F32 -> java.lang.Float.floatToIntBits(value.toFloat())
        RegKind.BOOL -> if (value != 0.0) 1 else 0
        else -> value.toLong().toInt()
    }
}

/**
 * Alarm thresholds as the device stores them, with conversions to the units shown in the app.
 *
 * Device units: dose rate in uR/h, dose in uR, count rate in counts per 10 s.
 * The device converts with a fixed 100 R = 1 Sv, so 1 uSv = 100 uR.
 */
object AlarmUnits {
    const val UR_PER_USV = 100.0

    fun urToUsv(ur: Double): Double = ur / UR_PER_USV
    fun usvToUr(usv: Double): Long = Math.round(usv * UR_PER_USV)
    fun cp10sToCps(raw: Double): Double = raw / 10.0
    fun cpsToCp10s(cps: Double): Long = Math.round(cps * 10.0)

    fun displayOffSeconds(index: Int): Int = when (index) {
        0 -> 5
        1 -> 10
        2 -> 15
        3 -> 30
        else -> -1
    }
}

/** Snapshot of raw register words read from one device. */
data class RegisterSnapshot(
    val values: Map<Vsfr, Int>,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    fun value(r: Vsfr): Double? = values[r]?.let { RegisterCodec.decode(r.kind, it) }

    fun toJson(): String = values.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key.name}\":${it.value}" }

    companion object {
        fun fromJson(json: String): RegisterSnapshot? = try {
            val body = json.trim().removePrefix("{").removeSuffix("}")
            val map = LinkedHashMap<Vsfr, Int>()
            if (body.isNotBlank()) {
                for (part in body.split(",")) {
                    val kv = part.split(":")
                    if (kv.size != 2) continue
                    val name = kv[0].trim().trim('"')
                    val reg = values().firstOrNull { it.name == name } ?: continue
                    map[reg] = kv[1].trim().toLong().toInt()
                }
            }
            RegisterSnapshot(map)
        } catch (_: Exception) {
            null
        }

        private fun values() = Vsfr.values()
    }
}

/** Firmware version information from GET_VERSION. */
data class FirmwareInfo(
    val bootMajor: Int,
    val bootMinor: Int,
    val bootDate: String,
    val targetMajor: Int,
    val targetMinor: Int,
    val targetDate: String,
) {
    val targetVersion: String get() = "$targetMajor.${targetMinor.toString().padStart(2, '0')}"
    val bootVersion: String get() = "$bootMajor.${bootMinor.toString().padStart(2, '0')}"
}
