package com.example.phoneshare

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLEncoder

data class ShareItem(val file: File, val name: String, val mime: String, val thumb: File? = null)

/** 统计已发送字节，发送完毕（达到总长度）时回调一次。 */
class TrackingStream(
    private val inner: InputStream,
    private val total: Long,
    private val onDone: () -> Unit
) : InputStream() {
    private var sent = 0L
    private var fired = false
    private fun add(n: Int) {
        if (n > 0) sent += n
        if (!fired && (n < 0 || sent >= total)) { fired = true; onDone() }
    }
    override fun read(): Int { val r = inner.read(); add(if (r < 0) -1 else 1); return r }
    override fun read(b: ByteArray, off: Int, len: Int): Int { val r = inner.read(b, off, len); add(r); return r }
    override fun available() = inner.available()
    override fun close() = inner.close()
}

private class Strs(
    val title: String,   // %d = count
    val tip: String,
    val dlAll: String,   // %d = count
    val dlAllHint: String,
    val dl: String
)

private val STRINGS = mapOf(
    "en" to Strs(
        "%d file(s) from Android",
        "Tap a photo to open it, then long-press and choose “Add to Photos”.",
        "⬇ Download all (%d)",
        "Files download one by one. If Safari asks, tap Download / Allow.",
        "Download"
    ),
    "zh-Hans" to Strs(
        "来自安卓的 %d 个文件",
        "点开照片后长按图片，选择“添加到照片”。",
        "⬇ 一键下载全部（%d 个）",
        "将逐个依次下载。如果 Safari 弹出询问，请点“下载”或“允许”。",
        "下载"
    ),
    "zh-Hant" to Strs(
        "來自 Android 的 %d 個檔案",
        "點開照片後長按圖片，選擇「加入照片」。",
        "⬇ 一鍵下載全部（%d 個）",
        "將逐個依序下載。如果 Safari 彈出詢問，請點「下載」或「允許」。",
        "下載"
    ),
    "ja" to Strs(
        "Android からのファイル %d 件",
        "写真をタップして開き、長押しして「“写真”に追加」を選択。",
        "⬇ すべてダウンロード（%d 件）",
        "1つずつ順番にダウンロードされます。Safari で確認が出たら「ダウンロード」を選んでください。",
        "ダウンロード"
    ),
    "ko" to Strs(
        "Android에서 보낸 파일 %d개",
        "사진을 탭해 연 다음 길게 눌러 “사진에 추가”를 선택하세요.",
        "⬇ 전체 다운로드 (%d개)",
        "하나씩 차례로 다운로드됩니다. Safari에서 묻으면 “다운로드”를 누르세요.",
        "다운로드"
    ),
    "es" to Strs(
        "%d archivo(s) desde Android",
        "Toca una foto para abrirla, mantenla pulsada y elige “Añadir a Fotos”.",
        "⬇ Descargar todo (%d)",
        "Se descargan uno por uno. Si Safari pregunta, toca Descargar.",
        "Descargar"
    ),
    "fr" to Strs(
        "%d fichier(s) depuis Android",
        "Touchez une photo, appuyez longuement puis « Ajouter à Photos ».",
        "⬇ Tout télécharger (%d)",
        "Les fichiers se téléchargent un par un. Si Safari demande, touchez Télécharger.",
        "Télécharger"
    ),
    "de" to Strs(
        "%d Datei(en) von Android",
        "Foto antippen, gedrückt halten und „Zu Fotos hinzufügen“ wählen.",
        "⬇ Alle laden (%d)",
        "Die Dateien werden nacheinander geladen. Wenn Safari fragt, „Laden“ tippen.",
        "Laden"
    )
)

/** 若填入快捷指令的 iCloud 分享链接（https://www.icloud.com/shortcuts/...），页面会显示“安装快捷指令”按钮。 */
private const val SHORTCUT_ICLOUD_URL = "https://www.icloud.com/shortcuts/6029658f6f074e07b032c7d559ad65fe"

private val MORE = mapOf(
    "en" to "More options", "zh-Hans" to "更多方式", "zh-Hant" to "更多方式", "ja" to "その他の方法",
    "ko" to "더 보기", "es" to "Más opciones", "fr" to "Plus d’options", "de" to "Mehr Optionen"
)

