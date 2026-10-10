package com.radiacode.ble

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Isotope identification engine using Region of Interest (ROI) analysis.
 * Analyzes gamma spectra to identify probable isotopes based on characteristic peaks.
 * 
 * Algorithm overview:
 * 1. For each enabled isotope, define energy windows around known gamma lines
 * 2. Sum counts in each ROI and compare to local background
 * 3. Calculate probability scores using sigmoid activation
 * 4. Compute fractional contributions (normalized to sum ~1)
 */
class IsotopeDetector(
    private val enabledIsotopes: Set<String> = IsotopeLibrary.DEFAULT_ENABLED
) {
    companion object {
        private const val TAG = "IsotopeDetector"
        
        // ROI window parameters
        private const val PEAK_WINDOW_KEV = 25f       // ± keV around peak center
        private const val BACKGROUND_MARGIN_KEV = 15f // Margin between peak and background regions
        private const val BACKGROUND_WIDTH_KEV = 30f  // Width of background regions
        
        // Probability calculation parameters
        private const val SIGMOID_SCALE = 35f         // Steepness of sigmoid
        private const val SIGMOID_OFFSET = 0.8f       // Threshold offset
        
        // Minimum counts threshold
        private const val MIN_PEAK_COUNTS = 10
    }
    
    /**
     * Result of isotope identification.
     */
    data class Prediction(
        /** Isotope identifier */
        val isotopeId: String,
        /** Display name */
        val name: String,
        /** Category */
        val category: IsotopeLibrary.Category,
        /** Probability score (0-1) */
        val probability: Float,
        /** Fractional contribution to spectrum (0-1) */
        val fraction: Float,
        /** Raw evidence score (counts above background) */
        val evidence: Float,
        /** Primary peak energy (keV) */
        val primaryPeakKeV: Float,
        /** Whether this is a decay chain (detected via daughters) */
        val isDecayChain: Boolean
    )
    
    /**
     * Full analysis result.
     */
    data class AnalysisResult(
        /** All predictions, sorted by probability */
        val predictions: List<Prediction>,
        /** Top 5 predictions */
        val topFive: List<Prediction>,
        /** Timestamp of analysis */
        val timestampMs: Long,
        /** Total spectrum counts */
        val totalCounts: Long,
        /** Spectrum duration (seconds) */
        val durationSeconds: Int
    ) {
        /** Get prediction for specific isotope */
        fun getPrediction(isotopeId: String): Prediction? = 
            predictions.find { it.isotopeId == isotopeId }
    }
    
    /**
     * Analyze a spectrum and identify probable isotopes.
     *
     * Uses the shared offline engine (analysis.PeakFinder): resolution-aware peak search,
     * rejection of broad continuum features, and library matching where peaks explained by
     * one isotope are not reused as evidence for another. The previous fixed-window ROI
     * method reported Am-241 on almost any spectrum because the 59.5 keV window sits on the
     * low-energy scatter hump of the detector.
     */
    fun analyze(spectrum: SpectrumData): AnalysisResult {
        val engine = com.radiacode.ble.analysis.PeakFinder()
        val result = engine.analyze(spectrum.counts, spectrum.a0.toDouble(), spectrum.a1.toDouble(), spectrum.a2.toDouble())
        val matches = result.matches.filter { it.isotope.id in enabledIsotopes }

        // Net counts explained by each isotope (each peak counted once, for the first isotope that claims it)
        val used = HashSet<com.radiacode.ble.analysis.PeakFinder.Peak>()
        val netByIso = LinkedHashMap<String, Double>()
        for (m in matches) {
            var net = 0.0
            for (l in m.lines) {
                val p = l.peak ?: continue
                if (used.add(p)) net += maxOf(0.0, p.netCounts)
            }
            netByIso[m.isotope.id] = net
        }
        val allPeaksNet = result.peaks.sumOf { maxOf(0.0, it.netCounts) }
        val explained = netByIso.values.sum()
        val denom = maxOf(allPeaksNet, explained, 1.0)

        val results = mutableListOf<Prediction>()
        for (isotopeId in enabledIsotopes) {
            val isotope = IsotopeLibrary.get(isotopeId) ?: continue
            val m = matches.firstOrNull { it.isotope.id == isotopeId }
            val evidence = m?.lines?.mapNotNull { it.peak?.significance }?.sum()?.toFloat() ?: 0f
            results.add(Prediction(
                isotopeId = isotope.id,
                name = isotope.name,
                category = isotope.category,
                probability = (m?.score ?: 0.0).toFloat(),
                fraction = ((netByIso[isotopeId] ?: 0.0) / denom).toFloat(),
                evidence = evidence,
                primaryPeakKeV = isotope.primaryLine.energyKeV,
                isDecayChain = isotope.parentChain != null || isotope.id.contains("Chain") || isotope.gammaLines.size > 2
            ))
        }
        val unknownFraction = if (allPeaksNet <= 0.0) 1f else (1.0 - explained / denom).toFloat().coerceIn(0f, 1f)
        results.add(Prediction(
            isotopeId = "Unknown",
            name = "Background/Unknown",
            category = IsotopeLibrary.Category.NATURAL,
            probability = max(0f, 1f - (results.maxOfOrNull { it.probability } ?: 0f)),
            fraction = unknownFraction,
            evidence = 0f,
            primaryPeakKeV = 0f,
            isDecayChain = false
        ))

        android.util.Log.d(TAG, "Analysis: ${result.peaks.size} peaks, matches=${matches.map { "${it.isotope.id}:${"%.2f".format(it.score)}" }}")
        val sorted = results.sortedByDescending { it.probability }
        return AnalysisResult(
            predictions = sorted,
            topFive = sorted.take(5),
            timestampMs = System.currentTimeMillis(),
            totalCounts = spectrum.totalCounts,
            durationSeconds = spectrum.durationSeconds
        )
    }

    /**
     * Quick single-isotope check (e.g., for specific alerting).
     */
    fun checkIsotope(spectrum: SpectrumData, isotopeId: String): Prediction? =
        analyze(spectrum).getPrediction(isotopeId)
}

