package com.example.phoneshare

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder

data class ShareItem(val file: File, val name: String, val mime: String, val thumb: File? = null)

/** 统计已发送字节，发送完毕（达到总长度）时回调一次。 */
class TrackingStream(
    private val inner: InputStream,
    private val total: Long,
    private val onDone: () -> Unit,
    private val onProgress: ((Long) -> Unit)? = null
) : InputStream() {
    private var sent = 0L
    private var fired = false
    private var lastP = 0L
    private fun add(n: Int) {
        if (n > 0) {
            sent += n
            val now = System.nanoTime()
            if (onProgress != null && now - lastP > 250_000_000L) { lastP = now; onProgress.invoke(sent) }
        }
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
        "⬇ Download all, one by one (%d)",
        "Files download one by one. If Safari asks, tap Download / Allow.",
        "Download"
    ),
    "zh-Hans" to Strs(
        "来自安卓的 %d 个文件",
        "点开照片后长按图片，选择“添加到照片”。",
        "⬇ 逐个下载全部（%d 个）",
        "将逐个依次下载。如果 Safari 弹出询问，请点“下载”或“允许”。",
        "下载"
    ),
    "zh-Hant" to Strs(
        "來自 Android 的 %d 個檔案",
        "點開照片後長按圖片，選擇「加入照片」。",
        "⬇ 逐個下載全部（%d 個）",
        "將逐個依序下載。如果 Safari 彈出詢問，請點「下載」或「允許」。",
        "下載"
    ),
    "ja" to Strs(
        "Android からのファイル %d 件",
        "写真をタップして開き、長押しして「“写真”に追加」を選択。",
        "⬇ すべてを1つずつダウンロード（%d 件）",
        "1つずつ順番にダウンロードされます。Safari で確認が出たら「ダウンロード」を選んでください。",
        "ダウンロード"
    ),
    "ko" to Strs(
        "Android에서 보낸 파일 %d개",
        "사진을 탭해 연 다음 길게 눌러 “사진에 추가”를 선택하세요.",
        "⬇ 전체를 하나씩 다운로드 (%d개)",
        "하나씩 차례로 다운로드됩니다. Safari에서 묻으면 “다운로드”를 누르세요.",
        "다운로드"
    ),
    "es" to Strs(
        "%d archivo(s) desde Android",
        "Toca una foto para abrirla, mantenla pulsada y elige “Añadir a Fotos”.",
        "⬇ Descargar todo, uno por uno (%d)",
        "Se descargan uno por uno. Si Safari pregunta, toca Descargar.",
        "Descargar"
    ),
    "fr" to Strs(
        "%d fichier(s) depuis Android",
        "Touchez une photo, appuyez longuement puis « Ajouter à Photos ».",
        "⬇ Tout télécharger, un par un (%d)",
        "Les fichiers se téléchargent un par un. Si Safari demande, touchez Télécharger.",
        "Télécharger"
    ),
    "de" to Strs(
        "%d Datei(en) von Android",
        "Foto antippen, gedrückt halten und „Zu Fotos hinzufügen“ wählen.",
        "⬇ Alle einzeln laden (%d)",
        "Die Dateien werden nacheinander geladen. Wenn Safari fragt, „Laden“ tippen.",
        "Laden"
    )
)

/** 若填入快捷指令的 iCloud 分享链接（https://www.icloud.com/shortcuts/...），页面会显示“安装快捷指令”按钮。 */
const val SHORTCUT_ICLOUD_URL = "https://www.icloud.com/shortcuts/a7e66990e0544ba1b9e4363029b01894"

private val MORE = mapOf(
    "en" to "More options", "zh-Hans" to "更多方式", "zh-Hant" to "更多方式", "ja" to "その他の方法",
    "ko" to "더 보기", "es" to "Más opciones", "fr" to "Plus d’options", "de" to "Mehr Optionen"
)

