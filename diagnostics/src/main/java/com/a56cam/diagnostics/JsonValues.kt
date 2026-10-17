package com.a56cam.diagnostics

import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.Capability
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MandatoryStreamCombination
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import com.a56cam.camera.CameraNames
import org.json.JSONArray
import org.json.JSONObject

/** Converts Camera2 metadata values into JSON-compatible values without ever throwing. */
internal object JsonValues {

    fun toJson(value: Any?): Any = try {
        convert(value)
    } catch (e: Exception) {
        "<error: $e>"
    }

    private fun convert(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Boolean, is Int, is Long, is String -> v
        is Float -> if (v.isFinite()) v.toDouble() else v.toString()
        is Double -> if (v.isFinite()) v else v.toString()
        is Byte -> v.toInt()
        is Short -> v.toInt()
        is Enum<*> -> v.name
        is IntArray -> JSONArray().apply { v.forEach { put(it) } }
        is LongArray -> JSONArray().apply { v.forEach { put(it) } }
        is FloatArray -> JSONArray().apply { v.forEach { put(convert(it)) } }
        is DoubleArray -> JSONArray().apply { v.forEach { put(convert(it)) } }
        is BooleanArray -> JSONArray().apply { v.forEach { put(it) } }
        is ByteArray -> JSONArray().apply { v.forEach { put(it.toInt()) } }
        is ShortArray -> JSONArray().apply { v.forEach { put(it.toInt()) } }
        is Array<*> -> JSONArray().apply { v.forEach { put(convert(it)) } }
        is Collection<*> -> JSONArray().apply { v.forEach { put(convert(it)) } }
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, value) -> put(k.toString(), convert(value)) } }
        is Size -> "${v.width}x${v.height}"
        is SizeF -> JSONObject().put("width", v.width.toDouble()).put("height", v.height.toDouble())
        is Rect -> JSONObject().put("left", v.left).put("top", v.top).put("right", v.right).put("bottom", v.bottom)
        is Point -> JSONArray().put(v.x).put(v.y)
        is Range<*> -> JSONArray().put(convert(v.lower)).put(convert(v.upper))
        is Rational -> "${v.numerator}/${v.denominator}"
        is StreamConfigurationMap -> streamMap(v)
        is ColorSpaceTransform -> JSONArray().apply {
            for (row in 0 until 3) {
                put(JSONArray().apply { for (col in 0 until 3) put(convert(v.getElement(col, row))) })
            }
        }
        is RggbChannelVector -> JSONArray().put(v.red.toDouble()).put(v.greenEven.toDouble())
            .put(v.greenOdd.toDouble()).put(v.blue.toDouble())
        is BlackLevelPattern -> JSONArray().apply {
            for (row in 0 until 2) for (col in 0 until 2) put(v.getOffsetForIndex(col, row))
        }
        is Capability -> JSONObject()
            .put("mode", v.mode)
            .put("maxStreamingSize", convert(v.maxStreamingSize))
            .put("zoomRatioRange", convert(v.zoomRatioRange))
        is MandatoryStreamCombination -> JSONObject()
            .put("description", v.description.toString())
            .put("reprocessable", v.isReprocessable)
            .put("streams", JSONArray().apply {
                v.streamsInformation.forEach { s ->
                    val o = JSONObject()
                        .put("format", CameraNames.formatName(s.format))
                        .put("input", s.isInput)
                        .put("sizes", convert(s.availableSizes))
                    if (Build.VERSION.SDK_INT >= 33) o.put("streamUseCase", s.streamUseCase)
                    put(o)
                }
            })
        is android.util.Pair<*, *> -> JSONArray().put(convert(v.first)).put(convert(v.second))
        else -> dynamicRange(v) ?: v.toString()
    }

    private fun dynamicRange(v: Any): Any? =
        if (Build.VERSION.SDK_INT >= 33 && v is DynamicRangeProfiles) {
            JSONArray().apply { v.supportedProfiles.sorted().forEach { put(it) } }
        } else {
            null
        }

    /** Output sizes, minimum frame durations and stall durations for every format the report cares about. */
    fun streamMap(map: StreamConfigurationMap): JSONObject {
        val out = JSONObject()
        val outputFormats = map.outputFormats.toList()
        out.put("outputFormats", JSONArray(outputFormats.map { CameraNames.formatName(it) }))
        out.put("inputFormats", JSONArray(map.inputFormats.map { CameraNames.formatName(it) }))
        val formats = JSONObject()
        val allFormats = (CameraNames.formats.keys + outputFormats).distinct()
        for (format in allFormats) {
            val f = JSONObject()
            val supported = outputFormats.contains(format)
            f.put("supported", supported)
            if (supported) {
                f.put("sizes", sizesWithDurations(map, format, map.getOutputSizes(format)))
                map.getHighResolutionOutputSizes(format)?.takeIf { it.isNotEmpty() }?.let {
                    f.put("highResolutionSizes", sizesWithDurations(map, format, it))
                }
            }
            formats.put(CameraNames.formatName(format), f)
        }
        out.put("formats", formats)
        out.put(
            "surfaceTextureSizes",
            convert(map.getOutputSizes(android.graphics.SurfaceTexture::class.java)),
        )
        out.put("highSpeedVideoSizes", convert(map.highSpeedVideoSizes))
        out.put("highSpeedVideoFpsRanges", convert(map.highSpeedVideoFpsRanges))
        return out
    }

    private fun sizesWithDurations(map: StreamConfigurationMap, format: Int, sizes: Array<Size>?): JSONArray {
        val arr = JSONArray()
        sizes?.sortedByDescending { it.width.toLong() * it.height }?.forEach { size ->
            val o = JSONObject().put("size", "${size.width}x${size.height}")
            try {
                val minFrame = map.getOutputMinFrameDuration(format, size)
                o.put("minFrameDurationNs", minFrame)
                if (minFrame > 0) o.put("maxFps", Math.round(1e11 / minFrame) / 100.0)
                o.put("stallDurationNs", map.getOutputStallDuration(format, size))
            } catch (e: Exception) {
                o.put("durationError", e.toString())
            }
            arr.put(o)
        }
        return arr
    }
}
