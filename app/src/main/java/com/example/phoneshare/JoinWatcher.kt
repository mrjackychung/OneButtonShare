package com.example.phoneshare

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * 尽力检测“有设备加入了本机热点”：
 * 监听 mDNS（224.0.0.251:5353）组播。iPhone 加入网络后会很快发出 Bonjour 报文，
 * 收到来自热点网段内其他地址的报文，就认为有设备加入。
 * 这不是系统提供的正式接口，部分手机可能收不到；收不到时仍可手动点“下一步”。
 */
class JoinWatcher(private val ctx: Context, private val onJoin: (String) -> Unit) {

    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start(myIp: String) {
        stop()
        running = true
        Thread { run(myIp) }.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { lock?.release() } catch (_: Exception) {}
        lock = null
    }

    private fun run(myIp: String) {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("onebuttonshare").apply { setReferenceCounted(false); acquire() }

            var ni: NetworkInterface? = null
            for (n in NetworkInterface.getNetworkInterfaces()) {
                if (n.inetAddresses.toList().any { it.hostAddress == myIp }) { ni = n; break }
            }

            val s = MulticastSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(5353))
            socket = s
            val group = InetAddress.getByName("224.0.0.251")
            if (ni != null) s.joinGroup(InetSocketAddress(group, 5353), ni) else s.joinGroup(group)

            val subnet = myIp.substringBeforeLast('.') + "."
            val buf = ByteArray(1500)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val src = p.address.hostAddress ?: continue
                if (src != myIp && src.startsWith(subnet)) {
                    running = false
                    onJoin(src)
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
            try { lock?.release() } catch (_: Exception) {}
        }
    }
}
