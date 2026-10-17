// JNI bridge for the burst stacking core (com.a56cam.processing.StackNative).
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <cstdio>
#include <memory>
#include <string>

#include "stack.h"

#define LOG_TAG "A56Cam"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Handle {
    a56::Stack stack;
    a56::StackStats stats;
    Handle(int w, int h, int capacity) : stack(w, h, capacity) {}
};

Handle* from(jlong h) { return reinterpret_cast<Handle*>(h); }

// True when a plane of the given geometry fits inside the direct buffer.
bool fits(JNIEnv* env, jobject buf, int rows, int cols, int rowStride, int pixelStride) {
    const jlong cap = env->GetDirectBufferCapacity(buf);
    const jlong need = static_cast<jlong>(rows - 1) * rowStride + static_cast<jlong>(cols - 1) * pixelStride + 1;
    return cap >= need;
}

std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

a56::RenderParams params(jfloat gain, jfloat sharpen, jint rotation) {
    a56::RenderParams p;
    p.gain = gain;
    p.sharpen = sharpen;
    p.rotation = rotation;
    return p;
}

template <typename T>
void appendArray(std::string& out, const char* name, const std::vector<T>& v) {
    out += "\"";
    out += name;
    out += "\":[";
    for (size_t i = 0; i < v.size(); ++i) {
        if (i) out += ",";
        char buf[32];
        std::snprintf(buf, sizeof buf, "%g", static_cast<double>(v[i]));
        out += buf;
    }
    out += "]";
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_a56cam_processing_StackNative_create(JNIEnv*, jobject, jint w, jint h, jint capacity) {
    try {
        return reinterpret_cast<jlong>(new Handle(w, h, capacity));
    } catch (const std::exception& e) {
        LOGE("Stack create failed: %s", e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_a56cam_processing_StackNative_destroy(JNIEnv*, jobject, jlong h) {
    delete from(h);
}

JNIEXPORT jint JNICALL
Java_com_a56cam_processing_StackNative_count(JNIEnv*, jobject, jlong h) {
    return from(h)->stack.count();
}

JNIEXPORT jboolean JNICALL
Java_com_a56cam_processing_StackNative_addFrame(JNIEnv* env, jobject, jlong h, jobject yBuf, jint yRowStride,
                                                jint yPixelStride, jobject uBuf, jobject vBuf, jint uvRowStride,
                                                jint uvPixelStride, jlong timestamp, jdouble exposure) {
    Handle* hd = from(h);
    const int w = hd->stack.width(), ht = hd->stack.height();
    auto* y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    auto* u = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuf));
    auto* v = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!y || !u || !v || !fits(env, yBuf, ht, w, yRowStride, yPixelStride) ||
        !fits(env, uBuf, ht / 2, w / 2, uvRowStride, uvPixelStride) ||
        !fits(env, vBuf, ht / 2, w / 2, uvRowStride, uvPixelStride)) {
        LOGE("addFrame: buffers do not match %dx%d", w, ht);
        return JNI_FALSE;
    }
    try {
        return hd->stack.addFrame(y, yRowStride, yPixelStride, u, v, uvRowStride, uvPixelStride, timestamp, exposure)
                   ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("addFrame failed: %s", e.what());
        return JNI_FALSE;
    }
}

// Returns stats as JSON, or null on failure.
JNIEXPORT jstring JNICALL
Java_com_a56cam_processing_StackNative_process(JNIEnv* env, jobject, jlong h, jfloat minSharpness, jboolean hdr) {
    Handle* hd = from(h);
    try {
        a56::ProcessParams p;
        p.minSharpness = minSharpness;
        p.hdr = hdr == JNI_TRUE;
        hd->stats = hd->stack.process(p);
    } catch (const std::exception& e) {
        LOGE("process failed: %s", e.what());
        return nullptr;
    }
    const a56::StackStats& s = hd->stats;
    char head[256];
    std::snprintf(head, sizeof head, "{\"frames\":%d,\"reference\":%d,\"alignMs\":%.0f,\"mergeMs\":%.0f,\"tilesX\":%d,\"tilesY\":%d,",
                  s.frames, s.reference, s.alignMs, s.mergeMs, s.tilesX, s.tilesY);
    std::string out(head);
    appendArray(out, "sharpness", s.sharpness);
    out += ",";
    appendArray(out, "noise", s.noise);
    out += ",";
    appendArray(out, "medianDx", s.medianDx);
    out += ",";
    appendArray(out, "medianDy", s.medianDy);
    out += ",";
    appendArray(out, "rejected", s.rejected);
    out += "}";
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jfloatArray JNICALL
Java_com_a56cam_processing_StackNative_tileWeights(JNIEnv* env, jobject, jlong h) {
    const std::vector<float>& w = from(h)->stats.tileWeight;
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(w.size()));
    if (arr && !w.empty()) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(w.size()), w.data());
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_com_a56cam_processing_StackNative_outputSize(JNIEnv* env, jobject, jlong h, jint rotation) {
    int w, ht;
    from(h)->stack.outputSize(rotation, &w, &ht);
    jint vals[2] = {w, ht};
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, vals);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_a56cam_processing_StackNative_renderBitmap(JNIEnv* env, jobject, jlong h, jobject bitmap, jfloat gain,
                                                    jfloat sharpen, jint rotation) {
    Handle* hd = from(h);
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("renderBitmap: need an ARGB_8888 bitmap");
        return JNI_FALSE;
    }
    int w, ht;
    hd->stack.outputSize(rotation, &w, &ht);
    if (static_cast<int>(info.width) != w || static_cast<int>(info.height) != ht) {
        LOGE("renderBitmap: bitmap %ux%u, expected %dx%d", info.width, info.height, w, ht);
        return JNI_FALSE;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    hd->stack.renderRgba8(static_cast<uint8_t*>(pixels), static_cast<int>(info.stride), params(gain, sharpen, rotation));
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_a56cam_processing_StackNative_writeTiff(JNIEnv* env, jobject, jlong h, jstring path, jfloat gain,
                                                 jfloat sharpen, jint rotation) {
    return from(h)->stack.writeTiff16(str(env, path), params(gain, sharpen, rotation)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_a56cam_processing_StackNative_writeDng(JNIEnv* env, jobject, jlong h, jstring path, jfloat gain,
                                                jint rotation, jstring make, jstring model) {
    return from(h)->stack.writeLinearDng(str(env, path), params(gain, 0.f, rotation), str(env, make), str(env, model))
               ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_a56cam_processing_StackNative_frameNv21(JNIEnv* env, jobject, jlong h, jint index) {
    const std::vector<uint8_t> data = from(h)->stack.frameNv21(index);
    if (data.empty()) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(data.size()));
    if (arr) env->SetByteArrayRegion(arr, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte*>(data.data()));
    return arr;
}

}  // extern "C"
