package com.a56cam.processing

import android.graphics.Bitmap
import android.media.Image
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** Native entry points; see stack_jni.cpp. */
internal object StackNative {
    init {
        System.loadLibrary("a56processing")
    }

    external fun create(width: Int, height: Int, capacity: Int): Long
    external fun destroy(handle: Long)
    external fun count(handle: Long): Int
    external fun addFrame(
        handle: Long, y: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
        timestamp: Long, exposure: Double,
    ): Boolean
    external fun process(handle: Long, minSharpness: Float, hdr: Boolean, useGpu: Boolean): String?
    external fun gpuStatus(): String
    external fun tileWeights(handle: Long): FloatArray
    external fun outputSize(handle: Long, rotation: Int): IntArray
    external fun renderBitmap(handle: Long, bitmap: Bitmap, gain: Float, sharpen: Float, rotation: Int, look: Int): Boolean
    external fun writeTiff(handle: Long, path: String, gain: Float, sharpen: Float, rotation: Int, look: Int): Boolean
    external fun writeDng(handle: Long, path: String, gain: Float, rotation: Int, make: String, model: String): Boolean
    external fun frameNv21(handle: Long, index: Int): ByteArray?
}

/** Result of [StackProcessor.process]; the raw JSON is kept for logs and debug files. */
class StackStats(val json: JSONObject) {
    val frames: Int get() = json.optInt("frames")
    val reference: Int get() = json.optInt("reference")
    val tilesX: Int get() = json.optInt("tilesX")
    val tilesY: Int get() = json.optInt("tilesY")
    val alignMs: Int get() = json.optInt("alignMs")
    val mergeMs: Int get() = json.optInt("mergeMs")
    val rejected: Int get() = json.optJSONArray("rejected")?.length() ?: 0
    /** "gpu" or "cpu". */
    val engine: String get() = json.optString("engine", "cpu")
}

/**
 * Burst stack in native memory. Frames are copied out of each [Image] immediately (the caller closes
 * the image right after [addFrame]); processing aligns and merges them in C++.
 */
class StackProcessor(val width: Int, val height: Int, capacity: Int) : AutoCloseable {
    private var handle: Long = StackNative.create(width, height, capacity)

    init {
        check(handle != 0L) { "Could not allocate the native stack" }
    }

    val count: Int get() = StackNative.count(live())

    /** Copies a YUV_420_888 frame; [exposure] is exposure time × ISO (only ratios between frames matter). */
    fun addFrame(image: Image, exposure: Double): Boolean {
        val p = image.planes
        return StackNative.addFrame(
            live(), p[0].buffer, p[0].rowStride, p[0].pixelStride,
            p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
            image.timestamp, exposure,
        )
    }

    /** NV21 copy of a captured frame, for debug output; only before [process]. */
    fun frameNv21(index: Int): ByteArray? = StackNative.frameNv21(live(), index)

    /** [useGpu]: merge on the GPU when it passed its self-test (see [gpuStatus]); the CPU otherwise. */
    fun process(minSharpness: Float, hdr: Boolean, useGpu: Boolean = true): StackStats {
        val json = StackNative.process(live(), minSharpness, hdr, useGpu) ?: throw IllegalStateException("Stack processing failed")
        return StackStats(JSONObject(json))
    }

    fun tileWeights(): FloatArray = StackNative.tileWeights(live())

    fun outputSize(rotation: Int): Pair<Int, Int> = StackNative.outputSize(live(), rotation).let { it[0] to it[1] }

    /** [look]: 0 natural, 1 bright iPhone-style (see RenderParams in stack.h). */
    fun renderBitmap(rotation: Int, gain: Float, sharpen: Float, look: Int): Bitmap {
        val (w, h) = outputSize(rotation)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        check(StackNative.renderBitmap(live(), bitmap, gain, sharpen, rotation, look)) { "Rendering the stacked image failed" }
        return bitmap
    }

    fun writeTiff(file: File, rotation: Int, gain: Float, sharpen: Float, look: Int): Boolean =
        StackNative.writeTiff(live(), file.absolutePath, gain, sharpen, rotation, look)

    fun writeDng(file: File, rotation: Int, gain: Float, make: String, model: String): Boolean =
        StackNative.writeDng(live(), file.absolutePath, gain, rotation, make, model)

    private fun live(): Long {
        check(handle != 0L) { "StackProcessor already closed" }
        return handle
    }

    companion object {
        /**
         * Sets up the GPU merge on first call (a small CPU-vs-GPU comparison, about a second) and describes
         * the result. Call it off the main thread, before the first merge.
         */
        fun gpuStatus(): String = StackNative.gpuStatus()
    }

    override fun close() {
        if (handle != 0L) {
            StackNative.destroy(handle)
            handle = 0L
        }
    }
}
