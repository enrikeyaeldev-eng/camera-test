// Phase 0: a trivial JNI bridge that proves native code builds, loads and runs.
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <string>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#define LOG_TAG "A56Cam"

extern "C" JNIEXPORT jstring JNICALL
Java_com_a56cam_processing_NativeBridge_nativeInfo(JNIEnv* env, jobject /* this */) {
    std::string info = "a56processing";
#if defined(__aarch64__)
    info += " abi=arm64-v8a";
#else
    info += " abi=other";
#endif
#if defined(__ARM_NEON)
    info += " neon=yes";
#else
    info += " neon=no";
#endif
    info += " clang=" __clang_version__;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "native loaded: %s", info.c_str());
    return env->NewStringUTF(info.c_str());
}

// Sums a byte array: a real (if tiny) native computation, vectorised with NEON when available.
extern "C" JNIEXPORT jlong JNICALL
Java_com_a56cam_processing_NativeBridge_nativeSum(JNIEnv* env, jobject /* this */, jbyteArray data) {
    const jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    const auto* p = reinterpret_cast<const uint8_t*>(bytes);
    uint64_t total = 0;
    jsize i = 0;
#if defined(__ARM_NEON)
    uint32x4_t acc = vdupq_n_u32(0);
    for (; i + 16 <= len; i += 16) {
        uint16x8_t s16 = vpaddlq_u8(vld1q_u8(p + i));
        acc = vpadalq_u16(acc, s16);
    }
    total += vaddvq_u32(acc);
#endif
    for (; i < len; ++i) total += p[i];
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return static_cast<jlong>(total);
}
