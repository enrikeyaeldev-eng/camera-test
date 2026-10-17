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

    // What is open; touched only on the camera thread while holding the mutex.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
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
            it.copy(lens = lens, evIndex = 0, aeLock = false, awbLock = false, focusPoint = null, focusState = FocusState.IDLE)
        }
        requestReconcile()
    }

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
        val canFocus = lens.autoAf && lens.maxAfRegions > 0
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
            val reader = jpegReader ?: throw CameraException("Camera is not open")
            _state.update { it.copy(capturing = true) }
            val lockAf = _state.value.focusPoint == null && lens.continuousAf
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

    // ------------------------------------------------------------------ open / close

    private fun requestReconcile(): Job = scope.launch { mutex.withLock { reconcileLocked() } }

    private fun refreshPreview() {
        scope.launch { mutex.withLock { if (session != null) safely("update preview") { startPreviewLocked() } } }
    }

    private suspend fun reconcileLocked() {
        val lens = _state.value.lens
        val s = surface
        val want = resumed && lens != null && s != null && s.isValid && surfaceSize == lens.previewSize
        if (want && device != null && openLens?.id == lens!!.id && openSurface === s) return
        closeLocked()
        if (!want) return
        val target = lens!!
        try {
            val dev = CameraOps.openCamera(manager, target.id, cameraThread)
            device = dev
            val reader = ImageReader.newInstance(target.jpegSize.width, target.jpegSize.height, ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ r -> onJpeg(r) }, imageThread.handler)
            jpegReader = reader
            session = CameraOps.createSession(
                dev, listOf(OutputConfiguration(s!!), OutputConfiguration(reader.surface)), cameraThread,
            )
            openLens = target
            openSurface = s
            _state.update { it.copy(open = true, error = null) }
            startPreviewLocked()
            AppLog.i("Camera ${target.id} preview started at ${target.previewSize}, JPEG ${target.jpegSize}")
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
        session = null
        device = null
        jpegReader = null
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
        val point = st.focusPoint
        if (point != null) {
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
        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            AppLog.e("Camera ${openLens?.id}: still capture failed reason=${failure.reason} ${CameraOps.describeRequest(request)}")
            jpeg.completeExceptionally(CameraException("Capture failed (reason ${failure.reason})"))
        }
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
