package com.a56cam.camera

import com.a56cam.camera.BurstPlanner.Kind
import com.a56cam.camera.BurstPlanner.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstPlannerTest {
    // Back main camera from the Phase 0 report.
    private val a56 = Limits(exposureMinNs = 34_700, exposureMaxNs = 100_000_000, isoMin = 50, isoMax = 2500)

    @Test
    fun stack_isOneStopUnderWithGainTwo() {
        val p = BurstPlanner.plan(Kind.STACK, 33_320_000, 538, a56, 8)
        assertEquals(8, p.frames.size)
        assertEquals(16_660_000L, p.frames[0].exposureNs)
        assertEquals(538, p.frames[0].iso)
        assertEquals(2f, p.gain, 0.01f)
    }

    @Test
    fun night_usesLongExposureAndLowerIso() {
        val p = BurstPlanner.plan(Kind.NIGHT, 33_320_000, 2500, a56, 12)
        val f = p.frames[0]
        assertEquals(100_000_000L, f.exposureNs)
        // 33.3 ms × 2500 × 0.7071 / 100 ms ≈ 589
        assertTrue("iso ${f.iso}", f.iso in 585..592)
        assertEquals(1.414f, p.gain, 0.01f)
        assertEquals(0.7f, p.minSharpness, 0f)
    }

    @Test
    fun night_inBrightLightKeepsIsoAtMinimum() {
        val p = BurstPlanner.plan(Kind.NIGHT, 1_000_000, 50, a56, 12)
        val f = p.frames[0]
        assertEquals(50, f.iso)
        assertTrue("exposure ${f.exposureNs}", f.exposureNs in 700_000L..720_000L)
    }

    @Test
    fun night_inARoomDoesNotUseLongHandheldExposures() {
        // Front camera in a lit bathroom (owner's test): auto 25 ms at ISO 200.
        val p = BurstPlanner.plan(Kind.NIGHT, 24_990_000, 200, a56, 16)
        val f = p.frames[0]
        assertEquals(24_990_000L, f.exposureNs)
        assertTrue("iso ${f.iso}", f.iso in 140..142)
    }

    @Test
    fun night_inDimLightStopsAtTargetIso() {
        // auto 33.3 ms at ISO 800: 0.7071 × 33.3 ms × 800 / 200 ≈ 94 ms at ISO 200.
        val f = BurstPlanner.plan(Kind.NIGHT, 33_320_000, 800, a56, 12).frames[0]
        assertTrue("exposure ${f.exposureNs}", f.exposureNs in 93_000_000L..95_000_000L)
        assertEquals(200, f.iso)
    }

    @Test
    fun hdr_isTwoStopsUnderWithGainFour() {
        val p = BurstPlanner.plan(Kind.HDR, 41_650_000, 1430, a56, 10)
        assertEquals(10, p.frames.size)
        assertEquals(10_412_500L, p.frames[0].exposureNs)
        assertEquals(1430, p.frames[0].iso)
        assertEquals(4f, p.gain, 0.01f)
    }
}
