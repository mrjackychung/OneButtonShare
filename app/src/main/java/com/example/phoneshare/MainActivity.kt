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
import android.provider.Settings
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
import java.net.URLEncoder

class MainActivity : Activity() {

    private var server: ShareServer? = null
    private val items = mutableListOf<ShareItem>()
    private var mode = 0 // 0 = 新手教程, 1 = Wi-Fi 二维码, 2 = 传输照片二维码

    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var progressBar: ProgressBar
    private val sentBytes = mutableMapOf<Int, Long>()
    private var spT = 0L
    private var spB = 0L
    private var speed = 0.0
    private val downloaded = mutableSetOf<Int>()
    private lateinit var caption: TextView
    private lateinit var qrView: ImageView
    private lateinit var wifiBox: LinearLayout
    private lateinit var btn1: Button
    private lateinit var btn2: Button
    private lateinit var tutorialBox: LinearLayout
    private lateinit var mainBox: LinearLayout
    private lateinit var nextBtn: Button

    // 手机热点（LocalOnlyHotspot）
    private var hotspot: WifiManager.LocalOnlyHotspotReservation?
        get() = HotspotHolder.reservation
        set(v) { HotspotHolder.reservation = v }
    private var hsSsid: String?
        get() = HotspotHolder.ssid
        set(v) { HotspotHolder.ssid = v }
    private var hsPwd: String?
        get() = HotspotHolder.pwd
        set(v) { HotspotHolder.pwd = v }
    private var hsStarting: Boolean
        get() = HotspotHolder.starting
        set(v) { HotspotHolder.starting = v }
    private var hsError: String? = null
    private var permPending = false
    private var watcher: JoinWatcher? = null

    /** 系统语言为中文显示中文，其余显示英文。 */
    private fun t(z: String, e: String): String =
        if (java.util.Locale.getDefault().language.startsWith("zh")) z else e

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        HotspotHolder.count++
        HotspotHolder.onEvent = { ev -> handleHotspotEvent(ev) }
        publishShareShortcut()
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
        watcher?.stop()
        HotspotHolder.count--
        if (HotspotHolder.count <= 0) { stopHotspot(); HotspotHolder.onEvent = null }
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

        wifiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        wifiBox.addView(Button(this).apply {
            text = t("🔄 重新开启热点", "🔄 Restart hotspot"); isAllCaps = false
            setOnClickListener { retryHotspot() }
        })
        wifiBox.addView(Button(this).apply {
            text = t("打开系统热点设置", "Open hotspot settings"); isAllCaps = false
            setOnClickListener { try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Exception) {} }
        })

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
            text = t("然后用 iPhone 扫描出现的二维码即可。*", "Then scan the QR code that appears with your iPhone.*")
            textSize = 15f; setTextColor(Color.GRAY); gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(10))
        })
        tutorialBox.addView(TextView(this).apply {
            text = t(
                "* 为什么需要「附近设备」权限？Android 系统规定，开启热点必须有这个权限（部分手机显示为「位置信息」）。本应用只用它让 iPhone 扫码加入，不会记录你的位置，也不会把任何数据上传到互联网。",
                "* Why “Nearby devices”? Android requires this permission to start a hotspot (some phones label it “Location”). This app only uses it so your iPhone can join by scanning a QR code. It doesn’t record your location or upload any data to the internet."
            )
            textSize = 12f; setTextColor(Color.GRAY); setLineSpacing(0f, 1.2f); setPadding(0, 0, 0, dp(8))
        })

        // ---- 二维码区域 ----
        mainBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        mainBox.addView(info); mainBox.addView(status)
        mainBox.addView(progressBar, LinearLayout.LayoutParams(MATCH, WRAP))
        mainBox.addView(row); mainBox.addView(wifiBox)
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
            setOnClickListener { stopHotspot(); finishAndRemoveTask() }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })
        root.addView(TextView(this).apply {
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
            text = "OneButtonShare v$ver"
            textSize = 12f; setTextColor(Color.GRAY); gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun updateMode() {
        tutorialBox.visibility = if (mode == 0) View.VISIBLE else View.GONE
        mainBox.visibility = if (mode == 0) View.GONE else View.VISIBLE
        if (mode == 0) return
        btn1.isEnabled = mode != 1
        btn2.isEnabled = mode != 2
        if (mode != 1) wifiBox.visibility = View.GONE
        nextBtn.visibility = if (mode == 1) View.VISIBLE else View.GONE
        if (mode == 1) startHotspot() else refreshQr()
    }

    // ---------------- 手机热点 ----------------

    private fun startHotspot() {
        if (hotspot != null) { refreshQr(); if (mode == 1) startWatching(); return }
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
            if (hsStarting && hotspot == null) {
                hotspotFailed(t("热点开启超时。", "Starting the hotspot timed out."), keepRequest = true)
            }
        }, 10000)
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.startLocalOnlyHotspot(HotspotHolder.callback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            hsStarting = false
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            hotspotFailed(
                t("无法自动开启热点（$detail）。请完全关闭本应用后重试。",
                    "Couldn’t start the hotspot ($detail). Please fully close the app and try again.")
            )
        }
    }

    /** 热点开启后，监听是否有 iPhone 加入，加入后自动进入下一步。 */
    private fun startWatching(attempt: Int = 0) {
        val ip = localIp()
        if (ip == null) {
            if (attempt < 10) Handler(Looper.getMainLooper()).postDelayed({ startWatching(attempt + 1) }, 1000)
            return
        }
        watcher?.stop()
        watcher = JoinWatcher(this) { src -> runOnUiThread { onIphoneJoined(src) } }.also { it.start(ip) }
    }

    private fun onIphoneJoined(src: String) {
        watcher?.stop(); watcher = null
        if (mode == 1) {
            Toast.makeText(this, t("📱 iPhone 已加入热点", "📱 iPhone joined the hotspot"), Toast.LENGTH_LONG).show()
            try {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(300)
            } catch (_: Exception) {
            }
            mode = 2
            updateMode()
        }
    }

    private fun handleHotspotEvent(ev: String) {
        when {
            ev == "started" -> {
                hsError = null
                if (hsSsid == null) hotspotFailed(t("无法读取热点名称。", "Couldn’t read the hotspot name."))
                else if (mode == 1) { refreshQr(); startWatching() }
            }
            ev.startsWith("failed") -> {
                val code = ev.substringAfter(':')
                hotspotFailed(t("无法自动开启热点（错误码 $code）。", "Couldn’t start the hotspot (code $code)."))
            }
        }
    }

    private fun stopHotspot() {
        watcher?.stop(); watcher = null
        try { hotspot?.close() } catch (_: Exception) {}
        hotspot = null; hsSsid = null; hsPwd = null; hsError = null
    }

    private fun hotspotPermOk(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    /** 首次打开时请求开启热点所需的权限。 */
    private fun askStartupPermissions() {
        val sp = getSharedPreferences("wifi", Context.MODE_PRIVATE)
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        if (hotspotPermOk() || sp.getBoolean("perm_asked", false) || permPending) return
        sp.edit().putBoolean("perm_asked", true).apply()
        permPending = true
        requestPermissions(arrayOf(perm), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permPending = false
        if (requestCode != 7 || grantResults.isEmpty()) return
        if (mode == 1) {
            if (hotspotPermOk()) startHotspot()
            else hotspotFailed(t("没有授权，无法开启热点。", "Permission denied, can’t start the hotspot."))
        }
    }

    private fun hotspotFailed(msg: String, keepRequest: Boolean = false) {
        if (!keepRequest) stopHotspot()
        hsError = msg
        if (mode == 1) refreshQr()
    }

    private fun retryHotspot() {
        stopHotspot()
        if (hsStarting) {
            Toast.makeText(this, t("系统仍在处理上一次请求，请稍等…", "The system is still handling the previous request, please wait…"), Toast.LENGTH_LONG).show()
            refreshQr()
        } else startHotspot()
    }

    private fun refreshHotspotQr() {
        val ss = hsSsid
        wifiBox.visibility = if (ss == null && hsError != null) View.VISIBLE else View.GONE
        when {
            ss != null -> {
                val pw = hsPwd
                val text = if (pw.isNullOrEmpty()) "WIFI:T:nopass;S:${wifiEsc(ss)};;"
                else "WIFI:T:${if (HotspotHolder.security == 3) "SAE" else "WPA"};S:${wifiEsc(ss)};P:${wifiEsc(pw)};;"
                qrView.setImageBitmap(makeQr(text))
                caption.text = t(
                    "用 iPhone 相机扫描，点“加入网络”。\n（iPhone 提示“无法连接互联网”是正常的）\n加入后点下面的“下一步”。\n如果 iPhone 已和本机在同一个 Wi-Fi，直接点“下一步”。\n\n热点：$ss${if (pw.isNullOrEmpty()) "" else "\n密码：$pw"}",
                    "Scan with your iPhone camera and tap “Join Network”.\n(“No Internet Connection” on iPhone is normal.)\nThen tap Next below.\nIf your iPhone is already on the same Wi-Fi, just tap Next.\n\nHotspot: $ss${if (pw.isNullOrEmpty()) "" else "\nPassword: $pw"}"
                )
                caption.text = caption.text.toString() + "\n\n" + t(
                    "⏳ 正在等待 iPhone 加入…加入后会自动进入下一步。\n（没有自动跳转的话，请手动点“下一步”）",
                    "⏳ Waiting for the iPhone to join… the next step opens automatically.\n(If nothing happens, tap Next yourself.)"
                )
                val secLabel = when (HotspotHolder.security) {
                    0 -> "Open"; 1 -> "WPA2"; 2 -> "WPA2/WPA3"; 3 -> "WPA3"; 4, 5 -> "OWE"; else -> "?"
                }
                caption.text = caption.text.toString() + "\n\n[$secLabel · Android ${Build.VERSION.RELEASE}]"
            }
            hsError != null -> {
                qrView.setImageBitmap(null)
                caption.text = hsError + "\n" + t(
                    "请点下面「重新开启热点」重试；或在手机系统设置里手动开启热点，再点「下一步」。\n提示：Android 12 及以下需要打开「位置信息」。",
                    "Tap “Restart hotspot” below to try again, or turn on the hotspot in your phone settings and then tap Next.\nTip: on Android 12 and below, Location must be turned on."
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
                    val size = querySize(uri)
                    contentResolver.openInputStream(uri)?.use { ins ->
                        f.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var done = 0L
                            var last = 0L
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val nowMs = System.currentTimeMillis()
                                if (nowMs - last > 300) {
                                    last = nowMs
                                    val d = done
                                    runOnUiThread {
                                        info.text = t(
                                            "正在准备 ${i + 1}/${uris.size}：${formatSize(d)}${if (size > 0) " / " + formatSize(size) else ""}",
                                            "Preparing ${i + 1}/${uris.size}: ${formatSize(d)}${if (size > 0) " / " + formatSize(size) else ""}"
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val tf = File(dir, "t_$i.jpg")
                    val ok = Thumbs.make(f, mime.startsWith("video/"), tf)
                    list.add(ShareItem(f, name, mime, if (ok) tf else null))
                } catch (_: Exception) {
                }
            }
            runOnUiThread {
                items.clear(); items.addAll(list)
                info.text = t("已就绪：${list.size} 个文件", "Ready: ${list.size} file(s)")
                startServer()
                mode = if (localIp()?.startsWith("172.20.10.") == true) 2 else 1
                updateMode()
            }
        }.start()
    }

    private fun querySize(uri: Uri): Long = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
    } catch (_: Exception) { -1L }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun startServer() {
        downloaded.clear()
        sentBytes.clear(); spT = 0L; spB = 0L; speed = 0.0
        progressBar.visibility = View.GONE; progressBar.progress = 0
        status.text = ""
        server?.stop(); server = null
        for (port in 8088..8097) {
            try {
                val s = ShareServer(port, items, { assets.open("SaveFromAndroid.shortcut") }, { i, sent, total -> onProgress(i, sent, total) }) { kind, idx -> onServerEvent(kind, idx) }
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
                items.getOrNull(idx)?.let { sentBytes[idx] = it.file.length() }
                if (downloaded.size >= items.size) allDone() else updateTransferUi()
            }
        }
    }

    private fun onProgress(idx: Int, sent: Long, total: Long) = runOnUiThread {
        sentBytes[idx] = maxOf(sentBytes[idx] ?: 0L, sent)
        updateTransferUi()
    }

    private fun formatSize(b: Long): String = when {
        b >= (1L shl 30) -> "%.2f GB".format(b / 1073741824.0)
        b >= (1L shl 20) -> "%.1f MB".format(b / 1048576.0)
        else -> "${b / 1024} KB"
    }

    private fun fmtEta(sec: Int): String =
        if (sec < 60) t("${sec} 秒", "${sec}s") else t("${sec / 60} 分 ${sec % 60} 秒", "${sec / 60}m ${sec % 60}s")

    private fun updateTransferUi() {
        val total = items.sumOf { it.file.length() }
        if (total <= 0L) return
        val done = minOf(total, sentBytes.values.sum())
        val now = System.currentTimeMillis()
        if (spT == 0L) { spT = now; spB = done }
        else if (now - spT >= 1000) {
            val inst = (done - spB) * 1000.0 / (now - spT)
            speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
            spT = now; spB = done
        }
        progressBar.visibility = View.VISIBLE
        progressBar.progress = (done * 1000 / total).toInt()
        val pct = (done * 100 / total).toInt()
        val eta = if (speed > 1.0) ((total - done) / speed).toInt() else -1
        val spd = if (speed > 1.0) " · ${formatSize(speed.toLong())}/s" else ""
        val etaTxt = if (eta >= 0) t(" · 约 ${fmtEta(eta)}", " · ~${fmtEta(eta)} left") else ""
        status.setTextColor(Color.parseColor("#007aff"))
        status.text = t(
            "⬇ iPhone 正在下载 $pct%（已完成 ${downloaded.size}/${items.size} 个）\n${formatSize(done)} / ${formatSize(total)}$spd$etaTxt",
            "⬇ iPhone downloading $pct% (${downloaded.size}/${items.size} done)\n${formatSize(done)} / ${formatSize(total)}$spd$etaTxt"
        )
    }

    private fun allDone() {
        progressBar.progress = 1000
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
            refreshHotspotQr()
        } else {
            val ip = localIp()
            val s = server
            when {
                s == null -> { qrView.setImageBitmap(null); caption.text = t("还没有图片，请从相册分享图片到本应用。", "No photos yet. Share photos from your gallery to this app.") }
                ip == null -> { qrView.setImageBitmap(null); caption.text = t("未检测到局域网地址，请连接 Wi-Fi 或开启热点。", "No local network found. Connect to Wi-Fi or turn on your hotspot.") }
                else -> {
                    val url = "http://$ip:${s.listeningPort}/"
                    val sc = false
                    val qrText = if (sc) "shortcuts://run-shortcut?name=SaveFromAndroid&input=text&text=" +
                        URLEncoder.encode(url, "UTF-8") else url
                    qrView.setImageBitmap(makeQr(qrText))
                    caption.text = if (sc)
                        t("iPhone 相机扫码 → 在「快捷指令」中打开，照片会直接存入「照片」。\n（传输期间请保持本应用在前台）",
                            "Scan with your iPhone camera → open in Shortcuts. Photos are saved straight to Photos.\n(Keep this app open while transferring)")
                    else
                        t("iPhone 相机扫码打开：\n$url\n\niPhone 上：先点 ① 添加快捷指令（仅第一次），再点 ② 存入相册。\n（传输期间请保持本应用在前台）",
                            "Scan with your iPhone camera:\n$url\n\nOn the iPhone: tap ① to add the shortcut (first time only), then ② to save to Photos.\n(Keep this app open while transferring)")
                    if (ip.startsWith("172.20.10."))
                        caption.text = t(
                            "✅ 已加入 iPhone 的热点。\n请在 iPhone 上点「继续」开始接收。\n\n",
                            "✅ Joined the iPhone’s hotspot.\nOn the iPhone, tap “Continue” to start receiving.\n\n"
                        ) + caption.text
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
        val apOnly = Regex("^(ap|swlan|softap).*")
        cands.firstOrNull { apOnly.matches(it.first) }?.let { return it.second }
        if (hotspot != null) {
            val ap = Regex("^wlan[1-9].*")
            cands.firstOrNull { ap.matches(it.first) }?.let { return it.second }
        }
        cands.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("ap") || it.first.startsWith("swlan") }
            ?.let { return it.second }
        return cands.firstOrNull()?.second
    }


}
