package com.radiacode.ble.analysis

import com.radiacode.ble.IsotopeLibrary
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Offline peak search and library matching. Runs entirely on the phone.
 *
 * Method: for every channel a peak window of about one FWHM is compared with a linear
 * background estimated from two side windows. Channels whose net counts exceed
 * [minSignificance] standard deviations of the background, and that are local maxima of
 * that significance, become peaks. Peaks are then matched to library gamma lines.
 *
 * This is a classic counting-statistics approach: transparent and fast, but it cannot
 * resolve overlapping lines or estimate activities the way a full spectral fit would.
 */
class PeakFinder(
    /** Relative FWHM at 662 keV (CsI(Tl) RadiaCode 10x/110 ~0.084, GAGG ~0.074). */
    private val resolutionAt662: Double = 0.084,
    private val minSignificance: Double = 4.0,
    private val minEnergyKeV: Double = 25.0,
) {
    data class Peak(
        val channel: Double,
        val energyKeV: Double,
        val netCounts: Double,
        val netUncertainty: Double,
        val significance: Double,
        val fwhmKeV: Double,
    )

    data class LineMatch(val line: IsotopeLibrary.GammaLine, val peak: Peak?)

    data class IsotopeMatch(
        val isotope: IsotopeLibrary.Isotope,
        val score: Double,
        val lines: List<LineMatch>,
    ) {
        val matchedCount: Int get() = lines.count { it.peak != null }
    }

    data class Result(val peaks: List<Peak>, val matches: List<IsotopeMatch>)

    fun fwhmKeV(e: Double): Double = resolutionAt662 * 662.0 * sqrt(max(e, 1.0) / 662.0)

    fun analyze(counts: IntArray, a0: Double, a1: Double, a2: Double): Result {
        val peaks = findPeaks(counts, a0, a1, a2)
        return Result(peaks, match(peaks))
    }

    fun findPeaks(counts: IntArray, a0: Double, a1: Double, a2: Double): List<Peak> {
        val n = counts.size
        if (n < 32 || a1 <= 0.0) return emptyList()
        fun energy(ch: Double) = a0 + a1 * ch + a2 * ch * ch
        fun keVPerChannel(ch: Double) = a1 + 2 * a2 * ch

        val sig = DoubleArray(n)
        val net = DoubleArray(n)
        val unc = DoubleArray(n)
        val half = IntArray(n)
        for (c in 0 until n) {
            val e = energy(c.toDouble())
            if (e < minEnergyKeV) continue
            val wCh = max(1, (fwhmKeV(e) / keVPerChannel(c.toDouble()) / 2.0).toInt())
            val lo = c - wCh
            val hi = c + wCh
            val bw = max(2, wCh)
            if (lo - bw < 0 || hi + bw >= n) continue
            var peakSum = 0.0
            for (i in lo..hi) peakSum += counts[i]
            var left = 0.0
            for (i in lo - bw until lo) left += counts[i]
            var right = 0.0
            for (i in hi + 1..hi + bw) right += counts[i]
            val width = (hi - lo + 1).toDouble()
            val bg = (left + right) / (2.0 * bw) * width
            val nn = peakSum - bg
            val variance = peakSum + bg * width / (2.0 * bw)
            val s = if (variance > 0) nn / sqrt(variance) else 0.0
            sig[c] = s
            net[c] = nn
            unc[c] = sqrt(max(variance, 0.0))
            half[c] = wCh
        }

        val peaks = ArrayList<Peak>()
        var c = 1
        while (c < n - 1) {
            val s = sig[c]
            if (s >= minSignificance && s >= sig[c - 1] && s >= sig[c + 1]) {
                // Centroid over the peak window
                val w = half[c]
                var sw = 0.0
                var sx = 0.0
                for (i in max(0, c - w)..min(n - 1, c + w)) {
                    val y = counts[i].toDouble()
                    sw += y
                    sx += y * i
                }
                val centroid = if (sw > 0) sx / sw else c.toDouble()
                val e = energy(centroid)
                peaks.add(Peak(centroid, e, net[c], unc[c], s, fwhmKeV(e)))
                c += max(1, w) // skip the rest of this peak
            } else {
                c++
            }
        }
        // Merge peaks closer than half a FWHM (keep the more significant one)
        val merged = ArrayList<Peak>()
        for (p in peaks.sortedBy { it.energyKeV }) {
            val last = merged.lastOrNull()
            if (last != null && abs(p.energyKeV - last.energyKeV) < 0.5 * p.fwhmKeV) {
                if (p.significance > last.significance) merged[merged.size - 1] = p
            } else merged.add(p)
        }
        return merged
    }

    fun match(peaks: List<Peak>): List<IsotopeMatch> {
        val out = ArrayList<IsotopeMatch>()
        for (iso in IsotopeLibrary.ALL_ISOTOPES) {
            val lines = iso.gammaLines.filter { it.energyKeV >= minEnergyKeV && it.intensity >= 0.02f }
            if (lines.isEmpty()) continue
            val matches = lines.map { line ->
                val tol = max(0.6 * fwhmKeV(line.energyKeV.toDouble()), 4.0)
                val p = peaks.filter { abs(it.energyKeV - line.energyKeV) <= tol }.maxByOrNull { it.significance }
                LineMatch(line, p)
            }
            val primary = iso.primaryLine
            val primaryHit = matches.firstOrNull { it.line == primary }?.peak != null
            if (!primaryHit) continue
            val totalIntensity = lines.sumOf { it.intensity.toDouble() }
            val matchedIntensity = matches.filter { it.peak != null }.sumOf { it.line.intensity.toDouble() }
            val coverage = if (totalIntensity > 0) matchedIntensity / totalIntensity else 0.0
            val strength = matches.mapNotNull { it.peak?.significance }.maxOrNull() ?: 0.0
            val score = (coverage * 0.7 + min(strength / 20.0, 1.0) * 0.3).coerceIn(0.0, 1.0)
            out.add(IsotopeMatch(iso, score, matches))
        }
        return out.sortedByDescending { it.score }
    }
}
