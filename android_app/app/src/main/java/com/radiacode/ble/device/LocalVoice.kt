package com.radiacode.ble.device

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * On-device speech using the system TextToSpeech engine. No network, no third-party server.
 */
object LocalVoice {
    private const val TAG = "LocalVoice"
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = ArrayList<String>()

    @Synchronized
    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        val engine = tts
        if (engine != null && ready) {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "rc_${System.nanoTime()}")
            return
        }
        pending.add(text)
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.getDefault()
                        ready = true
                        pending.forEach { tts?.speak(it, TextToSpeech.QUEUE_ADD, null, "rc_${System.nanoTime()}") }
                    } else {
                        Log.w(TAG, "TextToSpeech init failed: $status")
                    }
                    pending.clear()
                }
            }
        }
    }

    @Synchronized
    fun release() {
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        ready = false
        pending.clear()
    }

    /** Spoken form of a dose rate, avoiding symbols the engine reads badly. */
    fun doseRatePhrase(uSvPerHour: Float): String = when {
        uSvPerHour < 1f -> "${(uSvPerHour * 1000f).toInt()} nanosieverts per hour"
        uSvPerHour < 1000f -> String.format(Locale.US, "%.2f microsieverts per hour", uSvPerHour)
        else -> String.format(Locale.US, "%.2f millisieverts per hour", uSvPerHour / 1000f)
    }
}
