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
import android.os.Handler
import android.os.Looper
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
    private lateinit var fieldsBox: LinearLayout
    private lateinit var srcBtnHot: Button
    private lateinit var srcBtnWifi: Button
    private lateinit var nextBtn: Button

    // 手机热点（LocalOnlyHotspot）
    private var srcHotspot = true
    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var hsSsid: String? = null
    private var hsPwd: String? = null
    private var hsStarting = false
    private var hsError: String? = null
    private var permPending = false
    private var wifiReady = false

    /** 系统语言为中文显示中文，其余显示英文。 */
    private fun t(z: String, e: String): String =
        if (java.util.Locale.getDefault().language.startsWith("zh")) z else e

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        publishShareShortcut()
        val sp = getSharedPreferences("wifi", Context.MODE_PRIVATE)
        pwdEt.setText(sp.getString("pwd", ""))
        ssidEt.setText(sp.getString("ssid", ""))
        srcHotspot = sp.getBoolean("hot", true)
        wifiReady = !ssidEt.text.isNullOrEmpty() && !pwdEt.text.isNullOrEmpty()
        updateSourceUi()
        handleIntent(intent)
        mode = if (items.isEmpty()) 0 else 2
        updateMode()
        askStartupPermissions()
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
        stopHotspot()
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
        btn1 = Button(this).apply { text = t("① 加入Wi-Fi", "① Join Wi-Fi"); setOnClickListener { mode = 1; updateMode() } }
        btn2 = Button(this).apply { text = t("② 传输照片", "② Send photos"); setOnClickListener { mode = if (items.isEmpty()) 0 else 2; updateMode() } }
        row.addView(btn1, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn2, LinearLayout.LayoutParams(0, -2, 1f))

        wifiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val srcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        srcBtnHot = Button(this).apply {
            text = t("📶 手机热点（自动）", "📶 Phone hotspot (auto)"); isAllCaps = false
            setOnClickListener { setSource(true) }
        }
        srcBtnWifi = Button(this).apply {
            text = t("🏠 我的 Wi-Fi", "🏠 My Wi-Fi"); isAllCaps = false
            setOnClickListener { setSource(false) }
        }
        srcRow.addView(srcBtnHot, LinearLayout.LayoutParams(0, -2, 1f))
        srcRow.addView(srcBtnWifi, LinearLayout.LayoutParams(0, -2, 1f))
        fieldsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ssidEt = EditText(this).apply { hint = t("Wi-Fi 名称（和 iPhone 连的是同一个）", "Wi-Fi name (same as your iPhone)"); setSingleLine() }
        pwdEt = EditText(this).apply {
            hint = t("Wi-Fi 密码（无密码留空）", "Wi-Fi password (leave empty if none)"); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val gen = Button(this).apply { text = t("生成 Wi-Fi 二维码", "Generate Wi-Fi QR code"); setOnClickListener { saveWifi(); wifiReady = true; refreshQr() } }
        fieldsBox.addView(ssidEt); fieldsBox.addView(pwdEt); fieldsBox.addView(gen)
        wifiBox.addView(srcRow); wifiBox.addView(fieldsBox)

        qrView = ImageView(this).apply { setBackgroundColor(Color.WHITE) }
        caption = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }


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
        nextBtn = Button(this).apply {
            text = t("iPhone 已加入 → 下一步", "iPhone joined → Next"); isAllCaps = false
            setOnClickListener { mode = 2; updateMode() }
        }
        mainBox.addView(nextBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

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
        nextBtn.visibility = if (mode == 1) View.VISIBLE else View.GONE
        updateSourceUi()
        if (mode == 1 && srcHotspot) startHotspot() else refreshQr()
    }

    // ---------------- 手机热点 / Wi-Fi 来源 ----------------
    private fun updateSourceUi() {
        srcBtnHot.isEnabled = !srcHotspot
        srcBtnWifi.isEnabled = srcHotspot
        fieldsBox.visibility = if (srcHotspot) View.GONE else View.VISIBLE
    }

    private fun setSource(hot: Boolean) {
        srcHotspot = hot
        getSharedPreferences("wifi", Context.MODE_PRIVATE).edit().putBoolean("hot", hot).apply()
        updateSourceUi()
        if (hot) startHotspot() else { stopHotspot(); autoFillSsid(); refreshQr() }
    }

    private fun startHotspot() {
        if (hotspot != null) { refreshQr(); return }
        if (hsStarting) return
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            qrView.setImageBitmap(null)
            caption.text = if (Build.VERSION.SDK_INT >= 33)
                t("需要「附近设备」权限来开启热点（只用于让 iPhone 连上你的手机）。", "“Nearby devices” permission is needed to start the hotspot (only used so your iPhone can connect to this phone).")
            else
                t("需要位置权限来开启热点（Android 系统要求，不会记录你的位置）。", "Location permission is needed to start the hotspot (an Android requirement; your location is not recorded).")
            if (!permPending) { permPending = true; requestPermissions(arrayOf(perm), 7) }
            return
        }
        hsStarting = true; hsError = null; hsSsid = null; hsPwd = null
        refreshQr()
        Handler(Looper.getMainLooper()).postDelayed({
            if (hsStarting) {
                hsStarting = false
                fallbackToWifi(t("热点开启超时。", "Starting the hotspot timed out."))
            }
        }, 10000)
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspot = reservation
                    hsStarting = false
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val c = reservation.softApConfiguration
                            @Suppress("DEPRECATION")
                            hsSsid = c.ssid?.trim('"')
                            hsPwd = c.passphrase
                        } else {
                            @Suppress("DEPRECATION")
                            val wc = reservation.wifiConfiguration
                            hsSsid = wc?.SSID?.trim('"')
                            hsPwd = wc?.preSharedKey?.trim('"')
                        }
                    } catch (_: Exception) {
                    }
                    if (hsSsid == null) fallbackToWifi(t("无法读取热点名称。", "Couldn’t read the hotspot name."))
                    else if (mode == 1) refreshQr()
                }

                override fun onStopped() {
                    hotspot = null; hsSsid = null; hsPwd = null; hsStarting = false
                }

                override fun onFailed(reason: Int) {
                    hsStarting = false
                    fallbackToWifi(t("无法自动开启热点（错误码 $reason）。", "Couldn’t start the hotspot (code $reason)."))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            hsStarting = false
            fallbackToWifi(t("无法自动开启热点：${e.javaClass.simpleName}", "Couldn’t start the hotspot: ${e.javaClass.simpleName}"))
        }
    }

    private fun stopHotspot() {
        try { hotspot?.close() } catch (_: Exception) {}
        hotspot = null; hsSsid = null; hsPwd = null; hsStarting = false; hsError = null
    }

    private fun hotspotPermOk(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    /** 首次打开时一次性请求权限：热点 + 自动读取当前 Wi-Fi 名称。 */
    private fun askStartupPermissions() {
        val sp = getSharedPreferences("wifi", Context.MODE_PRIVATE)
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        val need = list.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty() || sp.getBoolean("perm_asked", false) || permPending) { autoFillSsid(); return }
        sp.edit().putBoolean("perm_asked", true).apply()
        permPending = true
        requestPermissions(need.toTypedArray(), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permPending = false
        if (requestCode != 7 || grantResults.isEmpty()) return
        autoFillSsid()
        if (mode == 1 && srcHotspot) {
            if (hotspotPermOk()) startHotspot()
            else fallbackToWifi(t("没有授权，无法开启热点。", "Permission denied, can’t start the hotspot."))
        }
    }

    private fun fallbackToWifi(msg: String) {
        stopHotspot()
        Toast.makeText(this, msg + "\n" + t("已切换到「我的 Wi-Fi」。", "Switched to “My Wi-Fi”."), Toast.LENGTH_LONG).show()
        setSource(false)
    }

    private fun autoFillSsid() {
        if (ssidEt.text.isNotEmpty()) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ssid = wm.connectionInfo?.ssid?.trim('"')
            if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") ssidEt.setText(ssid)
        } catch (_: Exception) {
        }
    }

    private fun refreshHotspotQr() {
        val ss = hsSsid
        when {
            ss != null -> {
                val pw = hsPwd
                val text = if (pw.isNullOrEmpty()) "WIFI:T:nopass;S:${wifiEsc(ss)};;"
                else "WIFI:T:WPA;S:${wifiEsc(ss)};P:${wifiEsc(pw)};;"
                qrView.setImageBitmap(makeQr(text))
                caption.text = t(
                    "用 iPhone 相机扫描，点“加入网络”。\n（iPhone 提示“无法连接互联网”是正常的）\n加入后点下面的“下一步”。\n如果 iPhone 已和本机在同一个 Wi-Fi，直接点“下一步”。\n\n热点：$ss${if (pw.isNullOrEmpty()) "" else "\n密码：$pw"}",
                    "Scan with your iPhone camera and tap “Join Network”.\n(“No Internet Connection” on iPhone is normal.)\nThen tap Next below.\nIf your iPhone is already on the same Wi-Fi, just tap Next.\n\nHotspot: $ss${if (pw.isNullOrEmpty()) "" else "\nPassword: $pw"}"
                )
            }
            hsError != null -> {
                qrView.setImageBitmap(null)
                caption.text = hsError + "\n" + t(
                    "请改用「我的 Wi-Fi」，或在手机设置里手动开启热点。\n提示：Android 12 及以下需要打开「位置信息」。",
                    "Use “My Wi-Fi” instead, or turn on the hotspot in your phone settings.\nTip: on Android 12 and below, Location must be turned on."
                )
            }
            else -> {
                qrView.setImageBitmap(null)
                caption.text = t("正在开启热点…", "Starting hotspot…")
            }
        }
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
            info.text = t("请在相册中选择图片或视频 → 分享 → 选择本应用。", "In Photos / Gallery, select photos or videos → Share → choose this app.")
            return
        }
        info.text = t("正在准备 ${uris.size} 个文件…", "Preparing ${uris.size} file(s)…")
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
                mode = 1
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
                status.text = t("📱 iPhone 已连接，正在查看", "📱 iPhone connected, viewing")
            }
            "file" -> {
                downloaded.add(idx)
                if (downloaded.size >= items.size) allDone()
                else {
                    status.setTextColor(Color.parseColor("#007aff"))
                    status.text = t("⬇ iPhone 已下载 ${downloaded.size}/${items.size} 个", "⬇ iPhone downloaded ${downloaded.size}/${items.size}")
                }
            }
        }
    }

    private fun allDone() {
        status.setTextColor(Color.parseColor("#1a9c3e"))
        status.text = t("✅ iPhone 已下载完成！", "✅ iPhone download complete!")
        Toast.makeText(this, t("✅ iPhone 已下载完成", "✅ iPhone download complete"), Toast.LENGTH_LONG).show()
        try {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(400)
        } catch (_: Exception) {
        }
    }

    // ---------------- 二维码 ----------------
    private fun refreshQr() {
        if (mode == 1) {
            if (srcHotspot) { refreshHotspotQr(); return }
            val ssid = ssidEt.text.toString()
            if (ssid.isEmpty()) {
                qrView.setImageBitmap(null)
                caption.text = t("请填写 Wi-Fi 名称和密码后点击生成。\niPhone 相机扫码即可加入同一 Wi-Fi。", "Enter the Wi-Fi name and password, then tap Generate.\nScan with your iPhone camera to join the same Wi-Fi.")
                return
            }
            if (!wifiReady) {
                qrView.setImageBitmap(null)
                caption.text = t("请确认 Wi-Fi 名称和密码（无密码留空），然后点「生成 Wi-Fi 二维码」。", "Check the Wi-Fi name and password (leave empty if none), then tap Generate.")
                return
            }
            val pwd = pwdEt.text.toString()
            val text = if (pwd.isEmpty()) "WIFI:T:nopass;S:${wifiEsc(ssid)};;"
            else "WIFI:T:WPA;S:${wifiEsc(ssid)};P:${wifiEsc(pwd)};;"
            qrView.setImageBitmap(makeQr(text))
            caption.text = t("用 iPhone 相机扫描 → 点“加入网络”", "Scan with your iPhone camera → tap “Join Network”")
        } else {
            val ip = localIp()
            val s = server
            when {
                s == null -> { qrView.setImageBitmap(null); caption.text = t("还没有图片，请从相册分享图片到本应用。", "No photos yet. Share photos from your gallery to this app.") }
                ip == null -> { qrView.setImageBitmap(null); caption.text = t("未检测到局域网地址，请连接 Wi-Fi 或开启热点。", "No local network found. Connect to Wi-Fi or turn on your hotspot.") }
                else -> {
                    val url = "http://$ip:${s.listeningPort}/"
                    qrView.setImageBitmap(makeQr(url))
                    caption.text = t("iPhone 相机扫码打开：\n$url\n（传输期间请保持本应用在前台）", "Scan with your iPhone camera:\n$url\n(Keep this app open while transferring)")
                    info.text = t("已就绪：${items.size} 个文件", "Ready: ${items.size} file(s)")
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
        val cands = mutableListOf<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        cands.add(ni.name to (addr.hostAddress ?: continue))
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (hotspot != null) {
            val ap = Regex("^(ap|swlan|softap|wlan[1-9]).*")
            cands.firstOrNull { ap.matches(it.first) }?.let { return it.second }
        }
        cands.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("ap") || it.first.startsWith("swlan") }
            ?.let { return it.second }
        return cands.firstOrNull()?.second
    }

    private fun saveWifi() {
        getSharedPreferences("wifi", Context.MODE_PRIVATE).edit()
            .putString("ssid", ssidEt.text.toString())
            .putString("pwd", pwdEt.text.toString()).apply()
    }
}
