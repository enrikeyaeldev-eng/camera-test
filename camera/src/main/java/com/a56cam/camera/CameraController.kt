package com.a56cam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Receives each burst frame on the image thread; the image is closed right after this returns. */
fun interface FrameSink {
    fun onFrame(image: android.media.Image, index: Int)
}

/** Per-shot options chosen in Settings; null means "leave the camera's default". */
data class CaptureOptions(
    val jpegQuality: Int = 95,
    val noiseReduction: Int? = null,
    val edge: Int? = null,
)

/**
 * Basic still camera on Camera2: preview, tap to focus and meter, exposure compensation,
 * AE/AWB lock, lens switching and single JPEG capture.
 *
 * All camera work runs on one background thread and is serialised by [mutex]. The camera is
 * open only while the owner has called [resume] and a preview surface of the right size exists.
 */
class CameraController(context: Context) {

    enum class FocusState { IDLE, SCANNING, FOCUSED, FAILED }

    data class State(
        val lenses: List<LensInfo> = emptyList(),
        val lens: LensInfo? = null,
        val open: Boolean = false,
        val evIndex: Int = 0,
        val aeLock: Boolean = false,
        val awbLock: Boolean = false,
        /** Tap point in preview coordinates (0..1), or null for continuous auto focus. */
        val focusPoint: CameraMath.Point? = null,
        val focusState: FocusState = FocusState.IDLE,
        val capturing: Boolean = false,
        val error: String? = null,
        /** Session with a full-size YUV stream for burst modes, instead of the JPEG stream. */
        val burstSession: Boolean = false,
        /** Manual focus distance in diopters (1/m), or null for auto focus. */
        val manualFocus: Float? = null,
        val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    )

    private val manager = context.getSystemService(CameraManager::class.java)
    private val cameraThread = CameraThread("A56Cam-camera")
    private val imageThread = CameraThread("A56Cam-jpeg")
    private val dispatcher = cameraThread.handler.asCoroutineDispatcher("A56Cam-camera")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    // What the owner wants; written from the main thread.
    @Volatile private var resumed = false
    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceSize: Size? = null
    @Volatile private var lastAuto: Pair<Long, Int>? = null

    // What is open; touched only on the camera thread while holding the mutex.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var yuvReader: ImageReader? = null
    private var openBurst = false
    private var openLens: LensInfo? = null
    private var openSurface: Surface? = null

    @Volatile private var afWaiter: CompletableDeferred<Int>? = null
    @Volatile private var pendingJpeg: CompletableDeferred<ByteArray>? = null

