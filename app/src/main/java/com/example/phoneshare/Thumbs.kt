package com.example.phoneshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** 生成预览用小缩略图（JPEG，最长边 480px），避免 iPhone 加载原图。 */
object Thumbs {

    fun make(src: File, isVideo: Boolean, out: File): Boolean {
        return try {
            val raw = (if (isVideo) videoFrame(src) else imageBitmap(src)) ?: return false
            val scale = 480f / maxOf(raw.width, raw.height)
            val bmp = if (scale < 1f)
                Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * scale).toInt().coerceAtLeast(1),
                    (raw.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            else raw
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun imageBitmap(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / sample > 960 || o.outHeight / sample > 960) sample *= 2
        val d = BitmapFactory.Options().apply { inSampleSize = sample }
        val b = BitmapFactory.decodeFile(f.path, d) ?: return null
        val deg = try {
            when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
        return if (deg == 0) b
        else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }

    private fun videoFrame(f: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}
