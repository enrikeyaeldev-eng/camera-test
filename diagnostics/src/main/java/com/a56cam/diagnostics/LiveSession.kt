package com.a56cam.diagnostics

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.util.Size
import android.view.Surface
import com.a56cam.camera.AppLog
import com.a56cam.camera.CameraOps
import com.a56cam.camera.CameraThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** A capture result paired with the frame it describes (matched by sensor timestamp). */
internal class Sample(val result: TotalCaptureResult, val frame: FrameInfo) {
    val tag: Any? get() = result.request.tag
    fun <T> get(key: CaptureResult.Key<T>): T? = result.get(key)
}

/** Raw timing record used by the burst test, including frames that never arrived. */
internal class RawLog(val tagPrefix: String) {
    class ResultStamp(val timestampNs: Long, val frameNumber: Long)
    val results = mutableListOf<ResultStamp>()
    val frameTimestamps = mutableListOf<Long>()
    var failures = 0
    var bufferLost = 0
}

/**
 * One open camera with one YUV [ImageReader] and a capture session on it.
 * Every image is analysed and closed immediately on the image thread, so the reader never fills up.
 */
internal class LiveSession private constructor(
    val cameraId: String,
    val device: CameraDevice,
    val session: CameraCaptureSession,
    private val reader: ImageReader,
    val size: Size,
    private val cameraThread: CameraThread,
    imageThread: CameraThread,
) : AutoCloseable {

    private class Waiter(val count: Int, val accept: (Sample) -> Boolean) {
        val samples = mutableListOf<Sample>()
        val done = CompletableDeferred<List<Sample>>()
    }

    private val lock = Any()
    private val unmatchedFrames = ArrayDeque<FrameInfo>()
    private val unmatchedResults = ArrayDeque<TotalCaptureResult>()
    private var waiter: Waiter? = null
    private var rawLog: RawLog? = null

    /** Side of the centre crop copied from each frame; 0 copies nothing. */
    @Volatile var cropSize = 0
    @Volatile private var closing = false
    private var framesAnalysed = 0L
    private var analysisNsTotal = 0L
    private var analysisNsMax = 0L
    private var resultsEvicted = 0
    private var framesEvicted = 0

    /** When set, the next frame is copied in full (about 18.7 MB at 4080 × 3060). */
    val fullCopyRequested = AtomicBoolean(false)

    val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            onResult(result)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            AppLog.e(
                "Camera $cameraId capture failed: reason=${failure.reason} frame=${failure.frameNumber} " +
                    "imageCaptured=${failure.wasImageCaptured()} ${CameraOps.describeRequest(request)}",
            )
            synchronized(lock) {
                rawLog?.let { if (request.tag.toString().startsWith(it.tagPrefix)) it.failures++ }
            }
        }

        override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            AppLog.w("Camera $cameraId buffer lost: frame=$frameNumber ${CameraOps.describeRequest(request)}")
            synchronized(lock) {
                rawLog?.let { if (request.tag.toString().startsWith(it.tagPrefix)) it.bufferLost++ }
            }
        }
    }

    init {
        reader.setOnImageAvailableListener({ r -> onImage(r) }, imageThread.handler)
    }

    private fun onImage(r: ImageReader) {
        val image = try {
            r.acquireNextImage()
        } catch (e: IllegalStateException) {
            AppLog.w("Camera $cameraId acquireNextImage failed", e)
            null
        } ?: return
        if (closing) {
            image.close()
            return
        }
        val startNs = System.nanoTime()
        val info = try {
            val crop = cropSize.takeIf { it > 0 }?.let { Yuv.copyNv21(image, Yuv.centerRect(image.width, image.height, it)) }
            val full = if (fullCopyRequested.compareAndSet(true, false)) {
                Yuv.copyNv21(image, android.graphics.Rect(0, 0, image.width, image.height))
            } else {
                null
            }
            FrameInfo(image.timestamp, image.width, image.height, Yuv.meanLuma(image), crop, full)
        } catch (e: Exception) {
            // Closing the session can invalidate buffers mid-read; that is expected, not a failure.
            if (!closing) AppLog.e("Camera $cameraId frame analysis failed", e)
            null
        } finally {
            image.close()
        }
        val tookNs = System.nanoTime() - startNs
        synchronized(lock) {
            framesAnalysed++
            analysisNsTotal += tookNs
            if (tookNs > analysisNsMax) analysisNsMax = tookNs
        }
        if (info != null) onFrame(info)
    }

    private fun onFrame(frame: FrameInfo) = synchronized(lock) {
        rawLog?.frameTimestamps?.add(frame.timestampNs)
        val match = unmatchedResults.firstOrNull { it.get(CaptureResult.SENSOR_TIMESTAMP) == frame.timestampNs }
        if (match != null) {
            unmatchedResults.remove(match)
            emit(Sample(match, frame))
        } else {
            unmatchedFrames.addLast(frame)
            while (unmatchedFrames.size > MAX_UNMATCHED_FRAMES) {
                unmatchedFrames.removeFirst()
                framesEvicted++
            }
        }
    }

    private fun onResult(result: TotalCaptureResult) = synchronized(lock) {
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
        rawLog?.let {
            if (result.request.tag.toString().startsWith(it.tagPrefix) && ts != null) {
                it.results.add(RawLog.ResultStamp(ts, result.frameNumber))
            }
        }
        val match = unmatchedFrames.firstOrNull { it.timestampNs == ts }
        if (match != null) {
            unmatchedFrames.remove(match)
            emit(Sample(result, match))
        } else {
            unmatchedResults.addLast(result)
            while (unmatchedResults.size > MAX_UNMATCHED_RESULTS) {
                unmatchedResults.removeFirst()
                resultsEvicted++
            }
        }
    }

    private fun emit(sample: Sample) {
        val w = waiter ?: return
        if (!w.accept(sample)) return
        w.samples.add(sample)
        if (w.samples.size >= w.count) {
            waiter = null
            w.done.complete(w.samples.toList())
        }
    }

    fun request(template: Int, tag: String, configure: CaptureRequest.Builder.() -> Unit = {}): CaptureRequest =
        device.createCaptureRequest(template).apply {
            addTarget(reader.surface)
            setTag(tag)
            configure()
        }.build()

    fun repeating(request: CaptureRequest) {
        session.setRepeatingRequest(request, callback, cameraThread.handler)
    }

    fun capture(request: CaptureRequest) {
        session.capture(request, callback, cameraThread.handler)
    }

    fun burst(requests: List<CaptureRequest>) {
        session.captureBurst(requests, callback, cameraThread.handler)
    }

    /** Waits for [count] samples accepted by [accept]; returns whatever arrived if [timeoutMs] runs out. */
    suspend fun collect(count: Int, timeoutMs: Long, accept: (Sample) -> Boolean): List<Sample> {
        val w = Waiter(count, accept)
        synchronized(lock) { waiter = w }
        return withTimeoutOrNull(timeoutMs) { w.done.await() } ?: synchronized(lock) {
            if (waiter === w) waiter = null
            AppLog.w("Camera $cameraId: only ${w.samples.size} of $count samples within $timeoutMs ms")
            w.samples.toList()
        }
    }

    suspend fun collectTag(tag: String, count: Int, timeoutMs: Long): List<Sample> =
        collect(count, timeoutMs) { it.tag == tag }

    /** Records every result tagged with [tagPrefix] and every frame while [submit] runs and frames arrive. */
    suspend fun recordRaw(tagPrefix: String, expectedResults: Int, timeoutMs: Long, submit: () -> Unit): RawLog {
        val log = RawLog(tagPrefix)
        synchronized(lock) { rawLog = log }
        submit()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val done = synchronized(lock) { log.results.size + log.failures >= expectedResults }
            if (done) break
            delay(20)
        }
        delay(400) // let the last images arrive after their results
        synchronized(lock) { rawLog = null }
        return log
    }

    /** How long frame analysis took and how many frames or results could not be paired. */
    fun frameStats(): org.json.JSONObject = synchronized(lock) {
        org.json.JSONObject()
            .put("framesAnalysed", framesAnalysed)
            .put("meanAnalysisMs", if (framesAnalysed > 0) Math.round(analysisNsTotal / framesAnalysed / 10_000.0) / 100.0 else 0.0)
            .put("maxAnalysisMs", Math.round(analysisNsMax / 10_000.0) / 100.0)
            .put("unpairedResultsDropped", resultsEvicted)
            .put("unpairedFramesDropped", framesEvicted)
    }

    override fun close() {
        closing = true
        AppLog.i("Camera $cameraId frame stats: ${frameStats()}")
        try {
            session.close()
        } catch (e: Exception) {
            AppLog.w("Camera $cameraId session close failed", e)
        }
        device.close()
        reader.setOnImageAvailableListener(null, null)
        reader.close()
        AppLog.d("Camera $cameraId live session closed")
    }

    companion object {
        // Results are small, so keep many: frames can arrive several hundred ms after their result.
        private const val MAX_UNMATCHED_RESULTS = 90
        // Frames may carry crops (up to 1.5 MB each), so keep fewer.
        private const val MAX_UNMATCHED_FRAMES = 12

        suspend fun open(
            manager: CameraManager,
            cameraId: String,
            size: Size,
            cameraThread: CameraThread,
            imageThread: CameraThread,
            maxImages: Int = 6,
            openTimeoutMs: Long = 5_000,
        ): LiveSession {
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, maxImages)
            var device: CameraDevice? = null
            try {
                device = CameraOps.openCamera(manager, cameraId, cameraThread, openTimeoutMs)
                val session = CameraOps.createSession(device, listOf(OutputConfiguration(reader.surface)), cameraThread)
                AppLog.i("Camera $cameraId live session open at ${size.width}x${size.height} YUV")
                return LiveSession(cameraId, device, session, reader, size, cameraThread, imageThread)
            } catch (e: Throwable) {
                device?.close()
                reader.close()
                throw e
            }
        }
    }
}
