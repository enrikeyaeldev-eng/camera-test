package com.a56cam.diagnostics

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import android.util.Range
import android.util.Size
import com.a56cam.camera.AppLog
import com.a56cam.camera.CameraNames
import com.a56cam.camera.CameraThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Capture tests on one camera. Each test returns JSON and never throws; frames are saved into [outDir]. */
internal class LiveTests(
    private val manager: CameraManager,
    private val cameraId: String,
    private val chars: CameraCharacteristics,
    private val outDir: File,
    private val cameraThread: CameraThread,
    private val imageThread: CameraThread,
    private val errors: ErrorLog,
) {
    private val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    private val fullSize: Size? = map?.getOutputSizes(YUV)?.maxByOrNull { it.width.toLong() * it.height }
    private val fpsRanges: List<Range<Int>> =
        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
    private val fixed30: Range<Int>? = fpsRanges.firstOrNull { it.lower == 30 && it.upper == 30 }
    private val aeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
    private val awbLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true

    private suspend fun <T> withSession(block: suspend LiveSession.() -> T): T {
        val size = fullSize ?: throw IllegalStateException("Camera $cameraId has no YUV_420_888 output")
        return LiveSession.open(manager, cameraId, size, cameraThread, imageThread).use { it.block() }
    }

    /** Runs AE/AF/AWB on a repeating request until settled enough for testing. */
    private suspend fun LiveSession.warmUp(tag: String, frames: Int = 20) {
        repeating(request(CameraDevice.TEMPLATE_PREVIEW, tag) {
            fixed30?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        })
        collectTag(tag, frames, 6_000)
    }

    // ---------------------------------------------------------------- burst speed

    fun hasYuv(): Boolean = fullSize != null

    suspend fun burstSpeed(): JSONObject = JSONObject().also { out ->
        errors.step("burst speed") {
            withSession {
                out.put("size", "${size.width}x${size.height}")
                out.put("fixedFpsRange", JsonValues.toJson(fixed30))
                out.put("timestampSource", JsonValues.toJson(chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)))
                map?.let {
                    val minFrame = it.getOutputMinFrameDuration(YUV, size)
                    out.put("minFrameDurationNs", minFrame)
                    out.put("advertisedMaxFps", if (minFrame > 0) 1e9 / minFrame else JSONObject.NULL)
                }
                warmUp("burst-warm")

                // A full-resolution reference frame, so the owner can judge Samsung's processing.
                fullCopyRequested.set(true)
                collectTag("burst-warm", 2, 3_000).firstNotNullOfOrNull { it.frame.full }?.let {
                    errors.step("save full frame") { Yuv.saveJpeg(it, File(outDir, "burst_reference_full.jpg")) }
                }

                val variants = JSONArray()
                for ((name, template) in listOf(
                    "captureBurst_STILL_CAPTURE" to CameraDevice.TEMPLATE_STILL_CAPTURE,
                    "captureBurst_PREVIEW" to CameraDevice.TEMPLATE_PREVIEW,
                )) {
                    session.stopRepeating()
                    val prefix = "b-$name"
                    val requests = (0 until BURST_FRAMES).map { i ->
                        request(template, "$prefix-$i") { burstSettings() }
                    }
                    val submitNs = SystemClock.elapsedRealtimeNanos()
                    val log = recordRaw(prefix, BURST_FRAMES, 10_000) { burst(requests) }
                    variants.put(burstMetrics(name, log, submitNs))
                    warmUp("burst-rewarm-$name", 8)
                }
                for ((name, template) in listOf(
                    "repeating_PREVIEW" to CameraDevice.TEMPLATE_PREVIEW,
                    "repeating_STILL_CAPTURE" to CameraDevice.TEMPLATE_STILL_CAPTURE,
                )) {
                    val prefix = "r-$name"
                    val submitNs = SystemClock.elapsedRealtimeNanos()
                    val log = recordRaw(prefix, BURST_FRAMES, 10_000) {
                        repeating(request(template, prefix) { burstSettings() })
                    }
                    variants.put(burstMetrics(name, log, submitNs))
                }
                session.stopRepeating()
                out.put("variants", variants)
                out.put("frameStats", frameStats())
            }
        }
    }

    private fun CaptureRequest.Builder.burstSettings() {
        fixed30?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        if (aeLock) set(CaptureRequest.CONTROL_AE_LOCK, true)
        if (awbLock) set(CaptureRequest.CONTROL_AWB_LOCK, true)
    }

    private fun burstMetrics(name: String, log: RawLog, submitNs: Long): JSONObject {
        val stamps = log.results.map { it.timestampNs }.sorted().take(BURST_FRAMES)
        val frames = log.frameTimestamps.toHashSet()
        val received = stamps.count { it in frames }
        val o = JSONObject()
            .put("variant", name)
            .put("requested", BURST_FRAMES)
            .put("results", stamps.size)
            .put("imagesReceived", received)
            .put("droppedImages", BURST_FRAMES - received)
            .put("failures", log.failures)
            .put("buffersLost", log.bufferLost)
        if (stamps.size >= 2) {
            val intervalsMs = stamps.zipWithNext { a, b -> (b - a) / 1e6 }
            val spanS = (stamps.last() - stamps.first()) / 1e9
            o.put("fps", round2((stamps.size - 1) / spanS))
            o.put("medianIntervalMs", round2(intervalsMs.sorted()[intervalsMs.size / 2]))
            o.put("maxIntervalMs", round2(intervalsMs.max()))
            o.put("intervalsMs", JSONArray(intervalsMs.map { round2(it) }))
        }
        val frameNumbers = log.results.map { it.frameNumber }.sorted()
        if (frameNumbers.size >= 2) {
            o.put("frameNumberGaps", frameNumbers.zipWithNext { a, b -> b - a - 1 }.sum())
        }
        // Only meaningful when sensor timestamps use the same clock as elapsedRealtime.
        if (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME && stamps.isNotEmpty()
        ) {
            o.put("firstFrameLatencyMs", round2((stamps.first() - submitNs) / 1e6))
        }
        AppLog.i("Burst $name on $cameraId: $o")
        return o
    }

    // ---------------------------------------------------------------- manual exposure

    suspend fun manualExposure(): JSONObject = JSONObject().also { out ->
        val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList() ?: emptyList()
        out.put("aeOffAdvertised", aeModes.contains(CameraMetadata.CONTROL_AE_MODE_OFF))
        if (!aeModes.contains(CameraMetadata.CONTROL_AE_MODE_OFF)) {
            AppLog.w("Camera $cameraId: AE mode OFF not offered, skipping manual exposure test")
            out.put("skipped", "AE mode OFF not available")
            return@also
        }
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        out.put("exposureRangeNs", JsonValues.toJson(expRange))
        out.put("isoRange", JsonValues.toJson(isoRange))
        if (expRange == null) AppLog.w("Camera $cameraId: no SENSOR_INFO_EXPOSURE_TIME_RANGE, using defaults")
        if (isoRange == null) AppLog.w("Camera $cameraId: no SENSOR_INFO_SENSITIVITY_RANGE, using defaults")
        val minExp = expRange?.lower ?: 100_000L
        val maxExp = minOf(expRange?.upper ?: 250_000_000L, MAX_TEST_EXPOSURE_NS)
        val minIso = isoRange?.lower ?: 100
        val maxIso = isoRange?.upper ?: 1600

        errors.step("manual exposure") {
            withSession {
                cropSize = 512
                warmUp("me-warm")
                val auto = collectTag("me-warm", 1, 2_000).lastOrNull()
                out.put("autoExposure", auto?.let { describeSample(it) })

                val baseIso = 200.coerceIn(minIso, maxIso)
                val exposures = (listOf(1_000_000L, 4_000_000L, 16_666_667L, 66_666_667L, 250_000_000L) + maxExp)
                    .map { it.coerceIn(minExp, maxExp) }.distinct().sorted()
                val exposureSweep = JSONArray()
                for ((i, exp) in exposures.withIndex()) {
                    exposureSweep.put(manualPoint("me-e$i", exp, baseIso))
                }
                out.put("exposureSweep", exposureSweep)

                val baseExp = 16_666_667L.coerceIn(minExp, maxExp)
                val isos = listOf(100, 200, 400, 800, 1600, 3200).map { it.coerceIn(minIso, maxIso) }.distinct()
                val isoSweep = JSONArray()
                for ((i, iso) in isos.withIndex()) {
                    isoSweep.put(manualPoint("me-i$i", baseExp, iso))
                }
                out.put("isoSweep", isoSweep)
                session.stopRepeating()

                out.put("exposureHonoured", allClose(exposureSweep, "requestedExposureNs", "resultExposureNs"))
                out.put("isoHonoured", allClose(isoSweep, "requestedIso", "resultIso"))
                out.put("brightnessFollowsExposure", monotonicLuma(exposureSweep))
                out.put("brightnessFollowsIso", monotonicLuma(isoSweep))
                out.put("frameStats", frameStats())
            }
        }
    }

    private suspend fun LiveSession.manualPoint(tag: String, exposureNs: Long, iso: Int): JSONObject {
        val minFrame = map?.getOutputMinFrameDuration(YUV, size) ?: 33_333_333L
        val frameDuration = maxOf(exposureNs, minFrame)
        val frames = if (exposureNs >= 200_000_000L) 3 else 6
        val timeout = (frames * frameDuration / 1_000_000L * 2 + 4_000L).coerceAtMost(120_000L)
        repeating(request(CameraDevice.TEMPLATE_PREVIEW, tag) {
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)
        })
        val samples = collectTag(tag, frames, timeout)
        val o = JSONObject()
            .put("requestedExposureNs", exposureNs)
            .put("requestedIso", iso)
            .put("requestedFrameDurationNs", frameDuration)
            .put("framesReceived", samples.size)
        val last = samples.lastOrNull()
        if (last == null) {
            AppLog.e("Camera $cameraId: no frames for manual exposure $exposureNs ns ISO $iso")
            return o.put("error", "no frames within ${timeout} ms")
        }
        o.put("resultExposureNs", last.get(CaptureResult.SENSOR_EXPOSURE_TIME))
            .put("resultIso", last.get(CaptureResult.SENSOR_SENSITIVITY))
            .put("resultFrameDurationNs", last.get(CaptureResult.SENSOR_FRAME_DURATION))
            .put("resultAeMode", CameraNames.name(CameraNames.aeMode, last.get(CaptureResult.CONTROL_AE_MODE)))
            .put("meanLuma", round2(last.frame.meanLuma))
            .put("lumaPerFrame", JSONArray(samples.map { round2(it.frame.meanLuma) }))
        last.frame.crop?.let {
            errors.step("save $tag") {
                Yuv.saveJpeg(it, File(outDir, String.format(Locale.US, "manual_%dus_iso%d.jpg", exposureNs / 1000, iso)))
            }
        }
        return o
    }

    private fun allClose(points: JSONArray, requested: String, result: String): Boolean {
        if (points.length() == 0) return false
        for (i in 0 until points.length()) {
            val p = points.getJSONObject(i)
            if (!p.has(result)) return false
            val req = p.getDouble(requested)
            val got = p.optDouble(result, Double.NaN)
            if (got.isNaN() || abs(got - req) / req > 0.1) return false
        }
        return true
    }

    /** True when brightness never drops as exposure or ISO rises (ignoring clipped and black frames). */
    private fun monotonicLuma(points: JSONArray): Boolean {
        val lumas = (0 until points.length()).mapNotNull { points.getJSONObject(it).optDouble("meanLuma", Double.NaN) }
            .filter { !it.isNaN() && it in 3.0..235.0 }
        if (lumas.size < 2) return false
        return lumas.zipWithNext().all { (a, b) -> b >= a * 0.98 } && lumas.last() > lumas.first() * 1.1
    }

    private fun describeSample(s: Sample): JSONObject = JSONObject()
        .put("exposureNs", s.get(CaptureResult.SENSOR_EXPOSURE_TIME))
        .put("iso", s.get(CaptureResult.SENSOR_SENSITIVITY))
        .put("frameDurationNs", s.get(CaptureResult.SENSOR_FRAME_DURATION))
        .put("aeState", CameraNames.name(CameraNames.aeState, s.get(CaptureResult.CONTROL_AE_STATE)))
        .put("meanLuma", round2(s.frame.meanLuma))

    // ---------------------------------------------------------------- EV bracketing

    suspend fun evBracketing(): JSONObject = JSONObject().also { out ->
        val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        out.put("compensationRange", JsonValues.toJson(range))
        out.put("compensationStep", JsonValues.toJson(step))
        if (range == null || step == null || step.toDouble() <= 0.0) {
            out.put("skipped", "no exposure compensation")
            return@also
        }
        errors.step("EV bracketing") {
            withSession {
                cropSize = 512
                warmUp("ev-warm")
                val series = JSONArray()
                var zeroLuma: Double? = null
                for ((i, ev) in listOf(0.0, -2.0, 2.0, 0.0).withIndex()) {
                    val steps = (ev / step.toDouble()).roundToInt().coerceIn(range.lower, range.upper)
                    val tag = "ev$i"
                    repeating(request(CameraDevice.TEMPLATE_PREVIEW, tag) {
                        fixed30?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
                    })
                    val samples = collectTag(tag, EV_FRAMES, 8_000)
                    val lumas = samples.map { it.frame.meanLuma }
                    val o = JSONObject().put("ev", ev).put("compensationSteps", steps).put("frames", samples.size)
                    if (lumas.isNotEmpty()) {
                        val final = lumas.takeLast(3).average()
                        val settle = lumas.indices.firstOrNull { start ->
                            lumas.drop(start).all { abs(it - final) <= maxOf(1.0, final * 0.03) }
                        }
                        val converged = samples.indexOfFirst {
                            it.get(CaptureResult.CONTROL_AE_STATE) == CameraMetadata.CONTROL_AE_STATE_CONVERGED
                        }
                        if (i == 0) zeroLuma = final
                        o.put("finalLuma", round2(final))
                            .put("framesToSettle", settle ?: JSONObject.NULL)
                            .put("firstAeConvergedFrame", converged)
                            .put("lumaRatioVs0Ev", zeroLuma?.let { if (it > 0) round2(final / it) else null } ?: JSONObject.NULL)
                            .put("finalExposureNs", samples.last().get(CaptureResult.SENSOR_EXPOSURE_TIME))
                            .put("finalIso", samples.last().get(CaptureResult.SENSOR_SENSITIVITY))
                            .put("lumaPerFrame", JSONArray(lumas.map { round2(it) }))
                            .put("aeStatePerFrame", JSONArray(samples.map {
                                CameraNames.name(CameraNames.aeState, it.get(CaptureResult.CONTROL_AE_STATE))
                            }))
                        samples.last().frame.crop?.let {
                            errors.step("save $tag") {
                                Yuv.saveJpeg(it, File(outDir, String.format(Locale.US, "ev_%d_%+.0f.jpg", i, ev)))
                            }
                        }
                    }
                    series.put(o)
                }
                session.stopRepeating()
                out.put("series", series)
                out.put("frameStats", frameStats())
            }
        }
    }

    // ---------------------------------------------------------------- noise reduction and edge modes

    suspend fun processingModes(): JSONObject = JSONObject().also { out ->
        val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList()
            ?.filter { it != CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG } ?: emptyList()
        val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList()
            ?.filter { it != CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG } ?: emptyList()
        out.put("noiseReductionModes", JSONArray(nrModes.map { CameraNames.name(CameraNames.noiseReduction, it) }))
        out.put("edgeModes", JSONArray(edgeModes.map { CameraNames.name(CameraNames.edge, it) }))
        if (nrModes.isEmpty() && edgeModes.isEmpty()) AppLog.w("Camera $cameraId: no NR or edge modes listed")

        val lowestNr = listOf(
            CameraMetadata.NOISE_REDUCTION_MODE_OFF, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL,
            CameraMetadata.NOISE_REDUCTION_MODE_FAST, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        ).firstOrNull { it in nrModes }
        val lowestEdge = listOf(
            CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST, CameraMetadata.EDGE_MODE_HIGH_QUALITY,
        ).firstOrNull { it in edgeModes }
        val combos = linkedSetOf<Pair<Int?, Int?>>()
        combos.add(lowestNr to lowestEdge)
        for (nr in nrModes) combos.add(nr to lowestEdge)
        for (edge in edgeModes) combos.add(lowestNr to edge)
        if (CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY in nrModes && CameraMetadata.EDGE_MODE_HIGH_QUALITY in edgeModes) {
            combos.add(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY to CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        }

        errors.step("processing modes") {
            withSession {
                cropSize = 1024
                warmUp("pm-warm")
                // Freeze exposure and colour so only the processing differs between captures.
                repeating(request(CameraDevice.TEMPLATE_PREVIEW, "pm-lock") {
                    fixed30?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    if (aeLock) set(CaptureRequest.CONTROL_AE_LOCK, true)
                    if (awbLock) set(CaptureRequest.CONTROL_AWB_LOCK, true)
                })
                collectTag("pm-lock", 5, 3_000)
                val results = JSONArray()
                for ((i, combo) in combos.withIndex()) {
                    val (nr, edge) = combo
                    val tag = "pm-$i"
                    capture(request(CameraDevice.TEMPLATE_STILL_CAPTURE, tag) {
                        if (aeLock) set(CaptureRequest.CONTROL_AE_LOCK, true)
                        if (awbLock) set(CaptureRequest.CONTROL_AWB_LOCK, true)
                        nr?.let { set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
                        edge?.let { set(CaptureRequest.EDGE_MODE, it) }
                    })
                    val sample = collectTag(tag, 1, 5_000).firstOrNull()
                    val nrName = CameraNames.name(CameraNames.noiseReduction, nr)
                    val edgeName = CameraNames.name(CameraNames.edge, edge)
                    val o = JSONObject().put("requestedNr", nrName).put("requestedEdge", edgeName)
                    if (sample == null) {
                        o.put("error", "no frame")
                    } else {
                        val resultNr = sample.get(CaptureResult.NOISE_REDUCTION_MODE)
                        val resultEdge = sample.get(CaptureResult.EDGE_MODE)
                        o.put("resultNr", CameraNames.name(CameraNames.noiseReduction, resultNr))
                            .put("resultEdge", CameraNames.name(CameraNames.edge, resultEdge))
                            .put("honoured", (nr == null || resultNr == nr) && (edge == null || resultEdge == edge))
                            .put("exposureNs", sample.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                            .put("iso", sample.get(CaptureResult.SENSOR_SENSITIVITY))
                            .put("meanLuma", round2(sample.frame.meanLuma))
                        sample.frame.crop?.let { crop ->
                            val file = File(outDir, "modes_nr-${nrName}_edge-$edgeName.png")
                            o.put("cropFile", file.name)
                                .put("cropGradientEnergy", round2(Yuv.gradientEnergy(crop)))
                                .put("cropLumaStdDev", round2(Yuv.lumaStdDev(crop)))
                            errors.step("save $tag") { Yuv.savePng(crop, file) }
                        }
                    }
                    results.put(o)
                }
                session.stopRepeating()
                out.put("captures", results)
                out.put("frameStats", frameStats())
            }
        }
    }

    companion object {
        const val YUV = android.graphics.ImageFormat.YUV_420_888
        const val BURST_FRAMES = 15
        const val EV_FRAMES = 30
        const val MAX_TEST_EXPOSURE_NS = 32_000_000_000L

        fun round2(v: Double): Double = Math.round(v * 100) / 100.0
    }
}
