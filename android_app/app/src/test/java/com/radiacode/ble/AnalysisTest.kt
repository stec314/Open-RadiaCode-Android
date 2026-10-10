package com.radiacode.ble

import com.radiacode.ble.analysis.PeakFinder
import com.radiacode.ble.analysis.SpectrumFiles
import com.radiacode.ble.analysis.StoredSpectrum
import com.radiacode.ble.pro.CalibrationFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.exp
import kotlin.math.sqrt

class AnalysisTest {
    // RadiaCode-like calibration: ~3 keV per channel
    private val a0 = -5.0
    private val a1 = 2.95
    private val a2 = 0.00035

    private fun channelOf(e: Double): Double {
        val d = a1 * a1 - 4 * a2 * (a0 - e)
        return (-a1 + sqrt(d)) / (2 * a2)
    }

    /** Exponential continuum plus Gaussian peaks, with Poisson-like noise. */
    private fun synth(peaks: List<Pair<Double, Double>>, seed: Long = 1): IntArray {
        val rnd = Random(seed)
        val pf = PeakFinder()
        return IntArray(1024) { ch ->
            val e = a0 + a1 * ch + a2 * ch * ch
            var mean = 4000.0 * exp(-e / 250.0) + 30.0
            for ((pe, area) in peaks) {
                val sigmaKeV = pf.fwhmKeV(pe) / 2.355
                val keVPerCh = a1 + 2 * a2 * ch
                mean += area * keVPerCh / (sigmaKeV * sqrt(2 * Math.PI)) * exp(-0.5 * ((e - pe) / sigmaKeV) * ((e - pe) / sigmaKeV))
            }
            (mean + rnd.nextGaussian() * sqrt(mean)).toInt().coerceAtLeast(0)
        }
    }

    @Test
    fun findsCs137AndK40() {
        val counts = synth(listOf(661.7 to 20000.0, 1460.8 to 4000.0))
        val res = PeakFinder().analyze(counts, a0, a1, a2)
        assertTrue("peak near 662: ${res.peaks.map { it.energyKeV }}", res.peaks.any { kotlin.math.abs(it.energyKeV - 661.7) < 15 })
        assertTrue("peak near 1461", res.peaks.any { kotlin.math.abs(it.energyKeV - 1460.8) < 25 })
        val ids = res.matches.map { it.isotope.id }
        assertTrue("Cs-137 matched: $ids", ids.contains("Cs-137"))
        assertTrue("K-40 matched: $ids", ids.contains("K-40"))
    }

    @Test
    fun flatSpectrumHasNoStrongPeaks() {
        val res = PeakFinder().analyze(synth(emptyList(), seed = 7), a0, a1, a2)
        assertTrue("false peaks: ${res.peaks.map { it.energyKeV.toInt() }}", res.peaks.size <= 2)
    }

    @Test
    fun n42RoundTrip() {
        val counts = IntArray(1024) { it % 50 }
        val s = StoredSpectrum("Test & <spectrum>", 1_700_000_000_000L, 3600.0, -5.0, 2.95, 0.00035, counts, serial = "RC-110-000123")
        val xml = SpectrumFiles.toN42(s)
        assertTrue(xml.contains("<ChannelData compressionCode=\"None\">"))
        assertTrue(xml.contains("&amp;"))
        val back = SpectrumFiles.fromN42(xml)
        assertNotNull(back)
        assertEquals(1024, back!!.counts.size)
        assertEquals(49, back.counts[49])
        assertEquals(3600.0, back.liveSeconds, 0.01)
        assertEquals(2.95, back.a1, 1e-6)
        assertEquals("Test & <spectrum>".length, back.title.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").length)
    }

    @Test
    fun durationParsing() {
        assertEquals(3723.5, SpectrumFiles.parseDuration("PT1H2M3.5S"), 1e-9)
        assertEquals(60.0, SpectrumFiles.parseDuration("PT60S"), 1e-9)
    }

    @Test
    fun backgroundSubtractionScalesByLiveTime() {
        val fg = StoredSpectrum("fg", 0, 100.0, 0.0, 3.0, 0.0, intArrayOf(100, 50))
        val bg = StoredSpectrum("bg", 0, 200.0, 0.0, 3.0, 0.0, intArrayOf(100, 100))
        val net = SpectrumFiles.subtract(fg, bg)
        assertEquals(50.0, net[0], 1e-9)
        assertEquals(0.0, net[1], 1e-9)
        val sm = SpectrumFiles.smooth(doubleArrayOf(0.0, 3.0, 0.0), 3)
        assertEquals(1.0, sm[1], 1e-9)
    }

    @Test
    fun calibrationFitLinearAndQuadratic() {
        val lin = CalibrationFit.fit(listOf(226.0 to 661.7, 494.0 to 1460.8))!!
        assertEquals(661.7, lin[0] + lin[1] * 226.0, 1e-6)
        val q = CalibrationFit.fit(listOf(100.0 to 300.0, 200.0 to 610.0, 400.0 to 1260.0))!!
        assertEquals(610.0, q[0] + q[1] * 200 + q[2] * 200 * 200, 1e-6)
        assertEquals(1260.0, q[0] + q[1] * 400 + q[2] * 400 * 400, 1e-6)
    }
}
