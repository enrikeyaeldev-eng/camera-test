package com.a56cam.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Size
import android.view.SurfaceHolder

/** What the app needs to know about one camera, read once from CameraCharacteristics. */
data class LensInfo(
    val id: String,
    val front: Boolean,
    val zoomLabel: String,
    val equivalentFocalMm: Float,
    val sensorOrientation: Int,
    val previewSize: Size,
    val jpegSize: Size,
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,
    val continuousAf: Boolean,
    val autoAf: Boolean,
    val maxAfRegions: Int,
    val maxAeRegions: Int,
    val evMin: Int,
    val evMax: Int,
    val evStep: Float,
    val aeLockAvailable: Boolean,
    val awbLockAvailable: Boolean,
    val noiseReductionModes: List<Int>,
    val edgeModes: List<Int>,
    /** Largest YUV_420_888 size (burst stacking input), or null if the camera has none. */
    val yuvSize: Size?,
    val yuvMinFrameNs: Long,
    val aeOffAvailable: Boolean,
    val exposureMinNs: Long,
    val exposureMaxNs: Long,
    val isoMin: Int,
    val isoMax: Int,
    /** Closest focus in diopters (1/m); 0 means fixed focus. */
    val minFocusDistance: Float,
    val manualFocusAvailable: Boolean,
    val awbModes: List<Int>,
    /** Field of view relative to the main camera of the same side (0.6 for the ultra-wide). */
    val lensFactor: Float = 1f,
    /** Digital zoom range (CONTROL_ZOOM_RATIO); 1..1 when the camera has none. */
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
) {
    val burstLimits: BurstPlanner.Limits
        get() = BurstPlanner.Limits(exposureMinNs, exposureMaxNs, isoMin, isoMax)
}

/** All usable cameras, main back camera first. Cameras without JPEG or preview outputs are skipped and logged. */
object LensCatalog {

    fun load(manager: CameraManager): List<LensInfo> {
        val raw = manager.cameraIdList.mapNotNull { id ->
            try {
                read(id, manager.getCameraCharacteristics(id))
            } catch (e: Exception) {
                AppLog.e("Camera $id: could not read characteristics", e)
                null
            }
        }
        // Main camera of each side: the one with the largest sensor (ties keep list order).
        fun mainOf(front: Boolean) = raw.filter { it.info.front == front }.maxByOrNull { it.sensorArea }
        val lenses = raw.map { r ->
            val main = mainOf(r.info.front)
            val mainFocal = main?.info?.equivalentFocalMm ?: 0f
            r.info.copy(
                zoomLabel = CameraMath.zoomLabel(r.info.equivalentFocalMm, mainFocal),
                lensFactor = if (mainFocal > 0f && r.info.equivalentFocalMm > 0f) r.info.equivalentFocalMm / mainFocal else 1f,
            )
        }
        val mainBack = mainOf(false)?.info?.id
        val sorted = lenses.sortedWith(compareBy<LensInfo>({ it.front }, { it.id != mainBack }, { it.equivalentFocalMm }))
        AppLog.i(
            "Lenses: " + sorted.joinToString {
                "${it.id}:${if (it.front) "front" else "back"} ${it.zoomLabel} preview ${it.previewSize} jpeg ${it.jpegSize} zoom ${it.zoomMin}-${it.zoomMax}"
            },
        )
        return sorted
    }

    private class Raw(val info: LensInfo, val sensorArea: Float)

    private fun read(id: String, c: CameraCharacteristics): Raw? {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            AppLog.w("Camera $id: no stream configuration map, skipped")
            return null
        }
        val jpeg = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
        if (jpeg == null) {
            AppLog.w("Camera $id: no JPEG output, skipped")
            return null
        }
        val previewCandidates = map.getOutputSizes(SurfaceHolder::class.java)?.map { CameraMath.Dim(it.width, it.height) } ?: emptyList()
        val preview = CameraMath.choosePreviewSize(previewCandidates, CameraMath.Dim(jpeg.width, jpeg.height))
        if (preview == null) {
            AppLog.w("Camera $id: no preview sizes, skipped")
            return null
        }
        val front = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equivalent = if (sensor != null) CameraMath.equivalentFocalLength(focal, sensor.width, sensor.height) else focal
        val array = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        val evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull { it.width.toLong() * it.height }
        val yuvMinFrame = yuv?.let { map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it) } ?: 33_333_333L
        val aeModes = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList() ?: emptyList()
        // LIMITED devices may omit these ranges even though AE OFF works (Phase 0); fall back to measured values.
        val expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val info = LensInfo(
            id = id,
            front = front,
            zoomLabel = "1x",
            equivalentFocalMm = equivalent,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            previewSize = Size(preview.width, preview.height),
            jpegSize = jpeg,
            activeArrayWidth = array?.width() ?: jpeg.width,
            activeArrayHeight = array?.height() ?: jpeg.height,
            continuousAf = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes,
            autoAf = CameraMetadata.CONTROL_AF_MODE_AUTO in afModes,
            maxAfRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            maxAeRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
            evMin = evRange?.lower ?: 0,
            evMax = evRange?.upper ?: 0,
            evStep = evStep?.toFloat() ?: 0f,
            aeLockAvailable = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
            awbLockAvailable = c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
            noiseReductionModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList() ?: emptyList(),
            edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList() ?: emptyList(),
            yuvSize = yuv,
            yuvMinFrameNs = yuvMinFrame,
            aeOffAvailable = CameraMetadata.CONTROL_AE_MODE_OFF in aeModes,
            exposureMinNs = expRange?.lower ?: 100_000L,
            exposureMaxNs = expRange?.upper ?: 100_000_000L,
            isoMin = isoRange?.lower ?: 50,
            isoMax = isoRange?.upper ?: 1600,
            minFocusDistance = minFocus,
            manualFocusAvailable = minFocus > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in afModes,
            awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList(),
            zoomMin = zoomRange?.lower ?: 1f,
            zoomMax = zoomRange?.upper ?: 1f,
        )
        if (yuv == null) AppLog.w("Camera $id: no YUV output, burst modes unavailable")
        if (!info.aeOffAvailable) AppLog.w("Camera $id: AE OFF not offered, bursts use AE lock instead of manual exposure")
        if (!info.continuousAf) AppLog.i("Camera $id: no continuous AF (fixed focus?)")
        if (info.evStep <= 0f) AppLog.w("Camera $id: no exposure compensation")
        return Raw(info, (sensor?.width ?: 0f) * (sensor?.height ?: 0f))
    }
}
