package com.a56cam.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.a56cam.camera.AppLog
import com.a56cam.diagnostics.DiagnosticsResult
import com.a56cam.diagnostics.DiagnosticsRunner
import com.a56cam.processing.NativeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val running: Boolean = false,
    val progress: List<String> = emptyList(),
    val result: DiagnosticsResult? = null,
    val message: String? = null,
    val nativeInfo: String = "",
    val crashLogs: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})"

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var job: Job? = null
    @Volatile private var cancelReason: String? = null

    init {
        val native = try {
            NativeBridge.selfTest()
        } catch (t: Throwable) {
            AppLog.e("Native library failed", t)
            "FAILED: $t"
        }
        AppLog.i("Native self-test: $native")
        _state.update { it.copy(nativeInfo = native) }
        refreshCrashCount()
    }

    fun refreshCrashCount() {
        val crashes = com.a56cam.camera.AppLog.logFiles(getApplication()).count { it.name.startsWith("crash-") }
        _state.update { it.copy(crashLogs = crashes) }
    }

    fun runReport() {
        if (job?.isActive == true) return
        cancelReason = null
        _state.update { it.copy(running = true, progress = emptyList(), result = null, message = null) }
        job = viewModelScope.launch {
            try {
                val result = DiagnosticsRunner(getApplication()).run(version) { step ->
                    AppLog.i("Progress: $step")
                    _state.update { it.copy(progress = it.progress + step) }
                }
                _state.update { it.copy(running = false, result = result) }
            } catch (e: CancellationException) {
                AppLog.w("Diagnostics run cancelled: $cancelReason")
                _state.update { it.copy(running = false, message = cancelReason ?: "Run cancelled.") }
                throw e
            } catch (t: Throwable) {
                AppLog.e("Diagnostics run failed", t)
                _state.update { it.copy(running = false, message = "The report failed: $t. Please tap Share logs.") }
            }
        }
    }

    /** Called from onPause: the camera must be released whenever the app leaves the screen. */
    fun cancelRun(reason: String) {
        if (job?.isActive != true) return
        cancelReason = reason
        job?.cancel()
    }
}