private val SC_INSTALL = mapOf(
    "en" to "Adds a shortcut named SaveFromAndroid to your Shortcuts app. You can delete it anytime (Shortcuts app → touch and hold → Delete).",
    "zh-Hans" to "会在你的「快捷指令」App 里添加一个名为 SaveFromAndroid 的快捷指令，随时可以删除（快捷指令 App → 长按 → 删除）。",
    "zh-Hant" to "會在你的「捷徑」App 裡加入一個名為 SaveFromAndroid 的捷徑，隨時可以刪除（捷徑 App → 長按 → 刪除）。",
    "ja" to "「ショートカット」アプリに SaveFromAndroid というショートカットが追加されます。いつでも削除できます（ショートカット → 長押し → 削除）。",
    "ko" to "“단축어” 앱에 SaveFromAndroid 단축어가 추가됩니다. 언제든 삭제할 수 있습니다 (단축어 앱 → 길게 누르기 → 삭제).",
    "es" to "Se añadirá un atajo llamado SaveFromAndroid a la app Atajos. Puedes eliminarlo cuando quieras (Atajos → mantén pulsado → Eliminar).",
    "fr" to "Un raccourci nommé SaveFromAndroid sera ajouté à l’app Raccourcis. Vous pouvez le supprimer à tout moment (Raccourcis → appui long → Supprimer).",
    "de" to "Es wird ein Kurzbefehl namens SaveFromAndroid in der Kurzbefehle-App hinzugefügt. Du kannst ihn jederzeit löschen (Kurzbefehle → gedrückt halten → Löschen)."
)

private val SC_NEEDNET = mapOf(
    "en" to "⚠ Needs internet: turn on cellular data, or add it before joining this Wi-Fi.",
    "zh-Hans" to "⚠ 需要联网：请开启手机流量，或在加入此 Wi-Fi 之前先添加。",
    "zh-Hant" to "⚠ 需要連網：請開啟行動數據，或在加入此 Wi-Fi 之前先加入。",
    "ja" to "⚠ インターネット接続が必要です。モバイル通信をオンにするか、このWi-Fiに接続する前に追加してください。",
    "ko" to "⚠ 인터넷 연결이 필요합니다. 모바일 데이터를 켜거나 이 Wi-Fi에 연결하기 전에 추가하세요.",
    "es" to "⚠ Requiere internet: activa los datos móviles o añádelo antes de conectarte a este Wi-Fi.",
    "fr" to "⚠ Nécessite Internet : activez les données mobiles ou ajoutez-le avant de rejoindre ce Wi-Fi.",
    "de" to "⚠ Internet erforderlich: Mobilfunkdaten einschalten oder vor dem Verbinden mit diesem WLAN hinzufügen."
)

private val SC_HOWTO = mapOf(
    "en" to "After it downloads, tap the file (Safari’s download button, top right) and choose “Add Shortcut”.",
    "zh-Hans" to "下载完成后，点开该文件（Safari 右上角的下载按钮），选择「添加快捷指令」。",
    "zh-Hant" to "下載完成後，點開該檔案（Safari 右上角的下載按鈕），選擇「加入捷徑」。",
    "ja" to "ダウンロード後、ファイルをタップ（Safari 右上のダウンロードボタン）して「ショートカットを追加」を選択。",
    "ko" to "다운로드가 끝나면 파일을 탭(Safari 오른쪽 위 다운로드 버튼)하고 “단축어 추가”를 선택하세요.",
    "es" to "Tras la descarga, toca el archivo (botón de descargas de Safari) y elige «Añadir atajo».",
    "fr" to "Une fois téléchargé, touchez le fichier (bouton de téléchargement de Safari) puis « Ajouter le raccourci ».",
    "de" to "Nach dem Download die Datei antippen (Download-Symbol in Safari) und „Kurzbefehl hinzufügen“ wählen."
)

private val SC_STEP1 = mapOf(
    "en" to "① Add the shortcut (first time only)",
    "zh-Hans" to "① 添加快捷指令（仅第一次）",
    "zh-Hant" to "① 加入捷徑（僅第一次）",
    "ja" to "① ショートカットを追加（初回のみ）",
    "ko" to "① 단축어 추가 (처음 한 번만)",
    "es" to "① Añadir el atajo (solo la primera vez)",
    "fr" to "① Ajouter le raccourci (première fois seulement)",
    "de" to "① Kurzbefehl hinzufügen (nur beim ersten Mal)"
)

private val SC_SAVE = mapOf(
    "en" to "② Save all to Photos",
    "zh-Hans" to "② 一键存入相册",
    "zh-Hant" to "② 一鍵存入相簿",
    "ja" to "② すべて写真に保存",
    "ko" to "② 모두 사진에 저장",
    "es" to "② Guardar todo en Fotos",
    "fr" to "② Tout enregistrer dans Photos",
    "de" to "② Alle in Fotos sichern"
)

