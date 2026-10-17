package com.a56cam.diagnostics

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import com.a56cam.camera.AppLog
import com.a56cam.camera.CameraException
import com.a56cam.camera.CameraNames
import com.a56cam.camera.CameraOps
import com.a56cam.camera.CameraThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One still capture in every Camera Extension the device offers, plus one Ultra HDR (JPEG_R) capture. */
internal class ExtensionTests(
    private val manager: CameraManager,
    private val cameraId: String,
    private val chars: CameraCharacteristics,
    private val outDir: File,
    /** Prepended to every saved file name, e.g. "cam2_", so cameras do not overwrite each other. */
    private val prefix: String,
    private val cameraThread: CameraThread,
    private val imageThread: CameraThread,
    private val errors: ErrorLog,
) {

    suspend fun extensions(): JSONObject {
        val out = JSONObject()
        val ext = errors.step("extension characteristics") { manager.getCameraExtensionCharacteristics(cameraId) }
            ?: return out.put("error", "could not read extension characteristics")
        val supported = ext.supportedExtensions
        out.put("supported", JSONArray(supported.map { CameraNames.name(CameraNames.extension, it) }))
        if (supported.isEmpty()) {
            AppLog.w("Camera $cameraId offers no Camera Extensions to third-party apps")
        }
        val captures = JSONArray()
        for (e in supported) {
            val name = CameraNames.name(CameraNames.extension, e)
            val o = JSONObject().put("extension", name)
            errors.step("extension capture $name") {
                val jpeg = ext.getExtensionSupportedSizes(e, ImageFormat.JPEG)
                val yuv = ext.getExtensionSupportedSizes(e, ImageFormat.YUV_420_888)
                val (format, sizes) = if (jpeg.isNotEmpty()) ImageFormat.JPEG to jpeg else ImageFormat.YUV_420_888 to yuv
                val captureSize = sizes.maxByOrNull { it.width.toLong() * it.height }
                val previewSize = pickPreviewSize(ext.getExtensionSupportedSizes(e, SurfaceTexture::class.java))
                if (captureSize == null || previewSize == null) {
                    o.put("error", "no capture or preview sizes")
                } else {
                    o.put("captureFormat", CameraNames.formatName(format))
                        .put("captureSize", "${captureSize.width}x${captureSize.height}")
                        .put("previewSize", "${previewSize.width}x${previewSize.height}")
                    captureOnce(e, name, format, captureSize, previewSize, o)
                }
            }
            captures.put(o)
        }
        out.put("captures", captures)
        return out
    }

    private fun pickPreviewSize(sizes: List<Size>): Size? =
        sizes.filter { it.width <= 1920 && it.height <= 1440 }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }

    private suspend fun captureOnce(extension: Int, name: String, format: Int, size: Size, previewSize: Size, o: JSONObject) {
        val device = CameraOps.openCamera(manager, cameraId, cameraThread)
        val stillReader = ImageReader.newInstance(size.width, size.height, format, 2)
        // Preview frames are consumed and dropped at once; extensions require a preview stream.
        val previewReader = ImageReader.newInstance(
            previewSize.width, previewSize.height, ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
        previewReader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, imageThread.handler)
        var texture: SurfaceTexture? = null
        var textureSurface: Surface? = null
        var session: CameraExtensionSession? = null
        try {
            session = try {
                createExtensionSession(device, extension, previewReader.surface, stillReader.surface).also {
                    o.put("previewSurface", "ImageReader(PRIVATE)")
                }
            } catch (e: CameraException) {
                AppLog.w("Camera $cameraId extension $name: PRIVATE ImageReader preview rejected, retrying with SurfaceTexture", e)
                texture = SurfaceTexture(false).apply { setDefaultBufferSize(previewSize.width, previewSize.height) }
                textureSurface = Surface(texture)
                createExtensionSession(device, extension, textureSurface, stillReader.surface).also {
                    o.put("previewSurface", "SurfaceTexture")
                }
            }
            val failures = mutableListOf<String>()
            val callback = object : CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureFailed(s: CameraExtensionSession, request: CaptureRequest) {
                    AppLog.e("Camera $cameraId extension $name capture failed: ${CameraOps.describeRequest(request)}")
                    synchronized(failures) { failures.add("captureFailed tag=${request.tag}") }
                }
            }
            val previewTarget = textureSurface ?: previewReader.surface
            session.setRepeatingRequest(
                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewTarget)
                    setTag("ext-preview")
                }.build(),
                cameraThread.executor, callback,
            )
            delay(1_500) // let Samsung's AE/AWB settle

            val image = CompletableDeferred<ByteArray>()
            stillReader.setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    val bytes = if (img.format == ImageFormat.JPEG) {
                        val buf = img.planes[0].buffer
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    } else {
                        val nv21 = Yuv.copyNv21(img, android.graphics.Rect(0, 0, img.width, img.height))
                        val tmp = File(outDir, prefix + "tmp_ext.jpg")
                        Yuv.saveJpeg(nv21, tmp)
                        tmp.readBytes().also { tmp.delete() }
                    }
                    image.complete(bytes)
                } catch (e: Exception) {
                    image.completeExceptionally(e)
                } finally {
                    img.close()
                }
            }, imageThread.handler)

            val start = SystemClock.elapsedRealtime()
            session.capture(
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(stillReader.surface)
                    setTag("ext-still-$name")
                }.build(),
                cameraThread.executor, callback,
            )
            val bytes = withTimeoutOrNull(30_000) { image.await() }
            o.put("failures", JSONArray(synchronized(failures) { failures.toList() }))
            if (bytes == null) {
                AppLog.e("Camera $cameraId extension $name: no image within 30 s")
                o.put("success", false).put("error", "no image within 30 s")
            } else {
                val file = File(outDir, prefix + "extension_$name.jpg")
                file.writeBytes(bytes)
                o.put("success", true)
                    .put("latencyMs", SystemClock.elapsedRealtime() - start)
                    .put("file", file.name)
                    .put("bytes", bytes.size)
                AppLog.i("Camera $cameraId extension $name captured in ${SystemClock.elapsedRealtime() - start} ms")
            }
        } finally {
            try {
                session?.close()
            } catch (e: Exception) {
                AppLog.w("Camera $cameraId extension session close failed", e)
            }
            device.close()
            stillReader.close()
            previewReader.close()
            textureSurface?.release()
            texture?.release()
        }
    }

    private suspend fun createExtensionSession(
        device: CameraDevice,
        extension: Int,
        preview: Surface,
        still: Surface,
    ): CameraExtensionSession = withTimeout(8_000) {
        suspendCancellableCoroutine { cont ->
            val config = ExtensionSessionConfiguration(
                extension,
                listOf(OutputConfiguration(preview), OutputConfiguration(still)),
                cameraThread.executor,
                object : CameraExtensionSession.StateCallback() {
                    override fun onConfigured(session: CameraExtensionSession) {
                        if (cont.isActive) cont.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraExtensionSession) {
                        AppLog.e("Camera $cameraId extension ${CameraNames.name(CameraNames.extension, extension)} configure failed")
                        if (cont.isActive) cont.resumeWithException(CameraException("extension session configure failed"))
                    }
                },
            )
            try {
                device.createExtensionSession(config)
            } catch (e: Exception) {
                AppLog.e("Camera $cameraId createExtensionSession threw", e)
                if (cont.isActive) cont.resumeWithException(CameraException("createExtensionSession threw: $e", e))
            }
        }
    }

    /** One Ultra HDR (JPEG_R) still, if the stream configuration map lists the format. */
    suspend fun ultraHdr(): JSONObject {
        val out = JSONObject()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supported = map?.outputFormats?.contains(JPEG_R) == true
        out.put("jpegRListed", supported)
        if (!supported) {
            AppLog.i("Camera $cameraId: JPEG_R (Ultra HDR) not listed")
            return out
        }
        errors.step("ultra HDR capture") {
            val size = map!!.getOutputSizes(JPEG_R).maxByOrNull { it.width.toLong() * it.height }!!
            out.put("size", "${size.width}x${size.height}")
            val reader = ImageReader.newInstance(size.width, size.height, JPEG_R, 2)
            val device = CameraOps.openCamera(manager, cameraId, cameraThread)
            try {
                val session = CameraOps.createSession(device, listOf(OutputConfiguration(reader.surface)), cameraThread)
                val image = CompletableDeferred<ByteArray>()
                reader.setOnImageAvailableListener({ r ->
                    val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                    try {
                        val buf = img.planes[0].buffer
                        image.complete(ByteArray(buf.remaining()).also { buf.get(it) })
                    } finally {
                        img.close()
                    }
                }, imageThread.handler)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    setTag("ultrahdr")
                }.build()
                session.capture(request, object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        s: android.hardware.camera2.CameraCaptureSession,
                        r: CaptureRequest,
                        f: android.hardware.camera2.CaptureFailure,
                    ) {
                        AppLog.e("Camera $cameraId Ultra HDR capture failed reason=${f.reason} ${CameraOps.describeRequest(r)}")
                    }
                }, cameraThread.handler)
                val bytes = withTimeoutOrNull(10_000) { image.await() }
                session.close()
                if (bytes == null) {
                    out.put("success", false).put("error", "no image within 10 s")
                } else {
                    File(outDir, prefix + "ultrahdr.jpg").writeBytes(bytes)
                    val text = String(bytes, Charsets.ISO_8859_1)
                    out.put("success", true)
                        .put("bytes", bytes.size)
                        .put("hasGainMapXmp", text.contains("hdrgm:"))
                        .put("hasMpf", text.contains("MPF\u0000"))
                }
            } finally {
                device.close()
                reader.close()
            }
        }
        return out
    }

    companion object {
        const val JPEG_R = 0x1005
    }
}
