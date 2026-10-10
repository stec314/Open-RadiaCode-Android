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

    /**
     * Exponential continuum plus Gaussian peaks, with Poisson-like noise. With [realistic] the
     * low end has the detector threshold cut-off and the broad scatter hump a CsI detector shows.
     */
    private fun synth(peaks: List<Pair<Double, Double>>, seed: Long = 1, realistic: Boolean = false): IntArray {
        val rnd = Random(seed)
        val pf = PeakFinder()
        return IntArray(1024) { ch ->
            val e = a0 + a1 * ch + a2 * ch * ch
            var mean = 4000.0 * exp(-e / 250.0) + 30.0
            if (realistic) {
                mean += 9000.0 * exp(-0.5 * ((e - 70.0) / 30.0) * ((e - 70.0) / 30.0))
                if (e < 25.0) mean *= maxOf(0.0, (e - 10.0) / 15.0)
            }
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

    private fun ids(counts: IntArray) = PeakFinder().analyze(counts, a0, a1, a2).matches.map { it.isotope.id }

    @Test
    fun radiumDialIsRadiumNotAmericiumOrBarium() {
        val ra = listOf(77.0 to 9000.0, 186.2 to 1500.0, 242.0 to 3500.0, 295.2 to 8500.0, 351.9 to 15000.0,
            609.3 to 14000.0, 768.4 to 1300.0, 1120.3 to 3200.0, 1238.1 to 1100.0, 1764.5 to 2400.0)
        val found = ids(synth(ra, seed = 3, realistic = true))
        assertTrue("Ra-226 first: $found", found.firstOrNull() == "Ra-226")
        assertTrue("no Am-241: $found", !found.contains("Am-241"))
        assertTrue("no Ba-133: $found", !found.contains("Ba-133"))
        assertTrue("no U-238 without 1001 keV: $found", !found.contains("U-238"))
    }

    @Test
    fun scatterHumpAloneIsNotAmericium() {
        val found = ids(synth(emptyList(), seed = 11, realistic = true))
        assertTrue("false ID on background: $found", !found.contains("Am-241"))
    }

    @Test
    fun realAmericiumLineIsFound() {
        val found = ids(synth(listOf(59.5 to 60000.0), seed = 5, realistic = true))
        assertTrue("Am-241 expected: $found", found.contains("Am-241"))
    }

    @Test
    fun bariumAloneIsBarium() {
        val ba = listOf(81.0 to 9000.0, 276.4 to 1800.0, 302.9 to 4500.0, 356.0 to 15000.0, 383.8 to 2200.0)
        val found = ids(synth(ba, seed = 9))
        assertTrue("Ba-133 expected first: $found", found.firstOrNull() == "Ba-133")
        assertTrue("no Ra-226: $found", !found.contains("Ra-226"))
    }

    @Test
    fun radiumStableAcrossNoiseAndShorterCounts() {
        val ra = listOf(77.0 to 9000.0, 186.2 to 1500.0, 242.0 to 3500.0, 295.2 to 8500.0, 351.9 to 15000.0,
            609.3 to 14000.0, 768.4 to 1300.0, 1120.3 to 3200.0, 1238.1 to 1100.0, 1764.5 to 2400.0)
        for (scale in listOf(1.0, 0.3)) for (seed in 1L..20L) {
            val c = synth(ra.map { it.first to it.second * scale }, seed = seed, realistic = true)
            val found = ids(c)
            assertTrue("seed $seed scale $scale: $found", found.firstOrNull() == "Ra-226" && !found.contains("Am-241"))
        }
    }
}
