package com.example.phoneshare

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder

data class ShareItem(val file: File, val name: String, val mime: String)

class ShareServer(port: Int, private val items: List<ShareItem>) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/" || uri.isEmpty()) return page()
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

    private fun page(): Response {
        val sb = StringBuilder()
        sb.append(
            """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>来自安卓的照片</title>
<style>
body{font-family:-apple-system,sans-serif;margin:0;padding:12px;background:#f2f2f7}
h2{margin:8px 0}.tip{color:#555;font-size:14px;margin-bottom:12px}
.c{background:#fff;border-radius:12px;padding:10px;margin-bottom:14px}
img{width:100%;border-radius:8px;display:block}
.n{font-size:13px;color:#666;margin:6px 0}
.b{display:inline-block;background:#007aff;color:#fff;padding:8px 16px;border-radius:8px;text-decoration:none}
</style></head><body>
<h2>共 ${items.size} 张照片</h2>
<div class="tip">长按图片 → 选择“添加到照片”即可存入相册；或点“下载”保存到“文件”。</div>
"""
        )
        items.forEachIndexed { i, it ->
            sb.append("<div class=\"c\"><img src=\"/f/$i\" loading=\"lazy\">")
            sb.append("<div class=\"n\">${esc(it.name)}</div>")
            sb.append("<a class=\"b\" href=\"/f/$i?dl=1\">下载</a></div>\n")
        }
        sb.append("</body></html>")
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", sb.toString())
    }
}
