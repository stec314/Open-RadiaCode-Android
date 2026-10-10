package com.radiacode.ble.device

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Pure decoders for device responses. Kept free of Android APIs so they can be
 * covered by JVM unit tests with captured or hand-built frames.
 */
object ResponseParsers {

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    /** RD_VIRT_STRING response: <I retcode><I flen> + flen bytes. */
    fun virtString(resp: ByteArray, what: String = "VS"): ByteArray {
        require(resp.size >= 8) { "$what: response too short (${resp.size})" }
        val bb = le(resp)
        val ret = bb.int
        val flen = bb.int
        check(ret == 1) { "$what: read failed ret=$ret" }
        check(flen >= 0 && resp.size >= 8 + flen) { "$what: bad length $flen" }
        val out = ByteArray(flen)
        bb.get(out)
        return out
    }

    /** Responses to writes: <I retcode> where 1 means success. */
    fun retcodeOk(resp: ByteArray): Boolean {
        if (resp.size < 4) return false
        return le(resp).int == 1
    }

    /**
     * RD_VIRT_SFR_BATCH response: <I validFlags> followed by one 4-byte word per register.
     * Returns null for registers the device flagged as invalid.
     */
    fun batchRead(resp: ByteArray, count: Int): List<Int?> {
        require(resp.size >= 4 + 4 * count) { "batch read: response too short (${resp.size}) for $count registers" }
        val bb = le(resp)
        val flags = bb.int
        return (0 until count).map { i ->
            val word = bb.int
            if ((flags ushr i) and 1 == 1) word else null
        }
    }

    /** Request payload for RD_VIRT_SFR_BATCH: <I n> + n * <I id>. */
    fun batchReadRequest(ids: List<Int>): ByteArray {
        val out = ByteArray(4 + 4 * ids.size)
        val bb = le(out)
        bb.putInt(ids.size)
        ids.forEach { bb.putInt(it) }
        return out
    }

    /** GET_VERSION response: <HH> boot minor/major, <B len> date, <HH> target minor/major, <B len> date. */
    fun firmwareVersion(resp: ByteArray): FirmwareInfo {
        val bb = le(resp)
        val bootMinor = bb.short.toInt() and 0xFFFF
        val bootMajor = bb.short.toInt() and 0xFFFF
        val bootDate = readShortString(bb)
        val targetMinor = bb.short.toInt() and 0xFFFF
        val targetMajor = bb.short.toInt() and 0xFFFF
        val targetDate = readShortString(bb)
        return FirmwareInfo(bootMajor, bootMinor, bootDate, targetMajor, targetMinor, targetDate)
    }

    private fun readShortString(bb: ByteBuffer): String {
        if (!bb.hasRemaining()) return ""
        val len = bb.get().toInt() and 0xFF
        val n = minOf(len, bb.remaining())
        val raw = ByteArray(n)
        bb.get(raw)
        return String(raw, Charsets.US_ASCII).trim { it <= ' ' }
    }

    /** GET_SERIAL response: <I len> then len bytes read as little-endian words, printed as hex groups. */
    fun hardwareSerial(resp: ByteArray): String {
        val bb = le(resp)
        val len = bb.int
        val groups = mutableListOf<String>()
        var left = minOf(len, bb.remaining())
        while (left >= 4) {
            groups.add(String.format("%08X", bb.int))
            left -= 4
        }
        return groups.joinToString("-")
    }

    fun ascii(bytes: ByteArray): String = String(bytes, Charsets.US_ASCII).trim { it <= ' ' }

    /** VS.CONFIGURATION is a CP1251 text block of key=value lines. */
    fun configuration(bytes: ByteArray): Map<String, String> {
        val text = try {
            String(bytes, Charset.forName("windows-1251"))
        } catch (_: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
        val out = LinkedHashMap<String, String>()
        text.split('\n', '\r').forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) out[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return out
    }
}

/** Decoder for spectrum count payloads (format version 0 and the RLE/delta version 1). */
object SpectrumCodec {
    const val CHANNELS = 1024

    /** Spectrum VS payload: <I duration><fff a0 a1 a2> + counts. */
    data class Decoded(val durationSeconds: Int, val a0: Float, val a1: Float, val a2: Float, val counts: IntArray)

    fun decodeSpectrum(payload: ByteArray): Decoded {
        require(payload.size >= 16) { "Spectrum data too short (${payload.size})" }
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val duration = bb.int
        val a0 = bb.float
        val a1 = bb.float
        val a2 = bb.float
        val counts = decodeCounts(payload, 16, payload.size - 16)
        return Decoded(duration, a0, a1, a2, counts)
    }

    fun decodeCounts(data: ByteArray, offset: Int, length: Int): IntArray {
        val bb = ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        if (length == CHANNELS * 4) {
            return IntArray(CHANNELS) { bb.int }
        }
        return decodeRle(bb, offset + length)
    }

    private fun decodeRle(bb: ByteBuffer, endPos: Int): IntArray {
        val result = ArrayList<Int>(CHANNELS)
        var last = 0
        outer@ while (endPos - bb.position() >= 2) {
            val u16 = bb.short.toInt() and 0xFFFF
            val count = (u16 ushr 4) and 0x0FFF
            val vlen = u16 and 0x0F
            for (i in 0 until count) {
                val remaining = endPos - bb.position()
                val v = when (vlen) {
                    0 -> 0
                    1 -> { if (remaining < 1) break@outer; bb.get().toInt() and 0xFF }
                    2 -> { if (remaining < 1) break@outer; last + bb.get().toInt() }
                    3 -> { if (remaining < 2) break@outer; last + bb.short.toInt() }
                    4 -> {
                        if (remaining < 3) break@outer
                        val a = bb.get().toInt() and 0xFF
                        val b = bb.get().toInt() and 0xFF
                        val c = bb.get().toInt()
                        last + ((c shl 16) or (b shl 8) or a)
                    }
                    5 -> { if (remaining < 4) break@outer; last + bb.int }
                    else -> throw IllegalStateException("Unsupported vlen=$vlen in RLE spectrum")
                }
                last = v
                result.add(v)
            }
        }
        return IntArray(CHANNELS) { if (it < result.size) result[it] else 0 }
    }

    /** Payload for WR_VIRT_STRING(ENERGY_CALIB): <II vs, len> + <fff>. */
    fun calibrationWritePayload(vs: Int, a0: Float, a1: Float, a2: Float): ByteArray {
        val out = ByteArray(8 + 12)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(vs)
        bb.putInt(12)
        bb.putFloat(a0)
        bb.putFloat(a1)
        bb.putFloat(a2)
        return out
    }
}
