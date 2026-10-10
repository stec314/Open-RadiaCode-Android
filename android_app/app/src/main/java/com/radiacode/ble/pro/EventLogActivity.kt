package com.radiacode.ble.pro

import android.os.Bundle
import android.widget.LinearLayout
import com.radiacode.ble.device.DeviceDataHub
import com.radiacode.ble.device.DeviceGateway
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Device events (alarms, power, charging, resets) and app actions, newest first. */
class EventLogActivity : ProActivity() {
    private lateinit var list: LinearLayout
    private var filter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("Event log", "Events reported by the device while connected")
        val top = card(column)
        segmented(top, listOf("All", "Alarms", "Device", "App"), 0) { filter = it; load() }
        buttons(top, 2,
            button("Export CSV", Btn.SECONDARY) { export() },
            button("Clear", Btn.DANGER) { confirm("Clear log", "Delete all logged events?", "Clear") { DeviceDataHub.clearEvents(this); load() } })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)
        listen(DeviceDataHub.ACTION_DEVICE_EVENT) { load() }
        load()
    }

    private fun load() {
        worker.execute {
            val all = DeviceDataHub.readEvents(this)
            val shown = all.filter {
                when (filter) {
                    1 -> it.isAlarm
                    2 -> it.source == "device"
                    3 -> it.source == "app"
                    else -> true
                }
            }.take(500)
            ui.post { render(shown, all.size) }
        }
    }

    private fun render(events: List<DeviceDataHub.LoggedEvent>, total: Int) {
        list.removeAllViews()
        list.addView(text("${events.size} shown / $total logged", 12f, cMuted).apply { setPadding(dp(4), dp(10), 0, dp(4)) })
        if (events.isEmpty()) return
        val fmt = SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.getDefault())
        val c = card(list)
        events.forEachIndexed { i, e ->
            if (i > 0) divider(c)
            val color = when {
                e.isAlarm && e.isLevel2 -> cRed
                e.isAlarm -> cYellow
                e.source == "app" -> cCyan
                else -> cText
            }
            c.addView(text(e.name, 15f, color, bold = e.isAlarm))
            val who = if (e.deviceId == "-") "app" else DeviceGateway.displayName(e.deviceId)
            c.addView(text("${fmt.format(Date(e.timestampMs))}   $who${if (e.detail.isNotBlank() && e.source == "app") "   ${e.detail}" else ""}", 12f, cMuted, mono = true))
        }
    }

    private fun export() {
        worker.execute {
            val events = DeviceDataHub.readEvents(this)
            val sb = StringBuilder("timestamp_iso,device,source,event_id,name,detail\n")
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            for (e in events) {
                sb.append(iso.format(Date(e.timestampMs))).append(',').append(e.deviceId).append(',').append(e.source).append(',')
                    .append(e.eventId).append(',').append('"').append(e.name.replace("\"", "'")).append('"').append(',')
                    .append('"').append(e.detail.replace("\"", "'")).append('"').append('\n')
            }
            val f = exportFile("event_log.csv", sb.toString())
            ui.post { share(f, "text/csv", "RadiaCode event log") }
        }
    }
}
