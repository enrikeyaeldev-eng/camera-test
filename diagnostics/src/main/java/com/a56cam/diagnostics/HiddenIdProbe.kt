package com.a56cam.diagnostics

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.util.Size
import com.a56cam.camera.AppLog
import com.a56cam.camera.CameraNames
import com.a56cam.camera.CameraThread
import org.json.JSONArray
import org.json.JSONObject

/** Tries camera IDs that getCameraIdList() does not return; some phones still open them. */
internal class HiddenIdProbe(
    private val manager: CameraManager,
    private val cameraThread: CameraThread,
    private val imageThread: CameraThread,
    private val errors: ErrorLog,
) {
    suspend fun run(listed: List<String>, progress: (String) -> Unit): JSONArray {
        val out = JSONArray()
        val candidates = ((0..20).map { it.toString() } + EXTRA_IDS).filter { it !in listed }
        for (id in candidates) {
            progress("Probing hidden camera ID $id")
            val o = JSONObject().put("id", id)
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                o.put("characteristics", false).put("characteristicsError", e.javaClass.simpleName)
                null
            }
            if (chars != null) {
                o.put("characteristics", true)
                    .put("lensFacing", CameraNames.name(CameraNames.lensFacing, chars.get(CameraCharacteristics.LENS_FACING)))
                    .put("focalLengths", JsonValues.toJson(chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)))
                    .put("physicalIds", JSONArray(chars.physicalCameraIds.toList()))
            }
            val size = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(LiveTests.YUV)?.minByOrNull { it.width.toLong() * it.height }
                ?: Size(640, 480)
            try {
                LiveSession.open(manager, id, size, cameraThread, imageThread, maxImages = 3, openTimeoutMs = 3_000).use { s ->
                    o.put("opened", true)
                    s.repeating(s.request(CameraDevice.TEMPLATE_PREVIEW, "probe-$id"))
                    val frames = s.collectTag("probe-$id", 3, 3_000)
                    o.put("streamed", frames.isNotEmpty()).put("framesReceived", frames.size)
                        .put("streamSize", "${size.width}x${size.height}")
                    s.session.stopRepeating()
                }
                AppLog.i("Hidden camera ID $id opened: $o")
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    o.put("opened", false).put("openError", "timeout")
                } else {
                    throw e
                }
            } catch (e: Exception) {
                o.put("opened", false).put("openError", e.toString())
            }
            out.put(o)
        }
        return out
    }

    companion object {
        val EXTRA_IDS = listOf("21", "22", "23", "24", "25", "50", "52", "54", "56", "58", "60", "64", "80", "90", "99", "100")
    }
}
