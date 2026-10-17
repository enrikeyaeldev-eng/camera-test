package com.a56cam.app

import android.content.Context
import android.hardware.camera2.CameraMetadata
import com.a56cam.camera.CaptureOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User settings, stored in SharedPreferences. */
class AppSettings(context: Context) {

    /** Extra full-quality file saved next to the JPEG for burst modes. */
    enum class RawOutput(val label: String) {
        NONE("JPEG only"),
        DNG("JPEG + linear DNG (Lightroom)"),
        TIFF("JPEG + 16-bit TIFF"),
        BOTH("JPEG + DNG + TIFF"),
    }

    data class Values(
        /** A CameraMetadata NOISE_REDUCTION_MODE_* value, or [CAMERA_DEFAULT]. */
        val noiseReduction: Int = CAMERA_DEFAULT,
        /** A CameraMetadata EDGE_MODE_* value, or [CAMERA_DEFAULT]. Off by default: Phase 0 showed Samsung's HQ sharpening is very strong. */
        val edge: Int = CameraMetadata.EDGE_MODE_OFF,
        val jpegQuality: Int = 95,
        val lastLensId: String? = null,
        val mode: String = CaptureMode.PHOTO.name,
        val stackFrames: Int = 8,
        val nightFrames: Int = 12,
        val rawOutput: RawOutput = RawOutput.DNG,
        val volumeShutter: Boolean = true,
        val grid: Boolean = false,
        val timerSeconds: Int = 0,
        val debug: Boolean = false,
    ) {
        fun captureOptions() = CaptureOptions(
            jpegQuality = jpegQuality,
            noiseReduction = noiseReduction.takeIf { it != CAMERA_DEFAULT },
            edge = edge.takeIf { it != CAMERA_DEFAULT },
        )
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(load())
    val values: StateFlow<Values> = _values

    fun update(transform: (Values) -> Values) {
        val v = transform(_values.value)
        _values.value = v
        prefs.edit()
            .putInt("noiseReduction", v.noiseReduction)
            .putInt("edge", v.edge)
            .putInt("jpegQuality", v.jpegQuality)
            .putString("lastLensId", v.lastLensId)
            .putString("mode", v.mode)
            .putInt("stackFrames", v.stackFrames)
            .putInt("nightFrames", v.nightFrames)
            .putString("rawOutput", v.rawOutput.name)
            .putBoolean("volumeShutter", v.volumeShutter)
            .putBoolean("grid", v.grid)
            .putInt("timerSeconds", v.timerSeconds)
            .putBoolean("debug", v.debug)
            .apply()
    }

    private fun load(): Values {
        val d = Values()
        return Values(
            noiseReduction = prefs.getInt("noiseReduction", d.noiseReduction),
            edge = prefs.getInt("edge", d.edge),
            jpegQuality = prefs.getInt("jpegQuality", d.jpegQuality),
            lastLensId = prefs.getString("lastLensId", null),
            mode = prefs.getString("mode", d.mode) ?: d.mode,
            stackFrames = prefs.getInt("stackFrames", d.stackFrames),
            nightFrames = prefs.getInt("nightFrames", d.nightFrames),
            rawOutput = prefs.getString("rawOutput", null)?.let { name -> RawOutput.entries.firstOrNull { it.name == name } }
                ?: d.rawOutput,
            volumeShutter = prefs.getBoolean("volumeShutter", d.volumeShutter),
            grid = prefs.getBoolean("grid", d.grid),
            timerSeconds = prefs.getInt("timerSeconds", d.timerSeconds),
            debug = prefs.getBoolean("debug", d.debug),
        )
    }

    companion object {
        const val CAMERA_DEFAULT = -1
    }
}
