package com.radiacode.ble.pro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import kotlin.math.exp
import kotlin.math.max

/**
 * Geiger-style particle clicks synthesised on the phone. Click times follow a Poisson
 * process at the measured count rate divided by [divider], so the sound has the
 * irregular texture of a real counter rather than a metronome.
 */
class ClickPlayer {
    private val sampleRate = 22050
    private val frame = 441 // 20 ms
    @Volatile private var rate = 0.0
    @Volatile private var running = false
    @Volatile var volume: Float = 0.6f
    @Volatile var divider: Int = 1
    private var thread: Thread? = null
    private val click: ShortArray = ShortArray(66) { i ->
        // 3 ms decaying burst
        val env = exp(-i / 12.0)
        val sign = if (i % 2 == 0) 1 else -1
        (sign * env * 26000).toInt().toShort()
    }

    fun setCountRate(cps: Double) {
        rate = max(0.0, cps) / max(1, divider)
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "ClickPlayer").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(max(minBuf, frame * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val rnd = Random()
        val buf = ShortArray(frame)
        val frameSec = frame.toDouble() / sampleRate
        var spill = -1          // index into click[] still to be written at the start of the next frame
        var timeToNext = 0.05   // seconds from the start of the current frame to the next click
        try {
            track.play()
            while (running) {
                java.util.Arrays.fill(buf, 0)
                val vol = volume.coerceIn(0f, 1f)
                fun put(at: Int, from: Int): Int {
                    // writes click samples starting at buffer index [at]; returns spill index or -1
                    var k = from
                    var p = at
                    while (k < click.size) {
                        if (p >= frame) return k
                        buf[p] = (buf[p] + click[k] * vol).toInt().coerceIn(-32768, 32767).toShort()
                        k++; p++
                    }
                    return -1
                }
                if (spill >= 0) spill = put(0, spill)
                if (rate > 0.0) {
                    // after a jump in rate, do not keep waiting for a click scheduled at the old rate
                    if (timeToNext > 3.0 / rate) timeToNext = nextInterval(rnd)
                    var t = timeToNext
                    while (t < frameSec) {
                        val s = put((t * sampleRate).toInt(), 0)
                        if (s >= 0) spill = s
                        t += nextInterval(rnd)
                    }
                    timeToNext = t - frameSec
                } else {
                    timeToNext = 0.05
                }
                track.write(buf, 0, frame)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun nextInterval(rnd: Random): Double {
        val r = rate
        if (r <= 0.0) return 1.0
        return -Math.log(1.0 - rnd.nextDouble()) / r
    }
}
