package com.a56cam.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.a56cam.camera.AppLog
import com.a56cam.camera.BurstPlanner
import com.a56cam.camera.CameraController
import com.a56cam.camera.CameraMath
import com.a56cam.processing.StackProcessor
import com.a56cam.processing.StackStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** What the shutter does. Burst modes capture full-size YUV frames and merge them in native code. */
enum class CaptureMode(val label: String, val burst: BurstPlanner.Kind?) {
    PHOTO("Photo", null),
    STACK("Stack", BurstPlanner.Kind.STACK),
    NIGHT("Night", BurstPlanner.Kind.NIGHT),
    HDR("HDR", BurstPlanner.Kind.HDR),
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    data class PhotoUi(
        val lastUri: Uri? = null,
        val thumbnail: Bitmap? = null,
        val busy: Boolean = false,
        /** Shown over the preview while a burst is captured or merged. */
        val status: String? = null,
        val countdown: Int = 0,
        val message: String? = null,
        /** Increments on every successful shot; drives the shutter flash. */
        val shots: Int = 0,
        /** Bursts captured and still being merged in the background. */
        val processing: Int = 0,
        /** Outcome of the GPU merge self-test, or null while it runs. */
        val gpuStatus: String? = null,
    )

    val controller = CameraController(app)
    val settings = AppSettings(app)

    private val _photo = MutableStateFlow(PhotoUi())
    val photo: StateFlow<PhotoUi> = _photo

    private val _mode = MutableStateFlow(
        CaptureMode.entries.firstOrNull { it.name == settings.values.value.mode } ?: CaptureMode.PHOTO,
    )
    val mode: StateFlow<CaptureMode> = _mode

    /**
     * Merging runs here, one burst at a time, so the shutter is free again as soon as a burst is captured.
     * Not tied to viewModelScope: a burst being merged is still saved if the screen is closed meanwhile.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val processingDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val processingScope = CoroutineScope(SupervisorJob() + processingDispatcher)

    init {
        controller.setBurstSession(_mode.value.burst != null)
        // Sets up the GPU merge (with its CPU comparison) before the first burst needs it.
        processingScope.launch {
            val status = try {
                StackProcessor.gpuStatus()
            } catch (e: Throwable) {
                "GPU merge unavailable: ${e.message}"
            }
            AppLog.i(status)
            _photo.update { it.copy(gpuStatus = status) }
        }
        viewModelScope.launch {
            settings.values.collect { controller.setPreShutter(it.preShutter) }
        }
        // Reopen the lens used last time, once the lens list is known.
        viewModelScope.launch {
            val lenses = controller.state.first { it.lenses.isNotEmpty() }.lenses
            settings.values.value.lastLensId?.takeIf { id -> lenses.any { it.id == id } }?.let { controller.selectLens(it) }
        }
    }

    fun selectLens(id: String) {
        controller.selectLens(id)
        settings.update { it.copy(lastLensId = id) }
    }

    /** Switches between the main back and main front camera. */
    fun switchFacing() {
        val st = controller.state.value
        val current = st.lens ?: return
        st.lenses.firstOrNull { it.front != current.front }?.let { selectLens(it.id) }
    }

    fun setMode(mode: CaptureMode) {
        if (_photo.value.busy) return
        _mode.value = mode
        controller.setBurstSession(mode.burst != null)
        settings.update { it.copy(mode = mode.name) }
    }

    fun cycleTimer() {
        settings.update { it.copy(timerSeconds = if (it.timerSeconds == 0) 3 else if (it.timerSeconds == 3) 10 else 0) }
    }

