package com.a56cam.diagnostics

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** An NV21 copy of (part of) a YUV_420_888 frame, owned by Java memory so the [Image] can be closed at once. */
internal class Nv21(val width: Int, val height: Int, val data: ByteArray)

/** What the diagnostics keep from each frame: the image itself is closed straight after this is built. */
internal class FrameInfo(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val meanLuma: Double,
    val crop: Nv21?,
    val full: Nv21?,
)

internal object Yuv {

    /** Reusable row buffers for the image thread; bulk row reads are far faster than per-byte ByteBuffer.get(). */
    private val rowA = ThreadLocal<ByteArray>()
    private val rowB = ThreadLocal<ByteArray>()

    private fun row(local: ThreadLocal<ByteArray>, size: Int): ByteArray =
        local.get()?.takeIf { it.size >= size } ?: ByteArray(size).also { local.set(it) }

    /** Mean of the Y plane, sampled every [step] pixels in both directions (0..255). */
    fun meanLuma(image: Image, step: Int = 8): Double {
        val plane = image.planes[0]
        val buf = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowLen = (image.width - 1) * pixelStride + 1
        val line = row(rowA, rowLen)
        var sum = 0L
        var count = 0L
        var y = 0
        while (y < image.height) {
            buf.position(y * rowStride)
            buf.get(line, 0, rowLen)
            var x = 0
            while (x < image.width) {
                sum += line[x * pixelStride].toInt() and 0xFF
                count++
                x += step
            }
            y += step
        }
        return if (count == 0L) 0.0 else sum.toDouble() / count
    }

    /** Centre crop of at most [size] × [size] pixels, with even coordinates. */
    fun centerRect(width: Int, height: Int, size: Int): Rect {
        val w = minOf(size, width) and 1.inv()
        val h = minOf(size, height) and 1.inv()
        val left = ((width - w) / 2) and 1.inv()
        val top = ((height - h) / 2) and 1.inv()
        return Rect(left, top, left + w, top + h)
    }

    /** Copies [rect] of a YUV_420_888 image into NV21 (Y plane, then interleaved V/U). */
    fun copyNv21(image: Image, rect: Rect): Nv21 {
        val w = rect.width()
        val h = rect.height()
        val out = ByteArray(w * h + w * h / 2)
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer.duplicate()
        if (yPlane.pixelStride == 1) {
            for (row in 0 until h) {
                yBuf.position((rect.top + row) * yPlane.rowStride + rect.left)
                yBuf.get(out, row * w, w)
            }
        } else {
            val len = (w - 1) * yPlane.pixelStride + 1
            val line = row(rowA, len)
            for (r in 0 until h) {
                yBuf.position((rect.top + r) * yPlane.rowStride + rect.left * yPlane.pixelStride)
                yBuf.get(line, 0, len)
                for (c in 0 until w) out[r * w + c] = line[c * yPlane.pixelStride]
            }
        }
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()
        val cTop = rect.top / 2
        val cLeft = rect.left / 2
        val cw = w / 2
        val uLen = (cw - 1) * uPlane.pixelStride + 1
        val vLen = (cw - 1) * vPlane.pixelStride + 1
        val uRow = row(rowA, uLen)
        val vRow = row(rowB, vLen)
        var dst = w * h
        for (r in 0 until h / 2) {
            uBuf.position((cTop + r) * uPlane.rowStride + cLeft * uPlane.pixelStride)
            uBuf.get(uRow, 0, uLen)
            vBuf.position((cTop + r) * vPlane.rowStride + cLeft * vPlane.pixelStride)
            vBuf.get(vRow, 0, vLen)
            for (c in 0 until cw) {
                out[dst++] = vRow[c * vPlane.pixelStride]
                out[dst++] = uRow[c * uPlane.pixelStride]
            }
        }
        return Nv21(w, h, out)
    }

    fun saveJpeg(frame: Nv21, file: File, quality: Int = 95) {
        val yuv = YuvImage(frame.data, ImageFormat.NV21, frame.width, frame.height, null)
        file.outputStream().use { yuv.compressToJpeg(Rect(0, 0, frame.width, frame.height), quality, it) }
    }

    /** Lossless PNG, used for crops that are compared pixel for pixel. */
    fun savePng(frame: Nv21, file: File) {
        val w = frame.width
        val h = frame.height
        val argb = IntArray(w * h)
        val d = frame.data
        for (row in 0 until h) {
            val uvRow = w * h + (row / 2) * w
            for (col in 0 until w) {
                val y = d[row * w + col].toInt() and 0xFF
                val uvIndex = uvRow + (col / 2) * 2
                val v = (d[uvIndex].toInt() and 0xFF) - 128
                val u = (d[uvIndex + 1].toInt() and 0xFF) - 128
                val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772f * u).toInt().coerceIn(0, 255)
                argb[row * w + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** Mean absolute horizontal plus vertical luma gradient: higher means sharper (or noisier). */
    fun gradientEnergy(frame: Nv21): Double {
        val w = frame.width
        val h = frame.height
        val d = frame.data
        var sum = 0L
        for (row in 0 until h - 1) {
            for (col in 0 until w - 1) {
                val p = d[row * w + col].toInt() and 0xFF
                sum += abs((d[row * w + col + 1].toInt() and 0xFF) - p) + abs((d[(row + 1) * w + col].toInt() and 0xFF) - p)
            }
        }
        return sum.toDouble() / ((w - 1).toLong() * (h - 1))
    }

    fun lumaStdDev(frame: Nv21): Double {
        val n = frame.width * frame.height
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until n) {
            val v = (frame.data[i].toInt() and 0xFF).toDouble()
            sum += v
            sumSq += v * v
        }
        val mean = sum / n
        return sqrt(maxOf(0.0, sumSq / n - mean * mean))
    }
}
