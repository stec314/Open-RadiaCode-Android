package com.radiacode.ble.pro

import android.content.Context
import com.radiacode.ble.device.ProSettings
import java.util.Locale

/** Formatting for the instrument screens: auto-ranging SI prefixes in Sv, rem or R. */
object Units {
    data class Scaled(val value: Double, val unit: String) {
        fun text(digits: Int = 3): String = "${number(value, digits)} $unit"
    }

    fun number(v: Double, sig: Int = 3): String {
        val a = kotlin.math.abs(v)
        val decimals = when {
            a == 0.0 -> 0
            a >= 100 -> (sig - 3).coerceAtLeast(0)
            a >= 10 -> (sig - 2).coerceAtLeast(0)
            a >= 1 -> (sig - 1).coerceAtLeast(0)
            else -> sig
        }
        return String.format(Locale.US, "%.${decimals}f", v)
    }

    /** Factor from uSv to the base unit (Sv, rem or R) with the device convention 1 Sv = 100 R. */
    private fun base(c: Context): Pair<Double, String> = when (ProSettings.doseSystem(c)) {
        ProSettings.DoseSystem.SIEVERT -> 1e-6 to "Sv"
        ProSettings.DoseSystem.REM -> 1e-4 to "rem"
        ProSettings.DoseSystem.ROENTGEN -> 1e-4 to "R"
    }

    private fun prefix(baseValue: Double, unit: String, suffix: String): Scaled {
        val a = kotlin.math.abs(baseValue)
        val (f, p) = when {
            a == 0.0 -> 1e9 to "n"
            a < 1e-6 -> 1e9 to "n"
            a < 1e-3 -> 1e6 to "µ"
            a < 1.0 -> 1e3 to "m"
            else -> 1.0 to ""
        }
        return Scaled(baseValue * f, "$p$unit$suffix")
    }

    fun doseRate(c: Context, uSvPerHour: Double): Scaled {
        val (k, u) = base(c)
        return prefix(uSvPerHour * k, u, "/h")
    }

    fun dose(c: Context, uSv: Double): Scaled {
        val (k, u) = base(c)
        return prefix(uSv * k, u, "")
    }

    fun temperature(c: Context, celsius: Float): String =
        if (ProSettings.temperatureFahrenheit(c)) String.format(Locale.US, "%.1f °F", celsius * 9f / 5f + 32f)
        else String.format(Locale.US, "%.1f °C", celsius)

    fun duration(seconds: Long): String {
        if (seconds < 0) return "-"
        val d = seconds / 86400
        val h = (seconds % 86400) / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            d > 0 -> String.format(Locale.US, "%dd %02d:%02d:%02d", d, h, m, s)
            else -> String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        }
    }
}
