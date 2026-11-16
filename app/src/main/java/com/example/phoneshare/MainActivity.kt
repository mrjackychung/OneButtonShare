package com.example.phoneshare

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private var server: ShareServer? = null
    private val items = mutableListOf<ShareItem>()
    private var mode = 2 // 1 = Wi-Fi 二维码, 2 = 传输照片二维码

    private lateinit var info: TextView
    private lateinit var caption: TextView
    private lateinit var qrView: ImageView
    private lateinit var wifiBox: LinearLayout
    private lateinit var ssidEt: EditText
    private lateinit var pwdEt: EditText
    private lateinit var btn1: Button
    private lateinit var btn2: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val sp = getSharedPreferences("wifi", Context.MODE_PRIVATE)
        pwdEt.setText(sp.getString("pwd", ""))
        ssidEt.setText(sp.getString("ssid", ""))
        handleIntent(intent)
        if (items.isEmpty()) mode = 1
        updateMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    // ---------------- UI ----------------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(20))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "发送到 iPhone"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        info = TextView(this).apply { textSize = 14f; setPadding(0, dp(6), 0, dp(10)) }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btn1 = Button(this).apply { text = "① 加入Wi-Fi"; setOnClickListener { mode = 1; updateMode() } }
        btn2 = Button(this).apply { text = "② 传输照片"; setOnClickListener { mode = 2; updateMode() } }
        row.addView(btn1, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn2, LinearLayout.LayoutParams(0, -2, 1f))

        wifiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ssidEt = EditText(this).apply { hint = "Wi-Fi 名称 (SSID)"; setSingleLine() }
        pwdEt = EditText(this).apply {
            hint = "Wi-Fi 密码（无密码留空）"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val gen = Button(this).apply { text = "生成 Wi-Fi 二维码"; setOnClickListener { saveWifi(); refreshQr() } }
        wifiBox.addView(ssidEt); wifiBox.addView(pwdEt); wifiBox.addView(gen)

        qrView = ImageView(this).apply { setBackgroundColor(Color.WHITE) }
        caption = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }

        root.addView(title); root.addView(info); root.addView(row); root.addView(wifiBox)
        root.addView(qrView, LinearLayout.LayoutParams(dp(280), dp(280)).apply { topMargin = dp(12) })
        root.addView(caption)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun updateMode() {
        btn1.isEnabled = mode != 1
        btn2.isEnabled = mode != 2
        wifiBox.visibility = if (mode == 1) View.VISIBLE else View.GONE
        if (mode == 1) autoFillSsid()
        refreshQr()
    }

    // ---------------- 接收分享 ----------------
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (u != null) uris.add(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val l: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                if (l != null) uris.addAll(l)
            }
        }
        if (uris.isEmpty()) {
            info.text = "请在相册中选择图片 → 分享 → 选择本应用。"
            return
        }
        info.text = "正在准备 ${uris.size} 张图片…"
        Thread {
            val dir = File(cacheDir, "shared")
            dir.deleteRecursively(); dir.mkdirs()
            val list = mutableListOf<ShareItem>()
            uris.forEachIndexed { i, uri ->
                try {
                    val name = queryName(uri) ?: "photo_$i.jpg"
                    val mime = contentResolver.getType(uri) ?: "image/jpeg"
                    val f = File(dir, "${i}_" + name.replace('/', '_'))
                    contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                    list.add(ShareItem(f, name, mime))
                } catch (_: Exception) {
                }
            }
            runOnUiThread {
                items.clear(); items.addAll(list)
                startServer()
                mode = 2
                updateMode()
            }
        }.start()
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun startServer() {
        server?.stop(); server = null
        for (port in 8088..8097) {
            try {
                val s = ShareServer(port, items)
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                break
            } catch (_: IOException) {
            }
        }
    }

    // ---------------- 二维码 ----------------
    private fun refreshQr() {
        if (mode == 1) {
            val ssid = ssidEt.text.toString()
            if (ssid.isEmpty()) {
                qrView.setImageBitmap(null)
                caption.text = "请填写 Wi-Fi 名称和密码后点击生成。\niPhone 相机扫码即可加入同一 Wi-Fi。"
                return
            }
            val pwd = pwdEt.text.toString()
            val text = if (pwd.isEmpty()) "WIFI:T:nopass;S:${wifiEsc(ssid)};;"
            else "WIFI:T:WPA;S:${wifiEsc(ssid)};P:${wifiEsc(pwd)};;"
            qrView.setImageBitmap(makeQr(text))
            caption.text = "用 iPhone 相机扫描 → 点“加入网络”"
        } else {
            val ip = localIp()
            val s = server
            when {
                s == null -> { qrView.setImageBitmap(null); caption.text = "还没有图片，请从相册分享图片到本应用。" }
                ip == null -> { qrView.setImageBitmap(null); caption.text = "未检测到局域网地址，请连接 Wi-Fi 或开启热点。" }
                else -> {
                    val url = "http://$ip:${s.listeningPort}/"
                    qrView.setImageBitmap(makeQr(url))
                    caption.text = "iPhone 相机扫码打开：\n$url\n（传输期间请保持本应用在前台）"
                    info.text = "已就绪：${items.size} 张图片"
                }
            }
        }
    }

    private fun wifiEsc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;")
        .replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")

    private fun makeQr(text: String): Bitmap {
        val size = 800
        val m = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2)
        )
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    // ---------------- 网络信息 ----------------
    private fun localIp(): String? {
        var fallback: String? = null
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val n = ni.name
                        if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) return addr.hostAddress
                        if (fallback == null) fallback = addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return fallback
    }

    private fun autoFillSsid() {
        if (ssidEt.text.isNotEmpty()) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ssid = wm.connectionInfo?.ssid?.trim('"')
            if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") ssidEt.setText(ssid)
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (mode == 1) autoFillSsid()
    }

    private fun saveWifi() {
        getSharedPreferences("wifi", Context.MODE_PRIVATE).edit()
            .putString("ssid", ssidEt.text.toString())
            .putString("pwd", pwdEt.text.toString()).apply()
    }
}