private val SC_INSTALL = mapOf(
    "en" to "First time? Tap here to install the shortcut (one time)",
    "zh-Hans" to "第一次使用？点这里安装快捷指令（只需一次）",
    "zh-Hant" to "第一次使用？點這裡安裝捷徑（只需一次）",
    "ja" to "初回はここをタップしてショートカットを追加（1回のみ）",
    "ko" to "처음이신가요? 여기를 눌러 단축어 설치 (1회)",
    "es" to "¿Primera vez? Toca aquí para instalar el atajo (una sola vez)",
    "fr" to "Première fois ? Touchez ici pour installer le raccourci (une seule fois)",
    "de" to "Erstes Mal? Hier tippen, um den Kurzbefehl zu installieren (einmalig)"
)

private val SC_SAVE = mapOf(
    "en" to "⭐ Save all to Photos",
    "zh-Hans" to "⭐ 一键存入相册",
    "zh-Hant" to "⭐ 一鍵存入相簿",
    "ja" to "⭐ すべて写真に保存",
    "ko" to "⭐ 모두 사진에 저장",
    "es" to "⭐ Guardar todo en Fotos",
    "fr" to "⭐ Tout enregistrer dans Photos",
    "de" to "⭐ Alle in Fotos sichern"
)

/** iOS“快捷指令”一键存入相册：按钮文字 + 帮助页 */
private val SC_BTN = mapOf(
    "en" to Pair("⭐ Save all to Photos (no confirmations)", "First time? One-time setup (2 min)"),
    "zh-Hans" to Pair("⭐ 一键存入相册（无需逐个确认）", "第一次使用？查看一次性设置（约 2 分钟）"),
    "zh-Hant" to Pair("⭐ 一鍵存入相簿（無需逐個確認）", "第一次使用？查看一次性設定（約 2 分鐘）")
)

private val SC_HELP = mapOf(
    "en" to Pair("One-time setup: Shortcuts app", listOf(
        "Open the Shortcuts app on your iPhone and tap + to create a new shortcut.",
        "Rename it exactly: SaveFromAndroid",
        "Add action “Text”: tap the variable “Shortcut Input”, then type list.txt right after it.",
        "Add action “Get Contents of URL” (its URL is the Text from the previous step).",
        "Add action “Split Text” and choose “New Lines”.",
        "Add action “Repeat with Each”.",
        "Inside the repeat, add “Get Contents of URL” and set its URL to “Repeat Item”.",
        "Right after it, still inside the repeat, add “Save to Photo Album”.",
        "Done. Go back to the transfer page and tap the star button. Allow access when asked (first time only). Everything is saved to Photos with no more confirmations."
    )),
    "zh-Hans" to Pair("一次性设置：快捷指令", listOf(
        "打开 iPhone 自带的「快捷指令」App，点右上角「+」新建。",
        "把名称改成（大小写要一致）：SaveFromAndroid",
        "添加动作「文本」：先点选变量「快捷指令输入」，紧接着输入 list.txt",
        "添加动作「获取 URL 内容」（URL 就是上一步的文本）。",
        "添加动作「拆分文本」，分隔符选「新行」。",
        "添加动作「重复每个项目」。",
        "在「重复」里面添加「获取 URL 内容」，URL 选择「重复项目」。",
        "紧接着（仍在「重复」里面）添加「存储到相册」。",
        "完成！回到传输页面点那个星星按钮，第一次会询问是否允许，点允许即可。以后每次都会一键全部存进「照片」，不再逐个确认。"
    )),
    "zh-Hant" to Pair("一次性設定：捷徑", listOf(
        "打開 iPhone 內建的「捷徑」App，點右上角「+」新增。",
        "把名稱改成（大小寫要一致）：SaveFromAndroid",
        "加入動作「文字」：先點選變數「捷徑輸入」，緊接著輸入 list.txt",
        "加入動作「取得 URL 的內容」（URL 就是上一步的文字）。",
        "加入動作「分割文字」，分隔符選「新行」。",
        "加入動作「重複每個項目」。",
        "在「重複」裡面加入「取得 URL 的內容」，URL 選擇「重複項目」。",
        "緊接著（仍在「重複」裡面）加入「儲存到相簿」。",
        "完成！回到傳輸頁面點那個星星按鈕，第一次會詢問是否允許，點允許即可。之後每次都會一鍵全部存進「照片」，不再逐個確認。"
    ))
)

