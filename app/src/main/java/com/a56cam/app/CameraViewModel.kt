package com.a56cam.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.a56cam.camera.AppLog
import com.a56cam.camera.CameraController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    data class PhotoUi(
        val lastUri: Uri? = null,
        val thumbnail: Bitmap? = null,
        val saving: Boolean = false,
        val message: String? = null,
        /** Increments on every successful shot; drives the shutter flash. */
        val shots: Int = 0,
    )

    val controller = CameraController(app)
    val settings = AppSettings(app)

    private val _photo = MutableStateFlow(PhotoUi())
    val photo: StateFlow<PhotoUi> = _photo

    init {
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

    fun shoot(deviceOrientation: Int) {
        if (_photo.value.saving || controller.state.value.capturing) return
        _photo.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            try {
                val bytes = controller.takePicture(deviceOrientation, settings.values.value.captureOptions())
                val app = getApplication<Application>()
                val uri = withContext(Dispatchers.IO) { PhotoStore.saveJpeg(app, bytes) }
                val thumb = withContext(Dispatchers.Default) { PhotoStore.thumbnail(bytes) }
                val opts = settings.values.value
                AppLog.i(
                    "Saved photo $uri (${bytes.size} bytes) settings nr=${opts.noiseReduction} edge=${opts.edge} " +
                        "q=${opts.jpegQuality} mean ${thumb?.let { PhotoStore.meanRgb(it) }}",
                )
                _photo.update { it.copy(lastUri = uri, thumbnail = thumb, saving = false, shots = it.shots + 1) }
            } catch (e: CancellationException) {
                _photo.update { it.copy(saving = false) }
                throw e
            } catch (e: Exception) {
                AppLog.e("Taking a photo failed", e)
                _photo.update { it.copy(saving = false, message = "Photo not saved: ${e.message}") }
            }
        }
    }

    override fun onCleared() {
        controller.release()
    }
}
