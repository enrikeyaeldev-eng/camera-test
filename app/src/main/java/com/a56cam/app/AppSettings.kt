package com.a56cam.app

import android.content.Context
import android.hardware.camera2.CameraMetadata
import com.a56cam.camera.CaptureOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User settings, stored in SharedPreferences. */
class AppSettings(context: Context) {

    data class Values(
        /** A CameraMetadata NOISE_REDUCTION_MODE_* value, or [CAMERA_DEFAULT]. */
        val noiseReduction: Int = CAMERA_DEFAULT,
        /** A CameraMetadata EDGE_MODE_* value, or [CAMERA_DEFAULT]. Off by default: Phase 0 showed Samsung's HQ sharpening is very strong. */
        val edge: Int = CameraMetadata.EDGE_MODE_OFF,
        val jpegQuality: Int = 95,
        val lastLensId: String? = null,
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
            .apply()
    }

    private fun load(): Values {
        val d = Values()
        return Values(
            noiseReduction = prefs.getInt("noiseReduction", d.noiseReduction),
            edge = prefs.getInt("edge", d.edge),
            jpegQuality = prefs.getInt("jpegQuality", d.jpegQuality),
            lastLensId = prefs.getString("lastLensId", null),
        )
    }

    companion object {
        const val CAMERA_DEFAULT = -1
    }
}
