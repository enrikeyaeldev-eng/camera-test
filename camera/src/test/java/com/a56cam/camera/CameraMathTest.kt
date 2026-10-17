package com.a56cam.camera

import com.a56cam.camera.CameraMath.Dim
import com.a56cam.camera.CameraMath.Point
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraMathTest {

    @Test
    fun jpegOrientation_backCameraSensor90() {
        assertEquals(90, CameraMath.jpegOrientation(90, 0, frontFacing = false))
        assertEquals(180, CameraMath.jpegOrientation(90, 90, frontFacing = false))
        assertEquals(0, CameraMath.jpegOrientation(90, 270, frontFacing = false))
        assertEquals(90, CameraMath.jpegOrientation(90, 350, frontFacing = false))
    }

    @Test
    fun jpegOrientation_frontCameraSensor270() {
        assertEquals(270, CameraMath.jpegOrientation(270, 0, frontFacing = true))
        assertEquals(180, CameraMath.jpegOrientation(270, 90, frontFacing = true))
        assertEquals(0, CameraMath.jpegOrientation(270, 270, frontFacing = true))
    }

    @Test
    fun viewToSensor_back90_mapsCornersAndCentre() {
        // Top-left of a portrait preview is the bottom-left... of the landscape sensor image.
        assertEquals(Point(0f, 1f), CameraMath.viewToSensor(Point(0f, 0f), 90, false))
        assertEquals(Point(1f, 1f), CameraMath.viewToSensor(Point(0f, 1f), 90, false))
        assertEquals(Point(0.5f, 0.5f), CameraMath.viewToSensor(Point(0.5f, 0.5f), 90, false))
    }

    @Test
    fun viewToSensor_front270_isMirrored() {
        assertEquals(Point(1f, 1f), CameraMath.viewToSensor(Point(0f, 0f), 270, true))
        assertEquals(Point(0f, 1f), CameraMath.viewToSensor(Point(0f, 1f), 270, true))
    }

    @Test
    fun meteringRect_staysInsideArray() {
        assertArrayEquals(intArrayOf(0, 0, 306, 306), CameraMath.meteringRect(Point(0f, 0f), 4080, 3060))
        assertArrayEquals(intArrayOf(3774, 2754, 306, 306), CameraMath.meteringRect(Point(1f, 1f), 4080, 3060))
        assertArrayEquals(intArrayOf(1887, 1377, 306, 306), CameraMath.meteringRect(Point(0.5f, 0.5f), 4080, 3060))
    }

    @Test
    fun choosePreviewSize_prefersLargestMatchingAspect() {
        val sizes = listOf(Dim(4080, 3060), Dim(1920, 1080), Dim(1440, 1080), Dim(1280, 960), Dim(640, 480))
        assertEquals(Dim(1440, 1080), CameraMath.choosePreviewSize(sizes, Dim(4080, 3060)))
        assertEquals(Dim(1920, 1080), CameraMath.choosePreviewSize(sizes, Dim(1920, 1080)))
    }

    @Test
    fun zoomLabels_forA56Cameras() {
        val main = CameraMath.equivalentFocalLength(5.54f, 8.16f, 6.12f)
        val ultra = CameraMath.equivalentFocalLength(1.74f, 4.48f, 3.36f)
        assertEquals("1x", CameraMath.zoomLabel(main, main))
        assertEquals("0.6x", CameraMath.zoomLabel(ultra, main))
    }
}
