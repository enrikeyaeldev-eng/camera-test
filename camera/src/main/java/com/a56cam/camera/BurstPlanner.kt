package com.a56cam.camera

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns the current auto exposure into the per-frame manual exposures of a burst. Pure arithmetic,
 * unit-tested. Exposure "product" = exposure time × ISO, proportional to image brightness.
 */
object BurstPlanner {

    enum class Kind { STACK, NIGHT, HDR }

    data class Limits(val exposureMinNs: Long, val exposureMaxNs: Long, val isoMin: Int, val isoMax: Int)

    data class Frame(val exposureNs: Long, val iso: Int) {
        val product: Double get() = exposureNs.toDouble() * iso
    }

    data class Plan(
        val kind: Kind,
        val frames: List<Frame>,
        /** Linear gain applied after merging to restore the auto-exposure brightness. */
        val gain: Float,
        /** Frames less sharp than this fraction of the sharpest are dropped (night). */
        val minSharpness: Float,
    )

    /** Night exposures never go above this, so hand-held frames stay mostly sharp. */
    const val NIGHT_MAX_EXPOSURE_NS = 100_000_000L

    /**
     * Night lengthens the exposure only until the ISO is down to this; below it the extra noise saving is not
     * worth the hand-shake blur. (A bright room picked 88 ms at ISO 40 and 6 of 16 selfie frames blurred.)
     */
    const val NIGHT_TARGET_ISO = 200

    fun plan(kind: Kind, autoExposureNs: Long, autoIso: Int, limits: Limits, frameCount: Int): Plan {
        val auto = autoExposureNs.toDouble() * autoIso
        return when (kind) {
            // Same brightness for every frame, 1 EV under auto to protect highlights; the merge removes the noise.
            Kind.STACK -> {
                val f = frameFor(auto * 0.5, autoExposureNs * 0.5, limits)
                Plan(kind, List(frameCount) { f }, gain(auto, f), minSharpness = 0f)
            }
            // Half a stop under auto, with the exposure lengthened (up to 100 ms) only as far as needed to bring the
            // ISO down to NIGHT_TARGET_ISO; never shorter than auto.
            Kind.NIGHT -> {
                val target = auto * 0.7071
                val longest = minOf(limits.exposureMaxNs, NIGHT_MAX_EXPOSURE_NS).toDouble()
                val wanted = (target / NIGHT_TARGET_ISO).coerceAtMost(longest)
                val f = frameFor(target, maxOf(autoExposureNs.toDouble(), wanted), limits)
                Plan(kind, List(frameCount) { f }, gain(auto, f), minSharpness = 0.7f)
            }
            // HDR+ style: identical frames 2 EV under auto so highlights are not clipped; merging them removes the
            // noise and the tone curve then lifts the shadows by 4×. No mixed exposures, so no halos or ghosts.
            Kind.HDR -> {
                val f = frameFor(auto * 0.25, autoExposureNs * 0.25, limits)
                Plan(kind, List(frameCount) { f }, gain(auto, f), minSharpness = 0f)
            }
        }
    }

    /** A frame with brightness [product], using [preferredExposureNs] if the ISO range allows it. */
    private fun frameFor(product: Double, preferredExposureNs: Double, l: Limits): Frame {
        var exposure = preferredExposureNs.coerceIn(l.exposureMinNs.toDouble(), l.exposureMaxNs.toDouble())
        var iso = (product / exposure).roundToInt()
        if (iso < l.isoMin || iso > l.isoMax) {
            iso = iso.coerceIn(l.isoMin, l.isoMax)
            exposure = (product / iso).coerceIn(l.exposureMinNs.toDouble(), l.exposureMaxNs.toDouble())
        }
        return Frame(exposure.toLong(), iso)
    }

    private fun gain(auto: Double, f: Frame): Float = (auto / f.product).toFloat().coerceIn(1f, 4f)
}
