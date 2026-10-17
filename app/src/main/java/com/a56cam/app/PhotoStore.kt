package com.a56cam.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves photos into DCIM/A56Cam through MediaStore (no storage permission needed). */
object PhotoStore {

    const val RELATIVE_PATH = "DCIM/A56Cam"

    /** File name stem for a capture taken now, e.g. A56Cam_20261017_101500_123. */
    fun baseName(now: Long = System.currentTimeMillis()): String =
        "A56Cam_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))

    fun saveJpeg(
        context: Context,
        bytes: ByteArray,
        name: String = baseName() + ".jpg",
        relativePath: String = "${Environment.DIRECTORY_DCIM}/A56Cam",
    ): Uri = save(context, name, "image/jpeg", relativePath) { it.write(bytes) }

    /** Copies [file] into the gallery folder (or [relativePath]) under [name]. */
    fun saveFile(
        context: Context,
        file: File,
        mime: String,
        name: String,
        relativePath: String = "${Environment.DIRECTORY_DCIM}/A56Cam",
    ): Uri = save(context, name, mime, relativePath) { out -> file.inputStream().use { it.copyTo(out) } }

    fun saveBitmapPng(context: Context, bitmap: Bitmap, name: String, relativePath: String): Uri =
        save(context, name, "image/png", relativePath) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    /** Non-image files (debug stats) go to Documents, the only shared folder that accepts them. */
    fun saveText(context: Context, text: String, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/A56Cam/debug")
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("MediaStore insert returned null")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        return uri
    }

    private fun save(context: Context, name: String, mime: String, relativePath: String, write: (OutputStream) -> Unit): Uri {
        val now = System.currentTimeMillis()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore insert returned null for $name")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IllegalStateException("No output stream for $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Mean red, green and blue of a bitmap (0..255), logged with each photo to catch colour casts. */
    fun meanRgb(bitmap: Bitmap): String {
        var r = 0L
        var g = 0L
        var b = 0L
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 64)
        var n = 0L
        for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
            val c = bitmap.getPixel(x, y)
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            n++
        }
        return if (n == 0L) "n/a" else "R=${r / n} G=${g / n} B=${b / n}"
    }

    /** A small, upright preview of a JPEG for the thumbnail button. */
    fun thumbnail(bytes: ByteArray, targetPx: Int = 192): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotation = try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
        if (rotation == 0) return bitmap
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
}
