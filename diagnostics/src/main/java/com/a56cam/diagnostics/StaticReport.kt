package com.a56cam.diagnostics

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.a56cam.camera.AppLog
import com.a56cam.camera.CameraNames
import org.json.JSONArray
import org.json.JSONObject

/** Everything that can be learned without opening a camera, for every listed and physical camera ID. */
internal class StaticReport(private val manager: CameraManager, private val errors: ErrorLog) {

    fun build(cameraIds: List<String>): JSONObject {
        val cameras = JSONObject()
        for (id in cameraIds) {
            val chars = errors.step("characteristics $id") { manager.getCameraCharacteristics(id) } ?: continue
            val cam = describe(id, chars)
            errors.step("extensions $id") { cam.put("extensions", extensions(id)) }
            val physical = JSONObject()
            for (physId in chars.physicalCameraIds) {
                errors.step("physical characteristics $physId (under $id)") {
                    physical.put(physId, describe(physId, manager.getCameraCharacteristics(physId)))
                }
            }
            cam.put("physicalCameras", physical)
            cameras.put(id, cam)
        }
        return cameras
    }

    fun describe(id: String, chars: CameraCharacteristics): JSONObject {
        val cam = JSONObject()
        cam.put("id", id)
        cam.put("lensFacing", CameraNames.name(CameraNames.lensFacing, chars.get(CameraCharacteristics.LENS_FACING)))
        cam.put(
            "hardwareLevel",
            CameraNames.name(CameraNames.hardwareLevel, chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
        )
        cam.put("capabilities", names(CameraNames.capability, chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)))

        val all = JSONObject()
        val vendorKeys = JSONArray()
        for (key in chars.keys) {
            val value = try {
                JsonValues.toJson(chars.get(key))
            } catch (e: Exception) {
                "<error: $e>"
            }
            all.put(key.name, value)
            if (!key.name.startsWith("android.")) vendorKeys.put(key.name)
        }
        cam.put("characteristics", all)
        cam.put("vendorKeys", vendorKeys)
        cam.put("keysNeedingPermission", keyNames(chars.keysNeedingPermission))
        cam.put("captureRequestKeys", keyNames(chars.availableCaptureRequestKeys))
        cam.put("captureResultKeys", keyNames(chars.availableCaptureResultKeys))
        cam.put("sessionKeys", keyNames(chars.availableSessionKeys))
        cam.put("physicalCameraRequestKeys", keyNames(chars.availablePhysicalCameraRequestKeys))

        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let {
            cam.put("streamConfigurations", JsonValues.streamMap(it))
        }
        cam.put("modes", modes(chars))
        return cam
    }

    private fun modes(chars: CameraCharacteristics): JSONObject = JSONObject()
        .put("ae", names(CameraNames.aeMode, chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)))
        .put("af", names(CameraNames.afMode, chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)))
        .put("awb", names(CameraNames.awbMode, chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)))
        .put(
            "noiseReduction",
            names(CameraNames.noiseReduction, chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)),
        )
        .put("edge", names(CameraNames.edge, chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)))
        .put("tonemap", names(CameraNames.tonemap, chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)))
        .put("hotPixel", names(CameraNames.hotPixel, chars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)))
        .put("lensShading", names(CameraNames.lensShading, chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)))
        .put("aeLockAvailable", JsonValues.toJson(chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)))
        .put("awbLockAvailable", JsonValues.toJson(chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)))
        .put("aeFpsRanges", JsonValues.toJson(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)))
        .put("exposureTimeRangeNs", JsonValues.toJson(chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)))
        .put("sensitivityRange", JsonValues.toJson(chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)))
        .put("maxFrameDurationNs", JsonValues.toJson(chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)))

    private fun keyNames(keys: List<*>?): JSONArray =
        JSONArray().apply { keys?.forEach { put(keyName(it)) } }

    private fun names(map: Map<Int, String>, values: IntArray?): JSONArray =
        JSONArray().apply { values?.forEach { put(CameraNames.name(map, it)) } }

    /** Camera Extensions (Samsung Night, HDR, ...) as seen by a third-party app. */
    private fun extensions(id: String): JSONObject {
        val out = JSONObject()
        val ext = manager.getCameraExtensionCharacteristics(id)
        val supported = ext.supportedExtensions
        out.put("supported", JSONArray(supported.map { CameraNames.name(CameraNames.extension, it) }))
        val details = JSONObject()
        for (e in supported) {
            val d = JSONObject()
            errors.step("extension ${CameraNames.name(CameraNames.extension, e)} on $id") {
                val jpeg = ext.getExtensionSupportedSizes(e, ImageFormat.JPEG)
                val yuv = ext.getExtensionSupportedSizes(e, ImageFormat.YUV_420_888)
                d.put("jpegSizes", JsonValues.toJson(jpeg))
                d.put("yuvSizes", JsonValues.toJson(yuv))
                d.put("previewSizes", JsonValues.toJson(ext.getExtensionSupportedSizes(e, SurfaceTexture::class.java)))
                if (Build.VERSION.SDK_INT >= 34) {
                    d.put("jpegRSizes", JsonValues.toJson(ext.getExtensionSupportedSizes(e, ImageFormat.JPEG_R)))
                    d.put("postviewAvailable", ext.isPostviewAvailable(e))
                    d.put("captureProgressAvailable", ext.isCaptureProcessProgressAvailable(e))
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    val latency = JSONObject()
                    jpeg.maxByOrNull { it.width.toLong() * it.height }?.let { size ->
                        latency.put("jpeg ${size.width}x${size.height}",
                            JsonValues.toJson(ext.getEstimatedCaptureLatencyRangeMillis(e, size, ImageFormat.JPEG)))
                    }
                    yuv.maxByOrNull { it.width.toLong() * it.height }?.let { size ->
                        latency.put("yuv ${size.width}x${size.height}",
                            JsonValues.toJson(ext.getEstimatedCaptureLatencyRangeMillis(e, size, ImageFormat.YUV_420_888)))
                    }
                    d.put("captureLatencyRangeMs", latency)
                    d.put("captureRequestKeys", JSONArray(ext.getAvailableCaptureRequestKeys(e).map { keyName(it) }))
                    d.put("captureResultKeys", JSONArray(ext.getAvailableCaptureResultKeys(e).map { keyName(it) }))
                }
                if (Build.VERSION.SDK_INT >= 35) {
                    val keys = JSONObject()
                    for (key in ext.getKeys(e)) keys.put(key.name, JsonValues.toJson(ext.get(e, key)))
                    d.put("characteristics", keys)
                }
            }
            details.put(CameraNames.name(CameraNames.extension, e), d)
        }
        out.put("details", details)
        AppLog.i("Camera $id extensions: ${supported.map { CameraNames.name(CameraNames.extension, it) }}")
        return out
    }
}

internal fun keyName(key: Any?): String = when (key) {
    is CameraCharacteristics.Key<*> -> key.name
    is android.hardware.camera2.CaptureRequest.Key<*> -> key.name
    is android.hardware.camera2.CaptureResult.Key<*> -> key.name
    else -> key.toString()
}

/** Collects non-fatal errors so one failing probe never stops the report. */
internal class ErrorLog {
    val entries = JSONArray()

    inline fun <T> step(name: String, block: () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        // A camera call timed out: a result for this step, not a cancelled run.
        record(name, e)
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        record(name, e)
        null
    } catch (e: LinkageError) {
        record(name, e)
        null
    }

    fun record(name: String, e: Throwable) {
        AppLog.e("Diagnostics step failed: $name", e)
        entries.put(JSONObject().put("step", name).put("error", e.toString()))
    }
}
