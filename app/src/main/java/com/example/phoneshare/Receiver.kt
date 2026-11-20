package com.example.phoneshare

import java.io.OutputStream

/** 一个正在写入相册的文件。 */
class UploadHandle(val out: OutputStream, val finish: () -> Unit, val abort: () -> Unit)

/** type: start / progress / done / fail */
data class ReceiveEvent(
    val type: String,
    val index: Int,
    val count: Int,
    val name: String,
    val bytes: Long,
    val size: Long,
    val batchTotal: Long
)

/** 接收模式：iPhone 通过网页上传文件到安卓。token 用来防止同一热点里的其他设备乱传。 */
class Receiver(
    val token: String,
    val open: (name: String, mime: String) -> UploadHandle?,
    val onEvent: (ReceiveEvent) -> Unit
)