private val LANG_NAMES = linkedMapOf(
    "en" to "English", "zh-Hans" to "简体中文", "zh-Hant" to "繁體中文", "ja" to "日本語",
    "ko" to "한국어", "es" to "Español", "fr" to "Français", "de" to "Deutsch"
)

private fun pickLang(header: String?, override: String?): String {
    if (override != null && STRINGS.containsKey(override)) return override
    val first = (header ?: "").split(",").firstOrNull()?.substringBefore(";")?.trim()?.lowercase() ?: ""
    return when {
        first.startsWith("zh-hant") || first.startsWith("zh-tw") ||
            first.startsWith("zh-hk") || first.startsWith("zh-mo") -> "zh-Hant"
        first.startsWith("zh") -> "zh-Hans"
        first.startsWith("ja") -> "ja"
        first.startsWith("ko") -> "ko"
        first.startsWith("es") -> "es"
        first.startsWith("fr") -> "fr"
        first.startsWith("de") -> "de"
        else -> "en"
    }
}

class ShareServer(
    port: Int,
    private val items: List<ShareItem>,
    private val listener: (kind: String, index: Int) -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/" || uri.isEmpty()) {
            listener("open", -1)
            val lang = pickLang(session.headers["accept-language"], session.parms["lang"])
            return page(lang, session.headers["host"] ?: "")
        }
        if (uri == "/list.txt") {
            val host = session.headers["host"] ?: ""
            val body = items.indices.joinToString("\n") { "http://$host/f/$it?s=1" }
            return NanoHTTPD.newFixedLengthResponse(Status.OK, "text/plain; charset=utf-8", body)
        }
        if (uri == "/help") {
            val lang = pickLang(session.headers["accept-language"], session.parms["lang"])
            val h = SC_HELP[lang] ?: SC_HELP.getValue("en")
            val html = "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<style>body{font-family:-apple-system,sans-serif;padding:16px;line-height:1.6}li{margin-bottom:10px}</style></head><body>" +
                "<h2>${esc(h.first)}</h2><ol>" + h.second.joinToString("") { "<li>${esc(it)}</li>" } +
                "</ol></body></html>"
            return NanoHTTPD.newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", html)
        }
        if (uri.startsWith("/t/")) {
            val th = uri.removePrefix("/t/").toIntOrNull()?.let { items.getOrNull(it) }?.thumb
            if (th != null && th.exists()) {
                val res = NanoHTTPD.newFixedLengthResponse(Status.OK, "image/jpeg", FileInputStream(th), th.length())
                res.addHeader("Cache-Control", "max-age=3600")
                return res
            }
            return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
        }
        if (uri.startsWith("/f/")) {
            val idx = uri.removePrefix("/f/").toIntOrNull()
            val item = idx?.let { items.getOrNull(it) }
                ?: return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
            val isDl = session.parms["dl"] == "1"
            val track = isDl || session.parms["s"] == "1"
            val raw = FileInputStream(item.file)
            val stream: InputStream =
                if (track) TrackingStream(raw, item.file.length()) { listener("file", idx) } else raw
            val res = NanoHTTPD.newFixedLengthResponse(Status.OK, item.mime, stream, item.file.length())
            val enc = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
            val dispo = if (isDl) "attachment" else "inline"
            res.addHeader("Content-Disposition", "$dispo; filename*=UTF-8''$enc")
            return res
        }
        return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun sizeText(b: Long): String =
        if (b >= 1048576) "%.1f MB".format(b / 1048576.0) else "%d KB".format(b / 1024)

    private fun page(lang: String, host: String): Response {
        val s = STRINGS.getValue(lang)
        val n = items.size
        val sb = StringBuilder()
        sb.append(
            """<!doctype html><html lang="$lang"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(s.title.format(n))}</title>
<style>
body{font-family:-apple-system,sans-serif;margin:0;padding:12px;background:#f2f2f7;color:#111}
h2{margin:8px 0}.tip{background:#fff3cd;border:1px solid #ffe08a;border-radius:12px;padding:12px;font-size:17px;font-weight:600;margin:10px 0}
.adv{margin:10px 0}summary{color:#007aff;font-size:15px;padding:8px 0}
.top{display:flex;justify-content:space-between;align-items:center}
select{font-size:14px;padding:4px;border-radius:6px}
.all{display:block;width:100%;box-sizing:border-box;text-align:center;background:#007aff;color:#fff;
 font-size:18px;font-weight:700;padding:18px 12px;border-radius:14px;text-decoration:none;margin:10px 0 6px}
.main{display:block;width:100%;box-sizing:border-box;text-align:center;background:#34c759;color:#fff;font-size:24px;font-weight:700;padding:24px 12px;border-radius:16px;text-decoration:none;margin:12px 0 6px}
.g{display:grid;grid-template-columns:repeat(3,1fr);gap:2px;margin-top:14px}
.t{position:relative;display:block;aspect-ratio:1/1;overflow:hidden;background:#d1d1d6}
.t img{width:100%;height:100%;object-fit:cover;border-radius:0}
.ph{display:flex;width:100%;height:100%;align-items:center;justify-content:center;font-size:32px}
.vb{position:absolute;left:5px;bottom:4px;color:#fff;font-size:12px;text-shadow:0 0 3px #000}
.hint{color:#666;font-size:12px;text-align:center;margin-bottom:10px}
.sc{display:block;box-sizing:border-box;text-align:center;background:#ff9500;color:#fff;font-size:17px;font-weight:700;padding:14px 12px;border-radius:14px;text-decoration:none;margin:14px 0 6px}
.c{background:#fff;border-radius:12px;padding:10px;margin-bottom:14px}
img{width:100%;border-radius:8px;display:block}
.v{background:#e5e5ea;border-radius:8px;text-align:center;padding:28px 0;font-size:40px}
.n{font-size:13px;color:#666;margin:6px 0;word-break:break-all}
.b{display:inline-block;background:#007aff;color:#fff;padding:8px 16px;border-radius:8px;text-decoration:none}
</style></head><body>
<div class="top"><h2>${esc(s.title.format(n))}</h2>
<select onchange="location.search='?lang='+this.value">
"""
        )
        LANG_NAMES.forEach { (k, v) ->
            sb.append("<option value=\"$k\"${if (k == lang) " selected" else ""}>$v</option>")
        }
        sb.append("</select></div>\n")
        val scUrl = "shortcuts://run-shortcut?name=SaveFromAndroid&input=text&text=" +
            URLEncoder.encode("http://$host/", "UTF-8")
        sb.append("<a class=\"main\" href=\"$scUrl\">${esc(SC_SAVE[lang] ?: SC_SAVE.getValue("en"))}</a>\n")
        if (SHORTCUT_ICLOUD_URL.isNotEmpty())
            sb.append("<div class=\"hint\"><a href=\"$SHORTCUT_ICLOUD_URL\">${esc(SC_INSTALL[lang] ?: SC_INSTALL.getValue("en"))}</a></div>\n")
        sb.append("<div class=\"tip\">👆 ${esc(s.tip)}</div>\n")
        sb.append("<details class=\"adv\"><summary>${esc(MORE[lang] ?: "More options")}</summary>\n")
        sb.append("<a class=\"all\" href=\"#\" onclick=\"dlAll();return false\">${esc(s.dlAll.format(n))}</a>\n")
        sb.append("<div class=\"hint\">${esc(s.dlAllHint)}</div></details>\n")
        sb.append("<div class=\"g\">")
        items.forEachIndexed { i, it ->
            val isVideo = it.mime.startsWith("video/")
            val href = if (isVideo) "/f/$i?dl=1" else "/f/$i"
            sb.append("<a class=\"t\" href=\"$href\" data-dl=\"/f/$i?dl=1\">")
            if (it.thumb != null) sb.append("<img src=\"/t/$i\" loading=\"lazy\">")
            else sb.append("<div class=\"ph\">${if (isVideo) "🎬" else "🖼"}</div>")
            if (isVideo) sb.append("<span class=\"vb\">▶</span>")
            sb.append("</a>")
        }
        sb.append("</div>\n")
        sb.append(
            """<script>
function dlAll(){var l=document.querySelectorAll('a.t'),i=0;
(function nx(){if(i>=l.length)return;var a=document.createElement('a');
a.href=l[i].getAttribute('data-dl');a.download='';document.body.appendChild(a);a.click();a.remove();i++;setTimeout(nx,1500);})();}
</script></body></html>"""
        )
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", sb.toString())
    }
}
