package com.radiacode.ble

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * Map tiles without API keys. CARTO basemaps now return "API KEY REQUIRED" tiles and Stadia
 * (Stamen) needs a key too, so every theme uses OpenStreetMap tiles and the dark looks are
 * produced on the phone with a color matrix.
 */
object MapTiles {
    private val OSM = arrayOf(
        "https://a.tile.openstreetmap.org/",
        "https://b.tile.openstreetmap.org/",
        "https://c.tile.openstreetmap.org/",
    )

    fun source(@Suppress("UNUSED_PARAMETER") theme: Prefs.MapTheme): XYTileSource =
        XYTileSource("OpenStreetMap", 0, 19, 256, ".png", OSM, "© OpenStreetMap contributors")

    fun urls(@Suppress("UNUSED_PARAMETER") theme: Prefs.MapTheme): Array<String> = OSM

    /** Inverts and desaturates OSM tiles into a dark map; optional tint for the Home theme. */
    private fun darkMatrix(tintR: Float, tintG: Float, tintB: Float, brightness: Float): ColorMatrix {
        val gray = ColorMatrix().apply { setSaturation(0.15f) }
        val invert = ColorMatrix(floatArrayOf(
            -brightness, 0f, 0f, 0f, 255f * brightness,
            0f, -brightness, 0f, 0f, 255f * brightness,
            0f, 0f, -brightness, 0f, 255f * brightness,
            0f, 0f, 0f, 1f, 0f
        ))
        val tint = ColorMatrix(floatArrayOf(
            tintR, 0f, 0f, 0f, 8f,
            0f, tintG, 0f, 0f, 8f,
            0f, 0f, tintB, 0f, 10f,
            0f, 0f, 0f, 1f, 0f
        ))
        gray.postConcat(invert)
        gray.postConcat(tint)
        return gray
    }

    fun matrix(theme: Prefs.MapTheme): ColorMatrix? = when (theme) {
        Prefs.MapTheme.HOME -> darkMatrix(0.75f, 1.0f, 0.95f, 0.55f)
        Prefs.MapTheme.DARK_MATTER -> darkMatrix(0.9f, 0.9f, 1.0f, 0.6f)
        Prefs.MapTheme.DARK_GRAY -> darkMatrix(0.85f, 0.85f, 0.9f, 0.45f)
        Prefs.MapTheme.POSITRON -> ColorMatrix().apply { setSaturation(0.2f) }
        Prefs.MapTheme.TONER -> ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix(floatArrayOf(1.8f, 0f, 0f, 0f, -120f, 0f, 1.8f, 0f, 0f, -120f, 0f, 0f, 1.8f, 0f, -120f, 0f, 0f, 0f, 1f, 0f)))
        }
        Prefs.MapTheme.VOYAGER -> ColorMatrix().apply { setSaturation(0.7f) }
        Prefs.MapTheme.STANDARD -> null
    }

    fun filter(theme: Prefs.MapTheme): ColorMatrixColorFilter? = matrix(theme)?.let { ColorMatrixColorFilter(it) }

    /** Applies the theme look to a bitmap in place (used by the widget tile renderer). */
    fun applyToBitmap(theme: Prefs.MapTheme, bitmap: Bitmap) {
        val f = filter(theme) ?: return
        if (!bitmap.isMutable) return
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Canvas(bitmap).drawBitmap(copy, 0f, 0f, Paint().apply { colorFilter = f })
        copy.recycle()
    }
}
