package com.a56cam.camera

/** Human-readable names for Camera2 integer constants, used in reports and logs. */
object CameraNames {
    val hardwareLevel = mapOf(
        0 to "LIMITED", 1 to "FULL", 2 to "LEGACY", 3 to "LEVEL_3", 4 to "EXTERNAL",
    )
    val capability = mapOf(
        0 to "BACKWARD_COMPATIBLE", 1 to "MANUAL_SENSOR", 2 to "MANUAL_POST_PROCESSING", 3 to "RAW",
        4 to "PRIVATE_REPROCESSING", 5 to "READ_SENSOR_SETTINGS", 6 to "BURST_CAPTURE",
        7 to "YUV_REPROCESSING", 8 to "DEPTH_OUTPUT", 9 to "CONSTRAINED_HIGH_SPEED_VIDEO",
        10 to "MOTION_TRACKING", 11 to "LOGICAL_MULTI_CAMERA", 12 to "MONOCHROME",
        13 to "SECURE_IMAGE_DATA", 14 to "SYSTEM_CAMERA", 15 to "OFFLINE_PROCESSING",
        16 to "ULTRA_HIGH_RESOLUTION_SENSOR", 17 to "REMOSAIC_REPROCESSING",
        18 to "DYNAMIC_RANGE_TEN_BIT", 19 to "STREAM_USE_CASE", 20 to "COLOR_SPACE_PROFILES",
    )
    val aeMode = mapOf(
        0 to "OFF", 1 to "ON", 2 to "ON_AUTO_FLASH", 3 to "ON_ALWAYS_FLASH", 4 to "ON_AUTO_FLASH_REDEYE",
        5 to "ON_EXTERNAL_FLASH", 6 to "ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY",
    )
    val afMode = mapOf(
        0 to "OFF", 1 to "AUTO", 2 to "MACRO", 3 to "CONTINUOUS_VIDEO", 4 to "CONTINUOUS_PICTURE", 5 to "EDOF",
    )
    val awbMode = mapOf(
        0 to "OFF", 1 to "AUTO", 2 to "INCANDESCENT", 3 to "FLUORESCENT", 4 to "WARM_FLUORESCENT",
        5 to "DAYLIGHT", 6 to "CLOUDY_DAYLIGHT", 7 to "TWILIGHT", 8 to "SHADE",
    )
    val noiseReduction = mapOf(
        0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "MINIMAL", 4 to "ZERO_SHUTTER_LAG",
    )
    val edge = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "ZERO_SHUTTER_LAG")
    val tonemap = mapOf(0 to "CONTRAST_CURVE", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "GAMMA_VALUE", 4 to "PRESET_CURVE")
    val hotPixel = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY")
    val lensShading = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY")
    val aeState = mapOf(
        0 to "INACTIVE", 1 to "SEARCHING", 2 to "CONVERGED", 3 to "LOCKED", 4 to "FLASH_REQUIRED", 5 to "PRECAPTURE",
    )
    val lensFacing = mapOf(0 to "FRONT", 1 to "BACK", 2 to "EXTERNAL")
    val extension = mapOf(
        0 to "AUTOMATIC", 1 to "FACE_RETOUCH", 2 to "BOKEH", 3 to "HDR", 4 to "NIGHT", 5 to "EYES_FREE_VIDEOGRAPHY",
    )

    /** Image formats the report checks; JPEG_R and HEIC use literals so they work below their API level. */
    val formats = linkedMapOf(
        0x23 to "YUV_420_888",
        0x100 to "JPEG",
        0x22 to "PRIVATE",
        0x48454946 to "HEIC",
        0x1005 to "JPEG_R",
        0x20 to "RAW_SENSOR",
        0x24 to "RAW_PRIVATE",
        0x25 to "RAW10",
        0x26 to "RAW12",
        0x20203859 to "Y8",
        0x36 to "YCBCR_P010",
        0x44363159 to "DEPTH16",
        0x101 to "DEPTH_POINT_CLOUD",
        0x69656963 to "DEPTH_JPEG",
    )

    fun name(map: Map<Int, String>, value: Int?): String =
        if (value == null) "null" else map[value] ?: "UNKNOWN($value)"

    fun formatName(format: Int): String = formats[format] ?: "0x" + Integer.toHexString(format)
}
