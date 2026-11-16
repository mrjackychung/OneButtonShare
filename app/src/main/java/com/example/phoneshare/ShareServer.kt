package com.example.phoneshare

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLEncoder

data class ShareItem(val file: File, val name: String, val mime: String)

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
        "Photos: long-press and choose “Add to Photos”. Or tap Download on any item.",
        "⬇ Download all (%d)",
        "Files download one by one. If Safari asks, tap Download / Allow.",
        "Download"
    ),
    "zh-Hans" to Strs(
        "来自安卓的 %d 个文件",
        "照片：长按图片，选择“添加到照片”；也可以点每项下面的“下载”。",
        "⬇ 一键下载全部（%d 个）",
        "将逐个依次下载。如果 Safari 弹出询问，请点“下载”或“允许”。",
        "下载"
    ),
    "zh-Hant" to Strs(
        "來自 Android 的 %d 個檔案",
        "照片：長按圖片，選擇「加入照片」；也可以點每項下方的「下載」。",
        "⬇ 一鍵下載全部（%d 個）",
        "將逐個依序下載。如果 Safari 彈出詢問，請點「下載」或「允許」。",
        "下載"
    ),
    "ja" to Strs(
        "Android からのファイル %d 件",
        "写真：長押しして「“写真”に追加」を選択。各項目の「ダウンロード」でも保存できます。",
        "⬇ すべてダウンロード（%d 件）",
        "1つずつ順番にダウンロードされます。Safari で確認が出たら「ダウンロード」を選んでください。",
        "ダウンロード"
    ),
    "ko" to Strs(
        "Android에서 보낸 파일 %d개",
        "사진: 길게 눌러 “사진에 추가”를 선택하세요. 각 항목의 다운로드를 눌러도 됩니다.",
        "⬇ 전체 다운로드 (%d개)",
        "하나씩 차례로 다운로드됩니다. Safari에서 묻으면 “다운로드”를 누르세요.",
        "다운로드"
    ),
    "es" to Strs(
        "%d archivo(s) desde Android",
        "Fotos: mantén pulsada y elige “Añadir a Fotos”. O toca Descargar en cada elemento.",
        "⬇ Descargar todo (%d)",
        "Se descargan uno por uno. Si Safari pregunta, toca Descargar.",
        "Descargar"
    ),
    "fr" to Strs(
        "%d fichier(s) depuis Android",
        "Photos : appui long puis « Ajouter à Photos ». Ou touchez Télécharger sur chaque élément.",
        "⬇ Tout télécharger (%d)",
        "Les fichiers se téléchargent un par un. Si Safari demande, touchez Télécharger.",
        "Télécharger"
    ),
    "de" to Strs(
        "%d Datei(en) von Android",
        "Fotos: gedrückt halten und „Zu Fotos hinzufügen“ wählen. Oder bei jedem Eintrag Laden tippen.",
        "⬇ Alle laden (%d)",
        "Die Dateien werden nacheinander geladen. Wenn Safari fragt, „Laden“ tippen.",
        "Laden"
    )
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
            return page(lang)
        }
        if (uri.startsWith("/f/")) {
            val idx = uri.removePrefix("/f/").toIntOrNull()
            val item = idx?.let { items.getOrNull(it) }
                ?: return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
            val isDl = session.parms["dl"] == "1"
            val raw = FileInputStream(item.file)
            val stream: InputStream =
                if (isDl) TrackingStream(raw, item.file.length()) { listener("file", idx) } else raw
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

    private fun page(lang: String): Response {
        val s = STRINGS.getValue(lang)
        val n = items.size
        val sb = StringBuilder()
        sb.append(
            """<!doctype html><html lang="$lang"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(s.title.format(n))}</title>
<style>
body{font-family:-apple-system,sans-serif;margin:0;padding:12px;background:#f2f2f7;color:#111}
h2{margin:8px 0}.tip{color:#555;font-size:14px;margin:8px 0 14px}
.top{display:flex;justify-content:space-between;align-items:center}
select{font-size:14px;padding:4px;border-radius:6px}
.all{display:block;width:100%;box-sizing:border-box;text-align:center;background:#34c759;color:#fff;
 font-size:20px;font-weight:700;padding:18px 12px;border-radius:14px;text-decoration:none;margin:10px 0 6px}
.hint{color:#666;font-size:12px;text-align:center}
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
        sb.append("<a class=\"all\" href=\"#\" onclick=\"dlAll();return false\">${esc(s.dlAll.format(n))}</a>\n")
        sb.append("<div class=\"hint\">${esc(s.dlAllHint)}</div>\n")
        sb.append("<div class=\"tip\">${esc(s.tip)}</div>\n")
        items.forEachIndexed { i, it ->
            sb.append("<div class=\"c\">")
            if (it.mime.startsWith("video/")) sb.append("<div class=\"v\">🎬</div>")
            else sb.append("<img src=\"/f/$i\" loading=\"lazy\">")
            sb.append("<div class=\"n\">${esc(it.name)} · ${sizeText(it.file.length())}</div>")
            sb.append("<a class=\"b\" href=\"/f/$i?dl=1\">${esc(s.dl)}</a></div>\n")
        }
        sb.append(
            """<script>
function dlAll(){var l=document.querySelectorAll('a.b'),i=0;
(function nx(){if(i>=l.length)return;var a=document.createElement('a');
a.href=l[i].href;a.download='';document.body.appendChild(a);a.click();a.remove();i++;setTimeout(nx,1500);})();}
</script></body></html>"""
        )
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", sb.toString())
    }
}