/** iOS“快捷指令”一键存入相册：按钮文字 + 帮助页 */
private val SC_BTN = mapOf(
    "en" to Pair("② Save all to Photos (no confirmations)", "First time? One-time setup (2 min)"),
    "zh-Hans" to Pair("② 一键存入相册（无需逐个确认）", "第一次使用？查看一次性设置（约 2 分钟）"),
    "zh-Hant" to Pair("② 一鍵存入相簿（無需逐個確認）", "第一次使用？查看一次性設定（約 2 分鐘）")
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

/** 上传页文字：标题、选择按钮、提示、完成、失败 */
private val UPLOAD_STR = mapOf(
    "en" to listOf("Send photos to Android", "📷 Choose photos / videos", "Select as many as you like. Keep this page open until it finishes.", "✅ All sent!", "⚠ Some files failed. Please try again."),
    "zh-Hans" to listOf("发送照片到安卓", "📷 选择照片 / 视频", "可以多选。传输完成前请保持此页面打开。", "✅ 全部发送完成！", "⚠ 有文件发送失败，请重试。"),
    "zh-Hant" to listOf("傳送照片到 Android", "📷 選擇照片 / 影片", "可多選。傳送完成前請保持此頁面開啟。", "✅ 全部傳送完成！", "⚠ 有檔案傳送失敗，請重試。"),
    "ja" to listOf("Android に写真を送る", "📷 写真・ビデオを選択", "複数選択できます。完了するまでこのページを開いたままにしてください。", "✅ すべて送信しました！", "⚠ 送信に失敗したファイルがあります。もう一度お試しください。"),
    "ko" to listOf("Android로 사진 보내기", "📷 사진 / 동영상 선택", "여러 개 선택할 수 있습니다. 완료될 때까지 이 페이지를 열어 두세요.", "✅ 모두 전송했습니다!", "⚠ 일부 파일 전송에 실패했습니다. 다시 시도하세요."),
    "es" to listOf("Enviar fotos a Android", "📷 Elegir fotos / vídeos", "Puedes elegir varias. Mantén esta página abierta hasta que termine.", "✅ ¡Todo enviado!", "⚠ Algunos archivos fallaron. Inténtalo de nuevo."),
    "fr" to listOf("Envoyer des photos vers Android", "📷 Choisir photos / vidéos", "Sélection multiple possible. Gardez cette page ouverte jusqu’à la fin.", "✅ Tout est envoyé !", "⚠ Certains fichiers ont échoué. Réessayez."),
    "de" to listOf("Fotos an Android senden", "📷 Fotos / Videos auswählen", "Mehrfachauswahl möglich. Seite bis zum Ende geöffnet lassen.", "✅ Alles gesendet!", "⚠ Einige Dateien sind fehlgeschlagen. Bitte erneut versuchen.")
)

private const val UPLOAD_HTML = """<!doctype html><html lang="__LANG__"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>__TITLE__</title>
<style>
body{font-family:-apple-system,sans-serif;margin:0;padding:16px;background:#f2f2f7;color:#111}
h2{margin:8px 0 4px}.hint{color:#555;font-size:14px;margin-bottom:16px}
.pick{display:block;text-align:center;background:#34c759;color:#fff;font-size:22px;font-weight:700;padding:22px 12px;border-radius:16px}
#sum{margin:14px 0 6px;font-size:15px;font-weight:600;text-align:center}
.bar{height:6px;background:#d1d1d6;border-radius:3px;overflow:hidden}
.bar i{display:block;height:6px;width:0;background:#007aff}
.r{background:#fff;border-radius:10px;padding:8px 10px;margin-top:8px}
.r .n{font-size:13px;color:#555;margin-bottom:6px;word-break:break-all}
.r.ok .bar i{background:#34c759}.r.bad .bar i{background:#ff3b30}
</style></head>
<body data-t="__TOKEN__" data-done="__DONE__" data-fail="__FAIL__">
<h2>__TITLE__</h2>
<div class="hint">__HINT__</div>
<label class="pick">__CHOOSE__<input id="f" type="file" multiple accept="image/*,video/*" style="display:none"></label>
<div id="sum"></div>
<div class="bar"><i id="ob"></i></div>
<div id="list"></div>
<script>
var T=document.body.getAttribute('data-t');
var DONE=document.body.getAttribute('data-done'),FAIL=document.body.getAttribute('data-fail');
var list=document.getElementById('list'),sum=document.getElementById('sum'),ob=document.getElementById('ob');
document.getElementById('f').addEventListener('change',function(e){start(Array.prototype.slice.call(e.target.files));});
function fmt(b){return b>=1048576?(b/1048576).toFixed(1)+' MB':Math.round(b/1024)+' KB';}
function start(files){
 if(!files.length)return;
 list.innerHTML='';ob.style.width='0%';sum.textContent='';
 var total=0;files.forEach(function(f){total+=f.size;});
 var rows=files.map(function(f){var d=document.createElement('div');d.className='r';
  d.innerHTML='<div class="n"></div><div class="bar"><i></i></div>';
  d.firstChild.textContent=f.name+' · '+fmt(f.size);list.appendChild(d);return d;});
 var done=0,fail=0,idx=0,t0=Date.now();
 function next(){
  if(idx>=files.length){sum.textContent=fail?FAIL:DONE;if(!fail)ob.style.width='100%';return;}
  var f=files[idx],row=rows[idx],i=idx;
  var x=new XMLHttpRequest();
  x.open('POST','/put?t='+encodeURIComponent(T)+'&name='+encodeURIComponent(f.name)+'&bi='+i+'&bc='+files.length+'&bt='+total);
  x.setRequestHeader('Content-Type',f.type||'application/octet-stream');
  x.upload.onprogress=function(e){
   var l=e.loaded,all=done+l,s=(Date.now()-t0)/1000;
   row.lastChild.firstChild.style.width=(f.size?l*100/f.size:100)+'%';
   ob.style.width=(total?all*100/total:100)+'%';
   sum.textContent=Math.round(total?all*100/total:100)+'% · '+fmt(all)+' / '+fmt(total)+(s>1?' · '+fmt(all/s)+'/s':'');
  };
  x.onload=function(){
   if(x.status==200){done+=f.size;row.className='r ok';row.lastChild.firstChild.style.width='100%';}
   else{fail++;row.className='r bad';}
   idx++;next();
  };
  x.onerror=function(){fail++;row.className='r bad';idx++;next();};
  x.send(f);
 }
 next();
}
</script></body></html>"""

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
    private val shortcutAsset: (() -> InputStream)?,
    private val receiver: Receiver?,
    private val progress: (index: Int, sent: Long, total: Long) -> Unit,
    private val listener: (kind: String, index: Int) -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (receiver != null) {
            if (uri == "/upload") {
                if (session.parms["t"] != receiver.token) return plain(Status.FORBIDDEN, "Invalid link. Scan the QR code again.")
                val lang = pickLang(session.headers["accept-language"], session.parms["lang"])
                return uploadPage(lang, receiver.token)
            }
            if (uri == "/put" && session.method == Method.POST) return handlePut(session, receiver)
            return plain(Status.NOT_FOUND, "Scan the QR code on the Android screen.")
        }
        if (uri == "/" || uri.isEmpty()) {
            listener("open", -1)
            val lang = pickLang(session.headers["accept-language"], session.parms["lang"])
            return page(lang, session.headers["host"] ?: "")
        }
        if (uri == "/SaveFromAndroid.shortcut") {
            val bytes = try { shortcutAsset?.invoke()?.use { it.readBytes() } } catch (e: Exception) { null }
            if (bytes != null) {
                val res = NanoHTTPD.newFixedLengthResponse(
                    Status.OK, "application/vnd.apple.shortcut", ByteArrayInputStream(bytes), bytes.size.toLong()
                )
                res.addHeader("Content-Disposition", "attachment; filename=\"SaveFromAndroid.shortcut\"")
                res.addHeader("Cache-Control", "no-store")
                return res
            }
            return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
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
                res.addHeader("Cache-Control", "no-store")
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
                if (track) TrackingStream(raw, item.file.length(), { listener("file", idx) }, { sent -> progress(idx!!, sent, item.file.length()) }) else raw
            val res = NanoHTTPD.newFixedLengthResponse(Status.OK, item.mime, stream, item.file.length())
            val enc = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
            val dispo = if (isDl) "attachment" else "inline"
            res.addHeader("Content-Disposition", "$dispo; filename*=UTF-8''$enc")
            res.addHeader("Cache-Control", "no-store")
            return res
        }
        return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
    }

    private val ver = System.currentTimeMillis()

    private fun plain(st: Status, msg: String): Response =
        NanoHTTPD.newFixedLengthResponse(st, "text/plain; charset=utf-8", msg)

    private fun handlePut(session: IHTTPSession, r: Receiver): Response {
        if (session.parms["t"] != r.token) return plain(Status.FORBIDDEN, "bad token")
        val len = session.headers["content-length"]?.toLongOrNull() ?: return plain(Status.BAD_REQUEST, "length required")
        val name = (session.parms["name"] ?: "file").filter { it.code >= 32 && it !in "/\\:*?\"<>|" }.take(120).ifEmpty { "file" }
        val mime = (session.headers["content-type"] ?: "").substringBefore(';').trim()
        val bi = session.parms["bi"]?.toIntOrNull() ?: 0
        val bc = session.parms["bc"]?.toIntOrNull() ?: 1
        val bt = session.parms["bt"]?.toLongOrNull() ?: len
        val handle = r.open(name, mime) ?: return plain(Status.BAD_REQUEST, "unsupported file type")
        r.onEvent(ReceiveEvent("start", bi, bc, name, 0L, len, bt))
        return try {
            val inp = session.inputStream
            val buf = ByteArray(256 * 1024)
            var remaining = len
            var got = 0L
            var last = 0L
            while (remaining > 0) {
                val n = inp.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw java.io.IOException("connection closed")
                handle.out.write(buf, 0, n)
                remaining -= n
                got += n
                val now = System.nanoTime()
                if (now - last > 250_000_000L) {
                    last = now
                    r.onEvent(ReceiveEvent("progress", bi, bc, name, got, len, bt))
                }
            }
            handle.out.close()
            handle.finish()
            r.onEvent(ReceiveEvent("done", bi, bc, name, got, len, bt))
            NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", "{\"ok\":true}")
        } catch (e: Exception) {
            try { handle.out.close() } catch (_: Exception) {}
            try { handle.abort() } catch (_: Exception) {}
            r.onEvent(ReceiveEvent("fail", bi, bc, name, 0L, len, bt))
            plain(Status.INTERNALERROR, "failed")
        }
    }

    private fun uploadPage(lang: String, token: String): Response {
        val u = UPLOAD_STR[lang] ?: UPLOAD_STR.getValue("en")
        fun attr(x: String) = esc(x).replace("\"", "&quot;")
        val html = UPLOAD_HTML
            .replace("__LANG__", lang)
            .replace("__TITLE__", esc(u[0]))
            .replace("__CHOOSE__", esc(u[1]))
            .replace("__HINT__", esc(u[2]))
            .replace("__DONE__", attr(u[3]))
            .replace("__FAIL__", attr(u[4]))
            .replace("__TOKEN__", attr(token))
        val res = NanoHTTPD.newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", html)
        res.addHeader("Cache-Control", "no-store")
        return res
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
        sb.append("<a class=\"sc\" style=\"background:#5856d6\" href=\"/SaveFromAndroid.shortcut\">${esc(SC_STEP1[lang] ?: SC_STEP1.getValue("en"))}</a>\n")
        sb.append("<div class=\"hint\">${esc(SC_HOWTO[lang] ?: SC_HOWTO.getValue("en"))}<br>${esc(SC_INSTALL[lang] ?: SC_INSTALL.getValue("en"))}</div>\n")
        sb.append("<a class=\"main\" href=\"$scUrl\">${esc(SC_SAVE[lang] ?: SC_SAVE.getValue("en"))}</a>\n")
        sb.append("<details class=\"adv\"><summary>${esc(MORE[lang] ?: "More options")}</summary>\n")
        sb.append("<a class=\"all\" href=\"#\" onclick=\"dlAll();return false\">${esc(s.dlAll.format(n))}</a>\n")
        sb.append("<div class=\"hint\">${esc(s.dlAllHint)}</div></details>\n")
        sb.append("<div class=\"g\">")
        items.forEachIndexed { i, it ->
            val isVideo = it.mime.startsWith("video/")
            val href = if (isVideo) "/f/$i?dl=1" else "/f/$i"
            sb.append("<a class=\"t\" href=\"$href\" data-dl=\"/f/$i?dl=1\">")
            if (it.thumb != null) sb.append("<img src=\"/t/$i?v=$ver\" loading=\"lazy\">")
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
        val pageRes = NanoHTTPD.newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", sb.toString())
        pageRes.addHeader("Cache-Control", "no-store")
        return pageRes
    }
}
