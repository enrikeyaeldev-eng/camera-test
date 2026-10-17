package com.a56cam.processing

/** JNI entry point into the C++ processing library. */
object NativeBridge {
    init {
        System.loadLibrary("a56processing")
    }

    external fun nativeInfo(): String

    external fun nativeSum(data: ByteArray): Long

    /** Runs both native calls and checks the result, proving native code works end to end. */
    fun selfTest(): String {
        val data = ByteArray(1000) { (it % 256).toByte() }
        val expected = data.sumOf { it.toInt() and 0xFF }.toLong()
        val actual = nativeSum(data)
        val ok = if (actual == expected) "ok" else "MISMATCH $actual != $expected"
        return "${nativeInfo()} sum=$ok"
    }
}
