package com.example.phoneshare

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
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
    private var mode = 0 // 0 = 新手教程, 1 = Wi-Fi 二维码, 2 = 传输照片二维码

    private lateinit var info: TextView
    private lateinit var status: TextView
    private val downloaded = mutableSetOf<Int>()
    private lateinit var caption: TextView
    private lateinit var qrView: ImageView
    private lateinit var wifiBox: LinearLayout
    private lateinit var ssidEt: EditText
    private lateinit var pwdEt: EditText
    private lateinit var btn1: Button
    private lateinit var btn2: Button
    private lateinit var tutorialBox: LinearLayout
    private lateinit var mainBox: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        publishShareShortcut()
        val sp = getSharedPreferences("wifi", Context.MODE_PRIVATE)
        pwdEt.setText(sp.getString("pwd", ""))
        ssidEt.setText(sp.getString("ssid", ""))
        handleIntent(intent)
        mode = if (items.isEmpty()) 0 else 2
        updateMode()
    }

    /** 发布“直接分享”快捷方式：让本应用以红点图标出现在系统分享面板最前面的一排。 */
    private fun publishShareShortcut() {
        try {
            val size = 192
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.parseColor("#FF1B1B")
            c.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)
            val sc = ShortcutInfoCompat.Builder(this, "send_to_iphone")
                .setShortLabel("OneButtonShare")
                .setLongLabel("OneButtonShare")
                .setIcon(IconCompat.createWithBitmap(bmp))
                .setCategories(setOf("com.example.phoneshare.category.SHARE_TARGET"))
                .setLongLived(true)
                .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_SEND))
                .setRank(0)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(this, sc)
        } catch (_: Exception) {
        }
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
            text = "OneButtonShare"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        info = TextView(this).apply { textSize = 14f; setPadding(0, dp(6), 0, dp(10)) }

        status = TextView(this).apply {
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btn1 = Button(this).apply { text = "① 加入Wi-Fi"; setOnClickListener { mode = 1; updateMode() } }
        btn2 = Button(this).apply { text = "② 传输照片"; setOnClickListener { mode = if (items.isEmpty()) 0 else 2; updateMode() } }
        row.addView(btn1, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn2, LinearLayout.LayoutParams(0, -2, 1f))

        wifiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ssidEt = EditText(this).apply { hint = "Wi-Fi 名称（和 iPhone 连的是同一个）"; setSingleLine() }
        pwdEt = EditText(this).apply {
            hint = "Wi-Fi 密码（无密码留空）"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val gen = Button(this).apply { text = "生成 Wi-Fi 二维码"; setOnClickListener { saveWifi(); refreshQr() } }
        wifiBox.addView(ssidEt); wifiBox.addView(pwdEt); wifiBox.addView(gen)

        qrView = ImageView(this).apply { setBackgroundColor(Color.WHITE) }
        caption = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }

        val zh = java.util.Locale.getDefault().language.startsWith("zh")
        fun t(z: String, e: String) = if (zh) z else e

        // ---- 新手教程（首页）----
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        fun stepCard(n: Int, label: String, res: Int): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.parseColor("#F5F5F7"))
                    setStroke(dp(1), Color.parseColor("#DDDDDD"))
                }
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val num = TextView(this).apply {
                this.text = n.toString(); setTextColor(Color.WHITE); textSize = 18f
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FF1B1B")) }
            }
            rowL.addView(num, LinearLayout.LayoutParams(dp(36), dp(36)))
            rowL.addView(TextView(this).apply {
                this.text = label; textSize = 17f; setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            card.addView(rowL)
            card.addView(ImageView(this).apply { setImageResource(res) },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
            return card
        }

        tutorialBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tutorialBox.addView(TextView(this).apply {
            text = t("现在开始 👇", "Get started 👇"); textSize = 24f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        })
        val gap = { LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) } }
        tutorialBox.addView(stepCard(1,
            t("打开「相册」（Photos / Gallery），选择想分享的图片或视频", "Open Photos / Gallery and select the photos or videos to share"),
            R.drawable.ic_step1_gallery), gap())
        tutorialBox.addView(stepCard(2, t("点「分享」", "Tap Share"), R.drawable.ic_step2_share), gap())
        tutorialBox.addView(stepCard(3,
            t("在分享列表里，点红点「OneButtonShare」", "In the share list, tap the red dot “OneButtonShare”"),
            R.drawable.ic_step3_dot), gap())
        tutorialBox.addView(TextView(this).apply {
            text = t("然后用 iPhone 扫描出现的二维码即可。", "Then scan the QR code that appears with your iPhone.")
            textSize = 15f; setTextColor(Color.GRAY); gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(10))
        })

        // ---- 二维码区域 ----
        mainBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        mainBox.addView(info); mainBox.addView(status); mainBox.addView(row); mainBox.addView(wifiBox)
        mainBox.addView(qrView, LinearLayout.LayoutParams(dp(280), dp(280)).apply { topMargin = dp(12) })
        mainBox.addView(caption)

        root.addView(title); root.addView(tutorialBox); root.addView(mainBox)
        root.addView(Button(this).apply {
            text = t("关闭应用", "Close app")
            setOnClickListener { finishAndRemoveTask() }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun updateMode() {
        tutorialBox.visibility = if (mode == 0) View.VISIBLE else View.GONE
        mainBox.visibility = if (mode == 0) View.GONE else View.VISIBLE
        if (mode == 0) return
        btn1.isEnabled = mode != 1
        btn2.isEnabled = mode != 2
        wifiBox.visibility = if (mode == 1) View.VISIBLE else View.GONE
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
            info.text = "请在相册中选择图片或视频 → 分享 → 选择本应用。"
            return
        }
        info.text = "正在准备 ${uris.size} 张图片…"
        Thread {
            val dir = File(cacheDir, "shared")
            dir.deleteRecursively(); dir.mkdirs()
            val list = mutableListOf<ShareItem>()
            uris.forEachIndexed { i, uri ->
                try {
                    val name = queryName(uri) ?: "file_$i"
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val f = File(dir, "${i}_" + name.replace('/', '_'))
                    contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                    val tf = File(dir, "t_$i.jpg")
                    val ok = Thumbs.make(f, mime.startsWith("video/"), tf)
                    list.add(ShareItem(f, name, mime, if (ok) tf else null))
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
        downloaded.clear()
        status.text = ""
        server?.stop(); server = null
        for (port in 8088..8097) {
            try {
                val s = ShareServer(port, items) { kind, idx -> onServerEvent(kind, idx) }
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                break
            } catch (_: IOException) {
            }
        }
    }

    // ---------------- iPhone 下载状态 ----------------
    private fun onServerEvent(kind: String, idx: Int) = runOnUiThread {
        when (kind) {
            "open" -> if (downloaded.isEmpty() && !status.text.startsWith("✅")) {
                status.setTextColor(Color.parseColor("#007aff"))
                status.text = "📱 iPhone 已连接，正在查看"
            }
            "file" -> {
                downloaded.add(idx)
                if (downloaded.size >= items.size) allDone()
                else {
                    status.setTextColor(Color.parseColor("#007aff"))
                    status.text = "⬇ iPhone 已下载 ${downloaded.size}/${items.size} 张"
                }
            }
        }
    }

    private fun allDone() {
        status.setTextColor(Color.parseColor("#1a9c3e"))
        status.text = "✅ iPhone 已下载完成！"
        Toast.makeText(this, "✅ iPhone 已下载完成", Toast.LENGTH_LONG).show()
        try {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(400)
        } catch (_: Exception) {
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

    private fun saveWifi() {
        getSharedPreferences("wifi", Context.MODE_PRIVATE).edit()
            .putString("ssid", ssidEt.text.toString())
            .putString("pwd", pwdEt.text.toString()).apply()
    }
}
