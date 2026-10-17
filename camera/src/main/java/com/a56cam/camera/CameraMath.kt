package com.a56cam.camera

import kotlin.math.abs
import kotlin.math.sqrt

/** Plain-number camera geometry, free of Android types so it is unit-tested on the JVM. */
object CameraMath {

    /** A width × height pair; [Size] equivalent that works in JVM tests. */
    data class Dim(val width: Int, val height: Int) {
        val area: Long get() = width.toLong() * height
        val aspect: Double get() = width.toDouble() / height
    }

    /** A point normalised to 0..1 in both axes. */
    data class Point(val x: Float, val y: Float)

    /** Rounds a raw OrientationEventListener angle to 0, 90, 180 or 270. */
    fun snapOrientation(degrees: Int): Int = (((degrees + 45) / 90) * 90) % 360

    /**
     * Value for CaptureRequest.JPEG_ORIENTATION so the saved photo is upright,
     * following the formula in the CaptureRequest documentation.
     */
    fun jpegOrientation(sensorOrientation: Int, deviceOrientation: Int, frontFacing: Boolean): Int {
        val device = snapOrientation(deviceOrientation)
        val signed = if (frontFacing) -device else device
        return (sensorOrientation + signed + 360) % 360
    }

    /**
     * Maps a tap on a portrait preview (0..1, origin top-left) to the same point on the sensor
     * (0..1, origin top-left of the active array). Front previews are mirrored on screen.
     */
    fun viewToSensor(view: Point, sensorOrientation: Int, frontFacing: Boolean): Point {
        val x = if (frontFacing) 1f - view.x else view.x
        val y = view.y
        return when (((sensorOrientation % 360) + 360) % 360) {
            90 -> Point(y, 1f - x)
            180 -> Point(1f - x, 1f - y)
            270 -> Point(1f - y, x)
            else -> Point(x, y)
        }
    }

    /** A square metering region of [fraction] of the shorter side, centred on [center] and kept inside the array. */
    fun meteringRect(center: Point, arrayWidth: Int, arrayHeight: Int, fraction: Float = 0.1f): IntArray {
        val half = (minOf(arrayWidth, arrayHeight) * fraction / 2).toInt().coerceAtLeast(1)
        val cx = (center.x.coerceIn(0f, 1f) * arrayWidth).toInt()
        val cy = (center.y.coerceIn(0f, 1f) * arrayHeight).toInt()
        val left = (cx - half).coerceIn(0, arrayWidth - 2 * half)
        val top = (cy - half).coerceIn(0, arrayHeight - 2 * half)
        return intArrayOf(left, top, 2 * half, 2 * half)
    }

    /**
     * Largest size with the same aspect ratio as [target] that fits inside [maxLong] × [maxShort];
     * falls back to the closest aspect ratio, then to the smallest size.
     */
    fun choosePreviewSize(sizes: List<Dim>, target: Dim, maxLong: Int = 1920, maxShort: Int = 1440): Dim? {
        if (sizes.isEmpty()) return null
        val fitting = sizes.filter { maxOf(it.width, it.height) <= maxLong && minOf(it.width, it.height) <= maxShort }
        val sameAspect = fitting.filter { abs(it.aspect - target.aspect) < 0.01 }
        return sameAspect.maxByOrNull { it.area }
            ?: fitting.minByOrNull { abs(it.aspect - target.aspect) * 1e6 - it.area }
            ?: sizes.minByOrNull { it.area }
    }

    /** 35 mm-equivalent focal length from the real focal length and sensor size in millimetres. */
    fun equivalentFocalLength(focalMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): Float {
        val diagonal = sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)
        return if (diagonal > 0f) focalMm * 43.27f / diagonal else focalMm
    }

    /** Zoom label relative to the main camera, e.g. "0.6x", "1x", "1.2x". */
    fun zoomLabel(equivalent: Float, mainEquivalent: Float): String {
        if (mainEquivalent <= 0f) return "1x"
        val ratio = Math.round(equivalent / mainEquivalent * 10) / 10f
        return if (ratio == ratio.toInt().toFloat()) "${ratio.toInt()}x" else "${ratio}x"
    }
}
