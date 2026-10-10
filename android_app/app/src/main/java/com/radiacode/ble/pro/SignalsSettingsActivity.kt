package com.radiacode.ble.pro

import android.os.Bundle
import com.radiacode.ble.Prefs
import com.radiacode.ble.device.LocalVoice
import com.radiacode.ble.device.ProSettings

/** Signals, voice, units and network (privacy) settings for the instrument features. */
class SignalsSettingsActivity : ProActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("Signals and privacy")

        val sig = card(column, "Phone signals")
        switchRow(sig, "Repeat device alarms on the phone", "Notification, vibration and tone when the RadiaCode raises an alarm",
            ProSettings.duplicateDeviceAlarms(this)) { ProSettings.setDuplicateDeviceAlarms(this, it) }
        switchRow(sig, "Speak alarms", "Uses the phone's speech engine, no network", ProSettings.voiceAlarms(this)) { ProSettings.setVoiceAlarms(this, it) }
        sliderRow(sig, "Announce dose rate every", 0, 30, ProSettings.voiceIntervalMin(this), { if (it == 0) "off" else "$it min" }) {
            ProSettings.setVoiceIntervalMin(this, it)
        }
        buttons(sig, 1, button("Test voice", Btn.SECONDARY) { LocalVoice.speak(this, "Dose rate ${LocalVoice.doseRatePhrase(0.12f)}") })

        val clicks = card(column, "Particle clicks", "Played in Search mode.")
        switchRow(clicks, "Clicks", null, ProSettings.clicksEnabled(this)) { ProSettings.setClicksEnabled(this, it) }
        sliderRow(clicks, "One click every", 1, 100, ProSettings.clickDivider(this), { "$it pulse(s)" }) { ProSettings.setClickDivider(this, it) }
        sliderRow(clicks, "Volume", 0, 100, ProSettings.clicksVolume(this), { "$it%" }) { ProSettings.setClicksVolume(this, it) }

        val units = card(column, "Units", "Used by Search, Spectrum, Dose and Intervention.")
        units.addView(text("Dose", 14f, cText))
        segmented(units, listOf("Sievert", "rem", "Roentgen"), ProSettings.doseSystem(this).ordinal) {
            ProSettings.setDoseSystem(this, ProSettings.DoseSystem.values()[it])
        }
        units.addView(text("Temperature", 14f, cText))
        segmented(units, listOf("Celsius", "Fahrenheit"), if (ProSettings.temperatureFahrenheit(this)) 1 else 0) {
            ProSettings.setTemperatureFahrenheit(this, it == 1)
        }

        val iface = card(column, "Interface")
        switchRow(iface, "Keep screen on", "In the instrument screens", ProSettings.keepScreenOn(this)) { ProSettings.setKeepScreenOn(this, it) }
        sliderRow(iface, "Averaging window (Search)", 1, 60, ProSettings.averagingSeconds(this), { "$it s" }) { ProSettings.setAveragingSeconds(this, it) }
        iface.addView(text("Start page", 14f, cText))
        segmented(iface, listOf("Dashboard", "Search", "Spectrum", "Dose", "Intervention"), ProSettings.startPage(this)) { ProSettings.setStartPage(this, it) }

        val net = card(column, "Network and privacy",
            "By default nothing leaves the phone. Remote services are off until you enable them and enter your own HTTPS address. Plain HTTP is refused.")
        switchRow(net, "Remote isotope identification", "Sends 60 s of spectra and the energy calibration", ProSettings.remoteIsotopeEnabled(this)) {
            ProSettings.setRemoteIsotopeEnabled(this, it)
        }
        val isoUrl = textField(ProSettings.remoteIsotopeUrl(this), "https://your-server.example")
        net.addView(isoUrl)
        divider(net)
        switchRow(net, "Remote voice (Vega TTS)", "Sends the text of spoken alerts; off uses the phone voice", ProSettings.remoteVoiceEnabled(this)) {
            ProSettings.setRemoteVoiceEnabled(this, it)
        }
        val ttsUrl = textField(Prefs.getVegaTtsApiUrl(this), "https://your-tts-server.example")
        net.addView(ttsUrl)
        buttons(net, 1, button("Save addresses", Btn.PRIMARY) {
            val a = isoUrl.text.toString().trim()
            val b = ttsUrl.text.toString().trim()
            if ((a.isNotEmpty() && !ProSettings.isHttps(a)) || (b.isNotEmpty() && !ProSettings.isHttps(b))) {
                toast("Only https:// addresses are accepted")
                return@button
            }
            ProSettings.setRemoteIsotopeUrl(this, a)
            Prefs.setVegaTtsApiUrl(this, b)
            toast("Saved")
        })
    }
}
