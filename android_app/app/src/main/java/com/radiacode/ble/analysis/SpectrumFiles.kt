package com.radiacode.ble.analysis

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A spectrum held in memory or on disk, independent of the device classes. */
data class StoredSpectrum(
    val title: String,
    val startMs: Long,
    val liveSeconds: Double,
    val a0: Double,
    val a1: Double,
    val a2: Double,
    val counts: IntArray,
    val instrument: String = "RadiaCode",
    val serial: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val total: Long get() = counts.sumOf { it.toLong() }
    fun energy(ch: Double) = a0 + a1 * ch + a2 * ch * ch
}

/** ANSI N42.42-2011 export and import, plus a plain CSV export. */
object SpectrumFiles {
    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun toN42(s: StoredSpectrum): String {
        val start = Instant.ofEpochMilli(s.startMs).atOffset(ZoneOffset.UTC).format(ISO)
        val dur = String.format(Locale.US, "PT%.1fS", s.liveSeconds)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<RadInstrumentData xmlns=\"http://physics.nist.gov/N42/2011/N42\" n42DocUUID=\"")
            .append(java.util.UUID.nameUUIDFromBytes("${s.startMs}-${s.total}".toByteArray())).append("\">\n")
        sb.append("  <RadInstrumentDataCreatorName>Open RadiaCode</RadInstrumentDataCreatorName>\n")
        sb.append("  <RadInstrumentInformation id=\"RadInstrumentInformation-1\">\n")
        sb.append("    <RadInstrumentManufacturerName>Scan-Electronics</RadInstrumentManufacturerName>\n")
        sb.append("    <RadInstrumentModelName>").append(esc(s.instrument)).append("</RadInstrumentModelName>\n")
        if (s.serial.isNotBlank()) sb.append("    <RadInstrumentIdentifier>").append(esc(s.serial)).append("</RadInstrumentIdentifier>\n")
        sb.append("    <RadInstrumentClassCode>Spectroscopic Personal Radiation Detector</RadInstrumentClassCode>\n")
        sb.append("  </RadInstrumentInformation>\n")
        sb.append("  <RadDetectorInformation id=\"RadDetectorInformation-1\">\n")
        sb.append("    <RadDetectorCategoryCode>Gamma</RadDetectorCategoryCode>\n")
        sb.append("    <RadDetectorKindCode>CsI</RadDetectorKindCode>\n")
        sb.append("  </RadDetectorInformation>\n")
        sb.append("  <EnergyCalibration id=\"EnergyCalibration-1\">\n")
        sb.append("    <CoefficientValues>")
            .append(String.format(Locale.US, "%.6g %.6g %.6g", s.a0, s.a1, s.a2))
            .append("</CoefficientValues>\n")
        sb.append("  </EnergyCalibration>\n")
        sb.append("  <RadMeasurement id=\"RadMeasurement-1\">\n")
        sb.append("    <MeasurementClassCode>Foreground</MeasurementClassCode>\n")
        sb.append("    <StartDateTime>").append(start).append("</StartDateTime>\n")
        sb.append("    <RealTimeDuration>").append(dur).append("</RealTimeDuration>\n")
        sb.append("    <Spectrum id=\"Spectrum-1\" radDetectorInformationReference=\"RadDetectorInformation-1\" energyCalibrationReference=\"EnergyCalibration-1\">\n")
        sb.append("      <Remark>").append(esc(s.title)).append("</Remark>\n")
        sb.append("      <LiveTimeDuration>").append(dur).append("</LiveTimeDuration>\n")
        sb.append("      <ChannelData compressionCode=\"None\">")
        s.counts.forEachIndexed { i, c ->
            if (i % 16 == 0) sb.append("\n        ")
            sb.append(c).append(' ')
        }
        sb.append("\n      </ChannelData>\n")
        sb.append("    </Spectrum>\n")
        if (s.latitude != null && s.longitude != null) {
            sb.append("    <RadInstrumentState>\n      <StateVector>\n        <GeographicPoint>\n")
            sb.append(String.format(Locale.US, "          <LatitudeValue>%.6f</LatitudeValue>\n          <LongitudeValue>%.6f</LongitudeValue>\n", s.latitude, s.longitude))
            sb.append("        </GeographicPoint>\n      </StateVector>\n    </RadInstrumentState>\n")
        }
        sb.append("  </RadMeasurement>\n")
        sb.append("</RadInstrumentData>\n")
        return sb.toString()
    }

    /** Minimal N42 reader for files written by [toN42] and most single-spectrum N42 files. */
    fun fromN42(xml: String, fallbackTitle: String = "Imported"): StoredSpectrum? {
        fun tag(name: String): String? {
            val m = Regex("<(?:\\w+:)?$name\\b[^>]*>([\\s\\S]*?)</(?:\\w+:)?$name>").find(xml) ?: return null
            return m.groupValues[1].trim()
        }
        val data = tag("ChannelData") ?: return null
        val counts = data.split(Regex("\\s+")).filter { it.isNotBlank() }.mapNotNull { it.toDoubleOrNull()?.toInt() }.toIntArray()
        if (counts.isEmpty()) return null
        val coef = tag("CoefficientValues")?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() } ?: listOf(0.0, 3.0, 0.0)
        val live = parseDuration(tag("LiveTimeDuration") ?: tag("RealTimeDuration") ?: "PT0S")
        val start = tag("StartDateTime")?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
        return StoredSpectrum(
            title = tag("Remark") ?: fallbackTitle,
            startMs = start,
            liveSeconds = live,
            a0 = coef.getOrElse(0) { 0.0 },
            a1 = coef.getOrElse(1) { 3.0 },
            a2 = coef.getOrElse(2) { 0.0 },
            counts = counts,
            instrument = tag("RadInstrumentModelName") ?: "RadiaCode",
        )
    }

    fun parseDuration(iso: String): Double {
        val m = Regex("PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?").find(iso.trim()) ?: return 0.0
        val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val mi = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
        return h * 3600 + mi * 60 + s
    }

    fun toCsv(s: StoredSpectrum): String {
        val sb = StringBuilder("channel,energy_keV,counts\n")
        s.counts.forEachIndexed { i, c -> sb.append(i).append(',').append(String.format(Locale.US, "%.2f", s.energy(i.toDouble()))).append(',').append(c).append('\n') }
        return sb.toString()
    }

    /**
     * Background-subtracted counts: foreground minus background scaled by live time.
     * Negative values are kept (they carry the statistical noise); callers clamp for display.
     */
    fun subtract(fg: StoredSpectrum, bg: StoredSpectrum): DoubleArray {
        val k = if (bg.liveSeconds > 0) fg.liveSeconds / bg.liveSeconds else 0.0
        return DoubleArray(fg.counts.size) { i -> fg.counts[i] - k * (bg.counts.getOrElse(i) { 0 }) }
    }

    /** Centered moving average with a window of [window] channels (shrinks at the edges). */
    fun smooth(values: DoubleArray, window: Int): DoubleArray {
        if (window <= 1 || values.isEmpty()) return values
        val h = window / 2
        val prefix = DoubleArray(values.size + 1)
        for (i in values.indices) prefix[i + 1] = prefix[i] + values[i]
        return DoubleArray(values.size) { i ->
            val lo = maxOf(0, i - h)
            val hi = minOf(values.size - 1, i + h)
            (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
        }
    }
}
