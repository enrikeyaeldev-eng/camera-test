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
    fun hdr_bracketsMinusTwoZeroPlusOneAndHalf() {
        val p = BurstPlanner.plan(Kind.HDR, 10_000_000, 200, a56, 3)
        val products = p.frames.map { it.product / (10_000_000.0 * 200) }
        assertEquals(0.25, products[0], 0.01)
        assertEquals(1.0, products[1], 0.01)
        assertEquals(2.83, products[2], 0.03)
        assertEquals(1f, p.gain, 0f)
    }

    @Test
    fun hdr_raisesIsoWhenShutterRunsOut() {
        val p = BurstPlanner.plan(Kind.HDR, 80_000_000, 400, a56, 3)
        val bright = p.frames[2]
        assertEquals(100_000_000L, bright.exposureNs)
        assertTrue("iso ${bright.iso}", bright.iso > 400)
    }
}
