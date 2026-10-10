package com.radiacode.ble.device

import com.radiacode.ble.DeviceConnectionState
import com.radiacode.ble.MultiDeviceBleManager
import com.radiacode.ble.Prefs
import com.radiacode.ble.RadiacodeBleClient
import android.content.Context

/**
 * Bridge between UI screens and the BLE clients owned by the foreground service.
 * The service attaches its manager on start and detaches it on stop.
 */
internal object DeviceGateway {
    @Volatile
    private var manager: MultiDeviceBleManager? = null

    fun attach(m: MultiDeviceBleManager?) {
        manager = m
    }

    val isServiceRunning: Boolean get() = manager != null

    /** Connected device ids with display names. */
    fun connectedDevices(): List<Pair<String, String>> {
        val m = manager ?: return emptyList()
        return m.getAllDeviceStates()
            .filter { it.connectionState == DeviceConnectionState.CONNECTED }
            .map { it.config.id to it.config.displayName }
    }

    /**
     * Client for the device the user is looking at: the selected device if connected,
     * otherwise the first connected one.
     */
    fun client(context: Context, deviceId: String? = null): RadiacodeBleClient? {
        val m = manager ?: return null
        val wanted = deviceId ?: Prefs.getSelectedDeviceId(context)
        if (wanted != null) {
            val st = m.getDeviceState(wanted)
            if (st?.connectionState == DeviceConnectionState.CONNECTED) return m.getClient(wanted)
        }
        return m.getClient(null)
    }

    /** Id of the device [client] would return. */
    fun activeDeviceId(context: Context): String? {
        val m = manager ?: return null
        val wanted = Prefs.getSelectedDeviceId(context)
        if (wanted != null && m.getDeviceState(wanted)?.connectionState == DeviceConnectionState.CONNECTED) return wanted
        return connectedDevices().firstOrNull()?.first
    }

    fun displayName(deviceId: String?): String {
        if (deviceId == null) return "No device"
        return manager?.getDeviceState(deviceId)?.config?.displayName ?: deviceId
    }
}
