package com.a56cam.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Coroutine wrappers around the callback-based Camera2 API. Callbacks run on a [CameraThread]. */
object CameraOps {

    suspend fun openCamera(
        manager: CameraManager,
        id: String,
        thread: CameraThread,
        timeoutMs: Long = 5_000,
    ): CameraDevice = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    AppLog.d("Camera $id opened")
                    if (cont.isActive) cont.resume(device) else device.close()
                }

                override fun onDisconnected(device: CameraDevice) {
                    AppLog.w("Camera $id disconnected")
                    device.close()
                    if (cont.isActive) cont.resumeWithException(CameraException("Camera $id disconnected"))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    AppLog.e("Camera $id error ${deviceErrorName(error)}")
                    device.close()
                    if (cont.isActive) {
                        cont.resumeWithException(CameraException("Camera $id error ${deviceErrorName(error)}"))
                    }
                }
            }
            try {
                manager.openCamera(id, thread.executor, callback)
            } catch (e: Exception) {
                AppLog.e("openCamera($id) threw", e)
                if (cont.isActive) cont.resumeWithException(CameraException("openCamera($id) threw: $e", e))
            }
        }
    }

    suspend fun createSession(
        device: CameraDevice,
        outputs: List<OutputConfiguration>,
        thread: CameraThread,
        timeoutMs: Long = 5_000,
    ): CameraCaptureSession = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(session) else session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    AppLog.e("Camera ${device.id} session configuration failed for ${outputs.size} outputs")
                    if (cont.isActive) {
                        cont.resumeWithException(CameraException("Camera ${device.id} session configuration failed"))
                    }
                }
            }
            try {
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, outputs, thread.executor, callback,
                )
                device.createCaptureSession(config)
            } catch (e: Exception) {
                AppLog.e("Camera ${device.id} createCaptureSession threw", e)
                if (cont.isActive) cont.resumeWithException(CameraException("createCaptureSession threw: $e", e))
            }
        }
    }

    fun deviceErrorName(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
        else -> "UNKNOWN($error)"
    }

    /** Short description of the settings that matter for diagnosing a failed request. */
    fun describeRequest(request: CaptureRequest): String = buildString {
        append("tag=").append(request.tag)
        append(" aeMode=").append(request.get(CaptureRequest.CONTROL_AE_MODE))
        append(" exposureNs=").append(request.get(CaptureRequest.SENSOR_EXPOSURE_TIME))
        append(" iso=").append(request.get(CaptureRequest.SENSOR_SENSITIVITY))
        append(" frameDurationNs=").append(request.get(CaptureRequest.SENSOR_FRAME_DURATION))
        append(" evComp=").append(request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION))
        append(" fps=").append(request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE))
        append(" nr=").append(request.get(CaptureRequest.NOISE_REDUCTION_MODE))
        append(" edge=").append(request.get(CaptureRequest.EDGE_MODE))
    }
}