/**
 * History of isotope predictions for time-series display.
 */
class IsotopePredictionHistory(
    private val maxSize: Int = 300  // ~5 minutes at 1Hz
) {
    private val history = mutableListOf<IsotopeDetector.AnalysisResult>()
    
    /**
     * Add a new analysis result to history.
     */
    fun add(result: IsotopeDetector.AnalysisResult) {
        synchronized(history) {
            history.add(result)
            while (history.size > maxSize) {
                history.removeAt(0)
            }
        }
    }
    
    /**
     * Clear all history.
     */
    fun clear() {
        synchronized(history) {
            history.clear()
        }
    }
    
    /**
     * Get all history entries.
     */
    fun getAll(): List<IsotopeDetector.AnalysisResult> {
        synchronized(history) {
            return history.toList()
        }
    }
    
    /**
     * Get history within a time window.
     */
    fun getRecent(windowMs: Long): List<IsotopeDetector.AnalysisResult> {
        val cutoff = System.currentTimeMillis() - windowMs
        synchronized(history) {
            return history.filter { it.timestampMs >= cutoff }
        }
    }
    
    /**
     * Get time series data for a specific isotope.
     * Returns list of (timestamp, probability) pairs.
     */
    fun getTimeSeries(isotopeId: String, windowMs: Long): List<Pair<Long, Float>> {
        val cutoff = System.currentTimeMillis() - windowMs
        synchronized(history) {
            return history
                .filter { it.timestampMs >= cutoff }
                .mapNotNull { result ->
                    result.getPrediction(isotopeId)?.let { pred ->
                        result.timestampMs to pred.probability
                    }
                }
        }
    }
    
    /**
     * Get fraction time series for a specific isotope.
     */
    fun getFractionTimeSeries(isotopeId: String, windowMs: Long): List<Pair<Long, Float>> {
        val cutoff = System.currentTimeMillis() - windowMs
        synchronized(history) {
            return history
                .filter { it.timestampMs >= cutoff }
                .mapNotNull { result ->
                    result.getPrediction(isotopeId)?.let { pred ->
                        result.timestampMs to pred.fraction
                    }
                }
        }
    }
    
    /**
     * Get current top N isotopes by probability.
     */
    fun getCurrentTop(n: Int = 5): List<IsotopeDetector.Prediction> {
        synchronized(history) {
            return history.lastOrNull()?.topFive?.take(n) ?: emptyList()
        }
    }
    
    val size: Int get() = synchronized(history) { history.size }
    
    val isEmpty: Boolean get() = synchronized(history) { history.isEmpty() }
}