    init {
        scope.launch {
            val lenses = try {
                LensCatalog.load(manager)
            } catch (e: Exception) {
                AppLog.e("Could not list cameras", e)
                emptyList()
            }
            _state.update {
                it.copy(
                    lenses = lenses,
                    lens = it.lens ?: lenses.firstOrNull(),
                    error = if (lenses.isEmpty()) "No usable camera found" else null,
                )
            }
            requestReconcile()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun resume() {
        resumed = true
        requestReconcile()
    }

    /** Closes the camera; waits briefly so it is released before the activity is paused. */
    fun pause() {
        resumed = false
        val job = requestReconcile()
        runBlocking { withTimeoutOrNull(1_500) { job.join() } }
    }

    fun onSurfaceChanged(newSurface: Surface, size: Size) {
        surface = newSurface
        surfaceSize = size
        requestReconcile()
    }

    /** The camera must stop drawing into the surface before this returns. */
    fun onSurfaceDestroyed() {
        surface = null
        surfaceSize = null
        val job = requestReconcile()
        runBlocking { withTimeoutOrNull(1_500) { job.join() } }
    }

    fun release() {
        resumed = false
        scope.launch { mutex.withLock { closeLocked() } }.invokeOnCompletion {
            scope.cancel()
            cameraThread.close()
            imageThread.close()
        }
    }

    // ------------------------------------------------------------------ user controls

    fun selectLens(id: String) {
        val lens = _state.value.lenses.firstOrNull { it.id == id } ?: return
        if (lens.id == _state.value.lens?.id) return
        AppLog.i("Switching to camera ${lens.id} (${lens.zoomLabel}, ${if (lens.front) "front" else "back"})")
        _state.update {
            it.copy(
                lens = lens, evIndex = 0, aeLock = false, awbLock = false, focusPoint = null, focusState = FocusState.IDLE,
                manualFocus = null, awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO,
            )
        }
        requestReconcile()
    }

    /** Switches between the JPEG session (Photo) and the YUV session (burst modes); reopens the camera. */
    fun setBurstSession(enabled: Boolean) {
        if (_state.value.burstSession == enabled) return
        _state.update { it.copy(burstSession = enabled) }
        requestReconcile()
    }

    /** Manual focus in diopters (0 = infinity), or null to return to auto focus. */
    fun setManualFocus(diopters: Float?) {
        val lens = _state.value.lens ?: return
        if (diopters != null && !lens.manualFocusAvailable) return
        _state.update { it.copy(manualFocus = diopters?.coerceIn(0f, lens.minFocusDistance), focusPoint = null, focusState = FocusState.IDLE) }
        refreshPreview()
    }

    fun setAwbMode(mode: Int) {
        val lens = _state.value.lens ?: return
        if (mode !in lens.awbModes) return
        _state.update { it.copy(awbMode = mode) }
        refreshPreview()
    }

    /** The latest auto exposure (time in ns, ISO) from the preview, the starting point for burst plans. */
    fun autoExposure(): Pair<Long, Int>? = lastAuto

    fun setEv(index: Int) {
        val lens = _state.value.lens ?: return
        _state.update { it.copy(evIndex = index.coerceIn(lens.evMin, lens.evMax)) }
        refreshPreview()
    }

    fun setAeLock(locked: Boolean) {
        if (_state.value.lens?.aeLockAvailable != true) return
        _state.update { it.copy(aeLock = locked) }
        refreshPreview()
    }

    fun setAwbLock(locked: Boolean) {
        if (_state.value.lens?.awbLockAvailable != true) return
        _state.update { it.copy(awbLock = locked) }
        refreshPreview()
    }

    /** Focuses and meters at a tap on the preview (0..1 in both axes). */
    fun focusAt(point: CameraMath.Point) {
        val lens = _state.value.lens ?: return
        // With manual focus a tap only meters.
        val canFocus = lens.autoAf && lens.maxAfRegions > 0 && _state.value.manualFocus == null
        if (!canFocus && lens.maxAeRegions == 0) {
            AppLog.i("Camera ${lens.id}: no AF or AE regions, tap ignored")
            return
        }
        _state.update {
            it.copy(focusPoint = point, focusState = if (canFocus) FocusState.SCANNING else FocusState.FOCUSED)
        }
        scope.launch {
            mutex.withLock {
                val s = session ?: return@withLock
                safely("tap focus") {
                    startPreviewLocked()
                    if (canFocus) {
                        s.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL), previewCallback, cameraThread.handler)
                        s.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_START), previewCallback, cameraThread.handler)
                    }
                }
            }
        }
    }

    /** Back to continuous auto focus and whole-frame metering. */
    fun resetFocus() {
        if (_state.value.focusPoint == null) return
        _state.update { it.copy(focusPoint = null, focusState = FocusState.IDLE) }
        scope.launch {
            mutex.withLock {
                val s = session ?: return@withLock
                safely("reset focus") {
                    s.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL), previewCallback, cameraThread.handler)
                    startPreviewLocked()
                }
            }
        }
    }

    /**
     * Takes one JPEG. In continuous AF the focus is locked for the shot and released afterwards.
     * [deviceOrientation] is the raw angle from an OrientationEventListener.
     */
    suspend fun takePicture(deviceOrientation: Int, options: CaptureOptions): ByteArray = withContext(dispatcher) {
        mutex.withLock {
            val s = session ?: throw CameraException("Camera is not open")
            val dev = device ?: throw CameraException("Camera is not open")
            val lens = openLens ?: throw CameraException("Camera is not open")
            val reader = jpegReader ?: throw CameraException("Photo capture needs Photo mode")
            _state.update { it.copy(capturing = true) }
            val lockAf = _state.value.focusPoint == null && _state.value.manualFocus == null && lens.continuousAf
            try {
                if (lockAf) {
                    val waiter = CompletableDeferred<Int>()
                    afWaiter = waiter
                    s.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_START), previewCallback, cameraThread.handler)
                    if (withTimeoutOrNull(1_000) { waiter.await() } == null) {
                        AppLog.w("Camera ${lens.id}: AF did not lock within 1 s, capturing anyway")
                    }
                    afWaiter = null
                }
                val jpeg = CompletableDeferred<ByteArray>()
                pendingJpeg = jpeg
                val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    applyCommon(this, lens)
                    set(CaptureRequest.JPEG_ORIENTATION, CameraMath.jpegOrientation(lens.sensorOrientation, deviceOrientation, lens.front))
                    set(CaptureRequest.JPEG_QUALITY, options.jpegQuality.coerceIn(1, 100).toByte())
                    options.noiseReduction?.let { mode ->
                        if (mode in lens.noiseReductionModes) {
                            set(CaptureRequest.NOISE_REDUCTION_MODE, mode)
                        } else {
                            AppLog.w("Camera ${lens.id}: noise reduction mode $mode not available, using default")
                        }
                    }
                    options.edge?.let { mode ->
                        if (mode in lens.edgeModes) {
                            set(CaptureRequest.EDGE_MODE, mode)
                        } else {
                            AppLog.w("Camera ${lens.id}: edge mode $mode not available, using default")
                        }
                    }
                    setTag("still")
                }.build()
                s.capture(request, stillCallback(jpeg), cameraThread.handler)
                val bytes = withTimeoutOrNull(8_000) { jpeg.await() }
                    ?: throw CameraException("Camera ${lens.id}: no JPEG within 8 s (${CameraOps.describeRequest(request)})")
                AppLog.i("Camera ${lens.id}: captured ${bytes.size} bytes, ${CameraOps.describeRequest(request)}")
                bytes
            } finally {
                afWaiter = null
                pendingJpeg = null
                if (lockAf && session === s) {
                    safely("release AF lock") {
                        s.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL), previewCallback, cameraThread.handler)
                    }
                }
                _state.update { it.copy(capturing = false) }
            }
        }
    }

    /**
     * Captures one full-size YUV frame per entry of [frames] with manual exposure, white balance locked and
     * the camera's own noise reduction and sharpening off. Frames go to [sink] as they arrive.
     * Returns how many frames were delivered.
     */
    suspend fun takeBurst(frames: List<BurstPlanner.Frame>, sink: FrameSink): Int = withContext(dispatcher) {
        mutex.withLock {
            val s = session ?: throw CameraException("Camera is not open")
            val dev = device ?: throw CameraException("Camera is not open")
            val lens = openLens ?: throw CameraException("Camera is not open")
            val reader = yuvReader ?: throw CameraException("Burst capture needs a burst mode")
            val preview = openSurface ?: throw CameraException("No preview surface")
            _state.update { it.copy(capturing = true) }
            val st = _state.value
            val lockAf = st.focusPoint == null && st.manualFocus == null && lens.continuousAf
            val received = java.util.concurrent.atomic.AtomicInteger(0)
            val done = CompletableDeferred<Unit>()
            reader.setOnImageAvailableListener({ r ->
                val image = try {
                    r.acquireNextImage()
                } catch (e: IllegalStateException) {
                    AppLog.w("Burst acquire failed", e)
                    null
                } ?: return@setOnImageAvailableListener
                try {
                    val index = received.getAndIncrement()
                    if (index < frames.size) sink.onFrame(image, index)
                } catch (e: Exception) {
                    AppLog.e("Burst frame copy failed", e)
                } finally {
                    image.close()
                }
                if (received.get() >= frames.size) done.complete(Unit)
            }, imageThread.handler)
            try {
                if (lockAf) {
                    val waiter = CompletableDeferred<Int>()
                    afWaiter = waiter
                    s.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_START), previewCallback, cameraThread.handler)
                    if (withTimeoutOrNull(1_000) { waiter.await() } == null) AppLog.w("Camera ${lens.id}: AF did not lock before burst")
                    afWaiter = null
                }
                val requests = frames.mapIndexed { i, f ->
                    dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview)
                        addTarget(reader.surface)
                        applyCommon(this, lens)
                        if (lens.aeOffAvailable) {
                            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, f.exposureNs)
                            set(CaptureRequest.SENSOR_SENSITIVITY, f.iso)
                            set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(f.exposureNs, lens.yuvMinFrameNs))
                        } else if (lens.aeLockAvailable) {
                            set(CaptureRequest.CONTROL_AE_LOCK, true)
                        }
                        if (lens.awbLockAvailable) set(CaptureRequest.CONTROL_AWB_LOCK, true)
                        if (CameraMetadata.NOISE_REDUCTION_MODE_OFF in lens.noiseReductionModes) {
                            set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                        }
                        if (CameraMetadata.EDGE_MODE_OFF in lens.edgeModes) set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                        setTag("burst-$i")
                    }.build()
                }
                s.captureBurst(requests, burstCallback, cameraThread.handler)
                val timeoutMs = frames.sumOf { maxOf(it.exposureNs, lens.yuvMinFrameNs) } / 1_000_000 + 4_000
                if (withTimeoutOrNull(timeoutMs) { done.await() } == null) {
                    AppLog.w("Camera ${lens.id}: burst delivered ${received.get()} of ${frames.size} frames in $timeoutMs ms")
                }
                AppLog.i("Camera ${lens.id}: burst of ${frames.size} frames done, first ${frames.firstOrNull()}")
                minOf(received.get(), frames.size)
            } finally {
                reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, imageThread.handler)
                if (lockAf && session === s) {
                    safely("release AF lock") {
                        s.capture(previewRequest(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL), previewCallback, cameraThread.handler)
                    }
                }
                _state.update { it.copy(capturing = false) }
            }
        }
    }

    private val burstCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            AppLog.e("Camera ${openLens?.id}: burst frame failed reason=${failure.reason} ${CameraOps.describeRequest(request)}")
        }
    }

    // ------------------------------------------------------------------ open / close

    private fun requestReconcile(): Job = scope.launch { mutex.withLock { reconcileLocked() } }

    private fun refreshPreview() {
        scope.launch { mutex.withLock { if (session != null) safely("update preview") { startPreviewLocked() } } }
    }

    private suspend fun reconcileLocked() {
        val lens = _state.value.lens
        val s = surface
        val want = resumed && lens != null && s != null && s.isValid && surfaceSize == lens.previewSize
        val burst = _state.value.burstSession && lens?.yuvSize != null
        if (want && device != null && openLens?.id == lens!!.id && openSurface === s && openBurst == burst) return
        closeLocked()
        if (!want) return
        val target = lens!!
        try {
            val dev = CameraOps.openCamera(manager, target.id, cameraThread)
            device = dev
            val reader = if (burst) {
                val yuv = target.yuvSize!!
                ImageReader.newInstance(yuv.width, yuv.height, ImageFormat.YUV_420_888, 3).also {
                    it.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, imageThread.handler)
                    yuvReader = it
                }
            } else {
                ImageReader.newInstance(target.jpegSize.width, target.jpegSize.height, ImageFormat.JPEG, 2).also {
                    it.setOnImageAvailableListener({ r -> onJpeg(r) }, imageThread.handler)
                    jpegReader = it
                }
            }
            session = CameraOps.createSession(
                dev, listOf(OutputConfiguration(s!!), OutputConfiguration(reader.surface)), cameraThread,
            )
            openLens = target
            openSurface = s
            openBurst = burst
            _state.update { it.copy(open = true, error = null) }
            startPreviewLocked()
            AppLog.i(
                "Camera ${target.id} preview started at ${target.previewSize}, " +
                    if (burst) "YUV ${target.yuvSize} (burst)" else "JPEG ${target.jpegSize}",
            )
        } catch (e: TimeoutCancellationException) {
            AppLog.e("Camera ${target.id}: open timed out", e)
            closeLocked()
            _state.update { it.copy(error = "The camera did not respond. Leave the app and come back to retry.") }
        } catch (e: Exception) {
            AppLog.e("Camera ${target.id}: could not start preview", e)
            closeLocked()
            _state.update { it.copy(error = "Camera unavailable (${e.message}). It may be in use by another app.") }
        }
    }

    private fun closeLocked() {
        val wasOpen = device != null
        try {
            session?.close()
        } catch (e: Exception) {
            AppLog.w("Camera ${openLens?.id}: session close failed", e)
        }
        device?.close()
        jpegReader?.setOnImageAvailableListener(null, null)
        jpegReader?.close()
        yuvReader?.setOnImageAvailableListener(null, null)
        yuvReader?.close()
        session = null
        device = null
        jpegReader = null
        yuvReader = null
        openBurst = false
        openSurface = null
        pendingJpeg?.completeExceptionally(CameraException("Camera closed"))
        if (wasOpen) AppLog.i("Camera ${openLens?.id} closed")
        openLens = null
        _state.update { it.copy(open = false, capturing = false) }
    }

    // ------------------------------------------------------------------ requests

    private fun startPreviewLocked() {
        val s = session ?: return
        s.setRepeatingRequest(previewRequest(null), previewCallback, cameraThread.handler)
    }

    private fun previewRequest(afTrigger: Int?): CaptureRequest {
        val dev = device ?: throw CameraException("Camera is not open")
        val lens = openLens ?: throw CameraException("Camera is not open")
        return dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(openSurface ?: throw CameraException("No preview surface"))
            applyCommon(this, lens)
            afTrigger?.let { set(CaptureRequest.CONTROL_AF_TRIGGER, it) }
            setTag(if (afTrigger == null) "preview" else "af-trigger-$afTrigger")
        }.build()
    }

    /** Settings shared by preview and still requests, applied only where the camera supports them. */
    private fun applyCommon(b: CaptureRequest.Builder, lens: LensInfo) {
        val st = _state.value
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        if (lens.awbModes.contains(st.awbMode)) b.set(CaptureRequest.CONTROL_AWB_MODE, st.awbMode)
        val manual = st.manualFocus
        val point = st.focusPoint
        if (manual != null && lens.manualFocusAvailable) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, manual)
            if (point != null && lens.maxAeRegions > 0) {
                val r = CameraMath.meteringRect(
                    CameraMath.viewToSensor(point, lens.sensorOrientation, lens.front), lens.activeArrayWidth, lens.activeArrayHeight,
                )
                b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(r[0], r[1], r[2], r[3], MeteringRectangle.METERING_WEIGHT_MAX)))
            }
        } else if (point != null) {
            val sensor = CameraMath.viewToSensor(point, lens.sensorOrientation, lens.front)
            val r = CameraMath.meteringRect(sensor, lens.activeArrayWidth, lens.activeArrayHeight)
            val region = arrayOf(MeteringRectangle(r[0], r[1], r[2], r[3], MeteringRectangle.METERING_WEIGHT_MAX))
            if (lens.autoAf && lens.maxAfRegions > 0) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, region)
            } else if (lens.continuousAf) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            if (lens.maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, region)
        } else if (lens.continuousAf) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (lens.evStep > 0f) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, st.evIndex.coerceIn(lens.evMin, lens.evMax))
        if (lens.aeLockAvailable) b.set(CaptureRequest.CONTROL_AE_LOCK, st.aeLock)
        if (lens.awbLockAvailable) b.set(CaptureRequest.CONTROL_AWB_LOCK, st.awbLock)
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (result.get(CaptureResult.CONTROL_AE_MODE) != CameraMetadata.CONTROL_AE_MODE_OFF) {
                val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                if (exposure != null && iso != null) lastAuto = exposure to iso
            }
            val af = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                afWaiter?.complete(af)
            }
            if (_state.value.focusPoint != null) {
                val next = when (af) {
                    CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> FocusState.SCANNING
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> FocusState.FOCUSED
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> FocusState.FAILED
                    else -> null
                }
                if (next != null && next != _state.value.focusState) _state.update { it.copy(focusState = next) }
            }
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            AppLog.e("Camera ${openLens?.id}: preview capture failed reason=${failure.reason} ${CameraOps.describeRequest(request)}")
        }
    }

    private fun stillCallback(jpeg: CompletableDeferred<ByteArray>) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            AppLog.i("Camera ${openLens?.id}: still result ${describeColour(result)}")
        }

        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            AppLog.e("Camera ${openLens?.id}: still capture failed reason=${failure.reason} ${CameraOps.describeRequest(request)}")
            jpeg.completeExceptionally(CameraException("Capture failed (reason ${failure.reason})"))
        }
    }

    /** Exposure, white balance and colour settings the camera actually used, for diagnosing colour casts. */
    private fun describeColour(r: CaptureResult): String = buildString {
        append("exposureNs=").append(r.get(CaptureResult.SENSOR_EXPOSURE_TIME))
        append(" iso=").append(r.get(CaptureResult.SENSOR_SENSITIVITY))
        append(" aeMode=").append(r.get(CaptureResult.CONTROL_AE_MODE))
        append(" aeState=").append(r.get(CaptureResult.CONTROL_AE_STATE))
        append(" awbMode=").append(r.get(CaptureResult.CONTROL_AWB_MODE))
        append(" awbState=").append(r.get(CaptureResult.CONTROL_AWB_STATE))
        append(" awbLock=").append(r.get(CaptureResult.CONTROL_AWB_LOCK))
        append(" ccMode=").append(r.get(CaptureResult.COLOR_CORRECTION_MODE))
        append(" ccGains=").append(r.get(CaptureResult.COLOR_CORRECTION_GAINS))
        append(" ccTransform=").append(r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM))
        append(" nr=").append(r.get(CaptureResult.NOISE_REDUCTION_MODE))
        append(" edge=").append(r.get(CaptureResult.EDGE_MODE))
        append(" tonemap=").append(r.get(CaptureResult.TONEMAP_MODE))
        append(" sceneMode=").append(r.get(CaptureResult.CONTROL_SCENE_MODE))
        append(" captureIntent=").append(r.get(CaptureResult.CONTROL_CAPTURE_INTENT))
    }

    private fun onJpeg(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: IllegalStateException) {
            AppLog.w("JPEG acquire failed", e)
            null
        } ?: return
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            pendingJpeg?.complete(bytes) ?: AppLog.w("Unexpected JPEG dropped (${bytes.size} bytes)")
        } catch (e: Exception) {
            AppLog.e("JPEG copy failed", e)
            pendingJpeg?.completeExceptionally(e)
        } finally {
            image.close()
        }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            AppLog.e("Camera ${openLens?.id}: $what failed", e)
        }
    }
}
