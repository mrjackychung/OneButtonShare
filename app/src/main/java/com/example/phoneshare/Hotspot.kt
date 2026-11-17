package com.example.phoneshare

import android.net.wifi.WifiManager
import android.os.Build

/**
 * 热点状态放在进程级单例里，而不是某个 Activity 里。
 * 否则再次分享时新建的 Activity 会重复申请热点，系统报
 * “Caller already has an active LocalOnlyHotspot request”。
 */
object HotspotHolder {
    var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    var ssid: String? = null
    var pwd: String? = null
    var starting = false
    var count = 0
    var onEvent: ((String) -> Unit)? = null

    val callback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
            reservation = r
            starting = false
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val c = r.softApConfiguration
                    @Suppress("DEPRECATION")
                    ssid = c.ssid?.trim('"')
                    pwd = c.passphrase
                } else {
                    @Suppress("DEPRECATION")
                    val wc = r.wifiConfiguration
                    ssid = wc?.SSID?.trim('"')
                    pwd = wc?.preSharedKey?.trim('"')
                }
            } catch (_: Exception) {
            }
            onEvent?.invoke("started")
        }

        override fun onStopped() {
            reservation = null; ssid = null; pwd = null; starting = false
            onEvent?.invoke("stopped")
        }

        override fun onFailed(reason: Int) {
            starting = false
            onEvent?.invoke("failed:$reason")
        }
    }
}