    /** Shutter button, volume keys and Bluetooth remotes all end up here. */
    fun shutter(deviceOrientation: () -> Int) {
        if (_photo.value.busy || controller.state.value.capturing || !controller.state.value.open) return
        if (_mode.value.burst != null && _photo.value.processing >= MAX_QUEUED) {
            _photo.update { it.copy(message = "Still merging the last photos, one moment…") }
            return
        }
        _photo.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                for (s in settings.values.value.timerSeconds downTo 1) {
                    _photo.update { it.copy(countdown = s) }
                    delay(1_000)
                }
                _photo.update { it.copy(countdown = 0) }
                val kind = _mode.value.burst
                if (kind == null) shootPhoto(deviceOrientation()) else shootBurst(kind, deviceOrientation())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Capture failed in ${_mode.value} mode", e)
                _photo.update { it.copy(message = "Photo not saved: ${e.message}") }
            } finally {
                _photo.update { it.copy(busy = false, status = null, countdown = 0) }
            }
        }
    }

    private suspend fun shootPhoto(deviceOrientation: Int) {
        val bytes = controller.takePicture(deviceOrientation, settings.values.value.captureOptions())
        val app = getApplication<Application>()
        val uri = withContext(Dispatchers.IO) { PhotoStore.saveJpeg(app, bytes) }
        val thumb = withContext(Dispatchers.Default) { PhotoStore.thumbnail(bytes) }
        val opts = settings.values.value
        AppLog.i(
            "Saved photo $uri (${bytes.size} bytes) settings nr=${opts.noiseReduction} edge=${opts.edge} " +
                "q=${opts.jpegQuality} mean ${thumb?.let { PhotoStore.meanRgb(it) }}",
        )
        _photo.update { it.copy(lastUri = uri, thumbnail = thumb, shots = it.shots + 1) }
    }

    private suspend fun shootBurst(kind: BurstPlanner.Kind, deviceOrientation: Int) {
        val lens = controller.state.value.lens ?: throw IllegalStateException("No camera")
        val yuv = lens.yuvSize ?: throw IllegalStateException("This camera has no full-size YUV output")
        val (autoExposure, autoIso) = controller.autoExposure() ?: throw IllegalStateException("Exposure not measured yet")
        val v = settings.values.value
        val count = when (kind) {
            BurstPlanner.Kind.STACK -> v.stackFrames
            BurstPlanner.Kind.NIGHT -> v.nightFrames
            BurstPlanner.Kind.HDR -> HDR_FRAMES
        }
        val plan = BurstPlanner.plan(kind, autoExposure, autoIso, lens.burstLimits, count)
        val base = PhotoStore.baseName() + "_" + kind.name
        val rotation = CameraMath.jpegOrientation(lens.sensorOrientation, deviceOrientation, lens.front)
        AppLog.i("Burst $kind on camera ${lens.id}: auto ${autoExposure}ns ISO $autoIso -> ${plan.frames} gain ${plan.gain}")
        _photo.update { it.copy(status = "Hold still…") }

        val stack = StackProcessor(yuv.width, yuv.height, plan.frames.size)
        var handedOff = false
        try {
            val delivered = controller.takeBurst(plan.frames, if (v.preShutter) PRE_FRAMES else 0) { image, i, exposure ->
                if (!stack.addFrame(image, exposure)) AppLog.w("Burst frame $i was not stored")
            }
            if (stack.count == 0) throw IllegalStateException("No frames were captured")
            AppLog.i("Burst captured ${stack.count} of ${plan.frames.size} frames (delivered $delivered)")
            _photo.update { it.copy(processing = it.processing + 1, shots = it.shots + 1) }
            handedOff = true
            processingScope.launch {
                try {
                    mergeAndSave(stack, kind, plan, base, rotation, v)
                } catch (e: Throwable) {
                    AppLog.e("Merging $base failed", e)
                    _photo.update { it.copy(message = "Photo not saved: ${e.message}") }
                } finally {
                    stack.close()
                    _photo.update { it.copy(processing = it.processing - 1) }
                }
            }
        } finally {
            if (!handedOff) stack.close()
        }
    }

    /** Runs on the processing thread: align, merge, render and save one captured burst. */
    private fun mergeAndSave(
        stack: StackProcessor, kind: BurstPlanner.Kind, plan: BurstPlanner.Plan, base: String, rotation: Int, v: AppSettings.Values,
    ) {
        val app = getApplication<Application>()
        val debugDir = "${Environment.DIRECTORY_DCIM}/A56Cam/debug"
        if (v.debug) saveSourceFrames(stack, base, debugDir)
        val started = System.currentTimeMillis()
        // All modes merge same-exposure frames; HDR differs only in its deeper underexposure and gain.
        val stats = stack.process(plan.minSharpness, hdr = false, useGpu = v.gpuMerge)
        val bitmap = stack.renderBitmap(rotation, plan.gain, SHARPEN, v.look.code)
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, v.jpegQuality, it) }.toByteArray()
        val uri = PhotoStore.saveJpeg(app, jpeg, "$base.jpg")
        val thumb = Bitmap.createScaledBitmap(bitmap, maxOf(1, bitmap.width / 16), maxOf(1, bitmap.height / 16), true)
        bitmap.recycle()
        // The JPEG is in the gallery now; the large files follow.
        val note = if (stats.rejected > 0) "Left out ${stats.rejected} blurred frame(s)." else null
        _photo.update { it.copy(lastUri = uri, thumbnail = thumb, message = note ?: it.message) }
        if (v.rawOutput == AppSettings.RawOutput.DNG || v.rawOutput == AppSettings.RawOutput.BOTH) {
            saveLarge(app, "$base.dng", "image/x-adobe-dng") { stack.writeDng(it, rotation, plan.gain, Build.MANUFACTURER, Build.MODEL) }
        }
        if (v.rawOutput == AppSettings.RawOutput.TIFF || v.rawOutput == AppSettings.RawOutput.BOTH) {
            saveLarge(app, "$base.tif", "image/tiff") { stack.writeTiff(it, rotation, plan.gain, SHARPEN, v.look.code) }
        }
        val totalMs = System.currentTimeMillis() - started
        AppLog.i("Burst $kind saved $uri in $totalMs ms in the background (${stats.engine} merge); stats ${stats.json}")
        if (v.debug) saveDebugInfo(stack, stats, plan, base, debugDir)
    }

    /** DNG and TIFF files are ~75 MB, so they are written to the cache first, then copied to the gallery. */
    private fun saveLarge(app: Application, name: String, mime: String, write: (File) -> Boolean) {
        val tmp = File(app.cacheDir, name)
        try {
            if (!write(tmp)) throw IllegalStateException("Writing $name failed")
            PhotoStore.saveFile(app, tmp, mime, name)
        } catch (e: Exception) {
            AppLog.e("Could not save $name", e)
        } finally {
            tmp.delete()
        }
    }

    /** Debug mode: every source frame as a JPEG, before processing frees them. */
    private fun saveSourceFrames(stack: StackProcessor, base: String, dir: String) {
        val app = getApplication<Application>()
        for (i in 0 until stack.count) {
            val nv21 = stack.frameNv21(i) ?: continue
            val out = ByteArrayOutputStream()
            YuvImage(nv21, ImageFormat.NV21, stack.width, stack.height, null)
                .compressToJpeg(Rect(0, 0, stack.width, stack.height), 90, out)
            try {
                PhotoStore.saveJpeg(app, out.toByteArray(), "${base}_frame$i.jpg", dir)
            } catch (e: Exception) {
                AppLog.e("Could not save debug frame $i", e)
            }
        }
    }

    /** Debug mode: the merge map (green = frames merged, red = moved tiles rejected) and the stats. */
    private fun saveDebugInfo(stack: StackProcessor, stats: StackStats, plan: BurstPlanner.Plan, base: String, dir: String) {
        val app = getApplication<Application>()
        try {
            val weights = stack.tileWeights()
            val tx = stats.tilesX
            val ty = stats.tilesY
            if (tx > 0 && ty > 0 && weights.size >= tx * ty) {
                val map = Bitmap.createBitmap(tx, ty, Bitmap.Config.ARGB_8888)
                for (y in 0 until ty) for (x in 0 until tx) {
                    val w = weights[y * tx + x].coerceIn(0f, 1f)
                    map.setPixel(x, y, android.graphics.Color.rgb(((1 - w) * 255).toInt(), (w * 255).toInt(), 0))
                }
                val big = Bitmap.createScaledBitmap(map, tx * 8, ty * 8, false)
                PhotoStore.saveBitmapPng(app, big, "${base}_mergemap.png", dir)
            }
            PhotoStore.saveText(app, "plan: $plan\nstats: ${stats.json.toString(2)}\n", "${base}_stats.txt")
        } catch (e: Exception) {
            AppLog.e("Could not save debug info", e)
        }
    }

    override fun onCleared() {
        controller.release()
        // processingScope is left running: bursts already captured still get saved.
    }

    private companion object {
        /** Gentle unsharp mask after merging; Samsung's own sharpening is off for burst frames. */
        const val SHARPEN = 0.35f
        const val HDR_FRAMES = 10
        /** Frames from before the shutter press merged into each burst. */
        const val PRE_FRAMES = 3
        /** Captured bursts waiting to be merged (each holds 200-300 MB of frames) before the shutter waits. */
        const val MAX_QUEUED = 2
    }
}
