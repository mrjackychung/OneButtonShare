package com.example.phoneshare

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder

data class ShareItem(val file: File, val name: String, val mime: String)

private class Strs(
    val title: String,   // %d = count
    val tip: String,
    val dlAll: String,   // %d = count
    val dlAllHint: String,
    val seq: String,
    val dl: String
)

private val STRINGS = mapOf(
    "en" to Strs(
        "%d photo(s) from Android",
        "Long-press a photo and choose “Add to Photos”, or tap Download to save to Files.",
        "⬇ Download all (%d)",
        "Saves one ZIP to Files. Tap the ZIP to unzip, then save the photos.",
        "Download one by one", "Download"
    ),
    "zh-Hans" to Strs(
        "来自安卓的 %d 张照片",
        "长按图片，选择“添加到照片”即可存入相册；也可点“下载”保存到“文件”。",
        "⬇ 一键下载全部（%d 张）",
        "将保存一个 ZIP 压缩包到“文件”，点按压缩包即可解压。",
        "逐张下载", "下载"
    ),
    "zh-Hant" to Strs(
        "來自 Android 的 %d 張照片",
        "長按圖片，選擇「加入照片」即可存入相簿；也可點「下載」儲存到「檔案」。",
        "⬇ 一鍵下載全部（%d 張）",
        "將儲存一個 ZIP 壓縮檔到「檔案」，點按壓縮檔即可解壓縮。",
        "逐張下載", "下載"
    ),
    "ja" to Strs(
        "Android からの写真 %d 枚",
        "写真を長押しして「“写真”に追加」を選ぶか、「ダウンロード」で「ファイル」に保存します。",
        "⬇ すべてダウンロード（%d 枚）",
        "ZIP ファイルが「ファイル」に保存されます。タップして解凍してください。",
        "1枚ずつダウンロード", "ダウンロード"
    ),
    "ko" to Strs(
        "Android에서 보낸 사진 %d장",
        "사진을 길게 눌러 “사진에 추가”를 선택하거나, 다운로드를 눌러 “파일”에 저장하세요.",
        "⬇ 전체 다운로드 (%d장)",
        "ZIP 파일이 “파일”에 저장됩니다. 탭하여 압축을 푸세요.",
        "한 장씩 다운로드", "다운로드"
    ),
    "es" to Strs(
        "%d foto(s) desde Android",
        "Mantén pulsada una foto y elige “Añadir a Fotos”, o toca Descargar para guardar en Archivos.",
        "⬇ Descargar todo (%d)",
        "Guarda un ZIP en Archivos. Tócalo para descomprimir.",
        "Descargar una por una", "Descargar"
    ),
    "fr" to Strs(
        "%d photo(s) depuis Android",
        "Appuyez longuement sur une photo puis « Ajouter à Photos », ou touchez Télécharger pour enregistrer dans Fichiers.",
        "⬇ Tout télécharger (%d)",
        "Enregistre un ZIP dans Fichiers. Touchez-le pour le décompresser.",
        "Télécharger une par une", "Télécharger"
    ),
    "de" to Strs(
        "%d Foto(s) von Android",
        "Foto gedrückt halten und „Zu Fotos hinzufügen“ wählen, oder Laden tippen, um in Dateien zu sichern.",
        "⬇ Alle laden (%d)",
        "Speichert eine ZIP-Datei in Dateien. Zum Entpacken antippen.",
        "Einzeln laden", "Laden"
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

class ShareServer(port: Int, private val items: List<ShareItem>, private val zip: File?) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/" || uri.isEmpty()) {
            val lang = pickLang(session.headers["accept-language"], session.parms["lang"])
            return page(lang)
        }
        if (uri == "/all.zip" && zip != null && zip.exists()) {
            val res = NanoHTTPD.newFixedLengthResponse(
                Status.OK, "application/zip", FileInputStream(zip), zip.length()
            )
            res.addHeader("Content-Disposition", "attachment; filename=\"photos.zip\"")
            return res
        }
        if (uri.startsWith("/f/")) {
            val idx = uri.removePrefix("/f/").toIntOrNull()
            val item = idx?.let { items.getOrNull(it) }
                ?: return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
            val res = NanoHTTPD.newFixedLengthResponse(
                Status.OK, item.mime, FileInputStream(item.file), item.file.length()
            )
            val enc = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
            val dispo = if (session.parms["dl"] == "1") "attachment" else "inline"
            res.addHeader("Content-Disposition", "$dispo; filename*=UTF-8''$enc")
            return res
        }
        return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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
h2{margin:8px 0}.tip{color:#555;font-size:14px;margin-bottom:12px}
.top{display:flex;justify-content:space-between;align-items:center}
select{font-size:14px;padding:4px;border-radius:6px}
.all{display:block;width:100%;box-sizing:border-box;text-align:center;background:#34c759;color:#fff;
 font-size:20px;font-weight:700;padding:18px 12px;border-radius:14px;text-decoration:none;margin:10px 0 6px}
.hint{color:#666;font-size:12px;text-align:center;margin-bottom:8px}
.seq{display:block;width:100%;box-sizing:border-box;text-align:center;background:#fff;color:#007aff;
 border:1px solid #007aff;font-size:16px;padding:12px;border-radius:12px;margin-bottom:16px}
.c{background:#fff;border-radius:12px;padding:10px;margin-bottom:14px}
img{width:100%;border-radius:8px;display:block}
.n{font-size:13px;color:#666;margin:6px 0}
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
        sb.append("<a class=\"all\" href=\"/all.zip\">${esc(s.dlAll.format(n))}</a>\n")
        sb.append("<div class=\"hint\">${esc(s.dlAllHint)}</div>\n")
        sb.append("<a class=\"seq\" href=\"#\" onclick=\"seq();return false\">${esc(s.seq)}</a>\n")
        sb.append("<div class=\"tip\">${esc(s.tip)}</div>\n")
        items.forEachIndexed { i, it ->
            sb.append("<div class=\"c\"><img src=\"/f/$i\" loading=\"lazy\">")
            sb.append("<div class=\"n\">${esc(it.name)}</div>")
            sb.append("<a class=\"b\" href=\"/f/$i?dl=1\">${esc(s.dl)}</a></div>\n")
        }
        sb.append(
            """<script>
function seq(){var l=document.querySelectorAll('a.b'),i=0;
(function nx(){if(i>=l.length)return;var a=document.createElement('a');
a.href=l[i].href;a.download='';document.body.appendChild(a);a.click();a.remove();i++;setTimeout(nx,900);})();}
</script></body></html>"""
        )
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", sb.toString())
    }
}
