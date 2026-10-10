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
    private val minEnergyKeV: Double = 30.0,
    /** Peaks wider than this multiple of the expected FWHM are treated as continuum. */
    private val maxWidthFactor: Double = 2.0,
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
                // Reject broad continuum features (low-energy hump, Compton edges) that pass the
                // significance test but are much wider than the detector resolution allows.
                val widthKeV = measuredFwhmKeV(counts, c, w) * keVPerChannel(c.toDouble())
                val expected = fwhmKeV(e)
                if (widthKeV <= 0.0 || widthKeV <= maxWidthFactor * expected) {
                    peaks.add(Peak(centroid, e, net[c], unc[c], s, expected))
                }
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

    /**
     * Full width at half maximum in channels of the net peak around [c], using a linear
     * baseline through the side windows. Returns -1 when it cannot be measured.
     */
    private fun measuredFwhmChannels(counts: IntArray, c: Int, w: Int): Double {
        val n = counts.size
        val span = 4 * max(w, 2)
        val lo = c - span
        val hi = c + span
        if (lo < 1 || hi >= n - 1) return -1.0
        fun sm(i: Int) = (counts[i - 1] + 2.0 * counts[i] + counts[i + 1]) / 4.0
        val bw = max(2, w / 2)
        var left = 0.0
        for (i in lo until lo + bw) left += sm(i)
        left /= bw
        var right = 0.0
        for (i in hi - bw + 1..hi) right += sm(i)
        right /= bw
        fun base(i: Int) = left + (right - left) * (i - lo).toDouble() / (hi - lo)
        var top = c
        for (i in c - w..c + w) if (sm(i) - base(i) > sm(top) - base(top)) top = i
        val height = sm(top) - base(top)
        if (height <= 0) return -1.0
        val half = height / 2.0
        var l = top
        while (l > lo && sm(l) - base(l) > half) l--
        var r = top
        while (r < hi && sm(r) - base(r) > half) r++
        if (l == lo || r == hi) return (hi - lo).toDouble() // never came down: very broad
        return (r - l).toDouble()
    }

    private fun measuredFwhmKeV(counts: IntArray, c: Int, w: Int): Double = measuredFwhmChannels(counts, c, w)

    /**
     * Library matching with explanation: isotopes are accepted best-first, and the peaks an
     * accepted isotope explains no longer count as evidence for the others. This keeps, for
     * example, Ba-133 (356/303/81 keV) from being reported on a radium source whose
     * Pb-214 lines (352/295 keV) and lead x-rays sit at the same places.
     */
    fun match(peaks: List<Peak>): List<IsotopeMatch> {
        data class Cand(val iso: IsotopeLibrary.Isotope, val lines: List<LineMatch>)
        val cands = ArrayList<Cand>()
        for (iso in IsotopeLibrary.ALL_ISOTOPES) {
            val primary = iso.primaryLine
            val lines = iso.gammaLines.filter { it.energyKeV >= minEnergyKeV && (it.intensity >= 0.02f || it == primary) }
            if (lines.isEmpty()) continue
            val matches = lines.map { line ->
                val tol = max(0.5 * fwhmKeV(line.energyKeV.toDouble()), 4.0)
                val p = peaks.filter { abs(it.energyKeV - line.energyKeV) <= tol }.maxByOrNull { it.significance }
                LineMatch(line, p)
            }
            if (matches.none { it.peak != null }) continue
            cands.add(Cand(iso, matches))
        }

        val claimed = HashSet<Peak>()
        val accepted = ArrayList<IsotopeMatch>()
        val remaining = cands.toMutableList()
        while (remaining.isNotEmpty()) {
            // Pick the isotope that explains the most evidence (coverage x summed significance),
            // so a source with many matching lines wins over one that fits a subset of them.
            var best: Triple<Cand, Double, Double>? = null
            for (cand in remaining) {
                val sc = score(cand.iso, cand.lines, claimed) ?: continue
                if (sc < MIN_SCORE) continue
                val free = cand.lines.filter { it.peak != null && it.peak !in claimed }
                val key = sc * free.sumOf { it.peak!!.significance }
                if (best == null || key > best.third) best = Triple(cand, sc, key)
            }
            val b = best ?: break
            accepted.add(IsotopeMatch(b.first.iso, b.second, b.first.lines))
            b.first.lines.mapNotNullTo(claimed) { it.peak }
            remaining.remove(b.first)
        }
        return accepted
    }

    /** Score from unexplained peaks only; null when the primary line has no free evidence. */
    private fun score(iso: IsotopeLibrary.Isotope, lines: List<LineMatch>, claimed: Set<Peak>): Double? {
        // A peak sitting on the shoulder of an explained peak is not independent evidence
        fun shoulder(p: Peak) = claimed.any { it !== p && abs(it.energyKeV - p.energyKeV) < it.fwhmKeV }
        val free = lines.filter { it.peak != null && it.peak !in claimed && !shoulder(it.peak) }
        // Single-line isotopes rely on one peak: demand a clearly significant one
        // (higher bar below 120 keV, where scatter and lead x-rays shape the continuum)
        if (lines.size == 1) {
            val p = free.firstOrNull()?.peak ?: return null
            val need = if (p.energyKeV < 120.0) 10.0 else 6.0
            if (p.significance < need) return null
        }
        val primaryFree = free.any { it.line == iso.primaryLine }
        // Accept without a free primary only if two or more other lines are unexplained
        if (!primaryFree && free.size < 2) return null
        if (lines.firstOrNull { it.line == iso.primaryLine }?.peak == null) return null
        val total = lines.sumOf { it.line.intensity.toDouble() }
        val found = free.sumOf { it.line.intensity.toDouble() }
        val coverage = if (total > 0) found / total else 0.0
        val strength = free.mapNotNull { it.peak?.significance }.maxOrNull() ?: 0.0
        return (coverage * 0.7 + min(strength / 20.0, 1.0) * 0.3).coerceIn(0.0, 1.0)
    }

    companion object {
        const val MIN_SCORE = 0.25
    }
}
