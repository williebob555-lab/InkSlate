package com.inksheets.android

/** Required radio permissions depend on Android version and role, never on an IP/Wi-Fi state. */
internal object BluetoothReadinessPolicy {
    const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val SCAN = "android.permission.BLUETOOTH_SCAN"
    const val ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val LOCATION = "android.permission.ACCESS_FINE_LOCATION"

    fun required(api: Int, mesh: Boolean): List<String> = when {
        api >= 31 && mesh -> listOf(CONNECT, SCAN, ADVERTISE)
        api >= 31 -> listOf(CONNECT)
        mesh -> listOf(LOCATION)
        else -> emptyList()
    }

    fun missing(api: Int, mesh: Boolean, granted: (String) -> Boolean) = required(api, mesh).filterNot(granted)
}
