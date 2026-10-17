// OpenGL ES 3.1 compute implementation of the same-exposure merge. It mirrors Stack::mergeRobust exactly:
// per-tile mean absolute differences against the reference, then a sin²-windowed, weighted sum over the
// overlapping tiles. Frames are uploaded one at a time, so GPU memory stays near 200 MB at 12 MP.
#include "gpu_merge.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>

#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>

namespace a56 {

namespace {

constexpr int kTile = 32, kStep = 16;  // must match kMergeTile / kMergeStep in stack.cpp

// Follows "#version 310 es" and "#define CHROMA 0|1"; luma sums are (value, weight), chroma (V, U, weight).
const char* kHeader = R"(
#if CHROMA
#define ACC vec4
#else
#define ACC vec2
#endif
precision highp float;
precision highp int;
precision highp usampler2D;
layout(location = 0) uniform ivec4 uDims;   // width, height, tiles x, tiles y
layout(location = 1) uniform ivec4 uTile;   // tile size, step, frame offset (in tiles), first frame
float win(int k, int m) { float s = sin(3.14159265358979 * (float(k) + 0.5) / float(m)); return s * s; }
)";

// One invocation per tile: mean |ref - alt| with the alt frame shifted by the tile's luma shift.
const char* kMismatch = R"(
layout(local_size_x = 64) in;
layout(binding = 0) uniform usampler2D refTex;
layout(binding = 1) uniform usampler2D altTex;
layout(std430, binding = 0) readonly buffer Shifts { ivec4 shifts[]; };
layout(std430, binding = 1) writeonly buffer Out { float d[]; };
void main() {
    int t = int(gl_GlobalInvocationID.x);
    int tiles = uDims.z * uDims.w;
    if (t >= tiles) return;
    int tx = t % uDims.z, ty = t / uDims.z;
    ivec2 sh = shifts[uTile.z + t].xy;
    int ox = tx * uTile.y - uTile.y, oy = ty * uTile.y - uTile.y;
    uint sum = 0u;
    int cnt = 0;
    for (int y = max(oy, 0); y < min(oy + uTile.x, uDims.y); ++y) {
        int sy = clamp(y + sh.y, 0, uDims.y - 1);
        for (int x = max(ox, 0); x < min(ox + uTile.x, uDims.x); ++x) {
            int sx = clamp(x + sh.x, 0, uDims.x - 1);
            sum += uint(abs(int(texelFetch(refTex, ivec2(x, y), 0).r) - int(texelFetch(altTex, ivec2(sx, sy), 0).r)));
            ++cnt;
        }
    }
    d[t] = cnt > 0 ? float(sum) / float(cnt) : 0.0;
}
)";

// One invocation per pixel: this frame's contribution from the (up to) 2×2 tiles covering the pixel.
// Luma uses shift.xy and acc = (sum, weight); chroma uses shift.zw and acc = (V sum, U sum, weight).
const char* kAccumulate = R"(
layout(local_size_x = 16, local_size_y = 16) in;
layout(binding = 0) uniform usampler2D src;
layout(std430, binding = 0) readonly buffer Shifts { ivec4 shifts[]; };
layout(std430, binding = 1) readonly buffer Weights { float weights[]; };
layout(std430, binding = 2) buffer Acc { ACC acc[]; };
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= uDims.x || p.y >= uDims.y) return;
    int m = uTile.x, s = uTile.y;
    vec3 a = vec3(0.0);
    for (int j = 0; j < 2; ++j) {
        int ty = p.y / s + j;
        int ky = p.y - (ty * s - s);
        if (ty >= uDims.w || ky < 0 || ky >= m) continue;
        float wy = win(ky, m);
        for (int i = 0; i < 2; ++i) {
            int tx = p.x / s + i;
            int kx = p.x - (tx * s - s);
            if (tx >= uDims.z || kx < 0 || kx >= m) continue;
            int t = uTile.z + ty * uDims.z + tx;
            float wt = weights[t];
            if (wt <= 0.0) continue;
            float ww = wy * wt * win(kx, m);
#if CHROMA
            uvec4 v = texelFetch(src, clamp(p + shifts[t].zw, ivec2(0), uDims.xy - 1), 0);
            a += vec3(ww * float(v.r), ww * float(v.g), ww);
#else
            uvec4 v = texelFetch(src, clamp(p + shifts[t].xy, ivec2(0), uDims.xy - 1), 0);
            a += vec3(ww * float(v.r), ww, 0.0);
#endif
        }
    }
    int idx = p.y * uDims.x + p.x;
#if CHROMA
    acc[idx] = (uTile.w != 0 ? vec4(0.0) : acc[idx]) + vec4(a, 0.0);
#else
    acc[idx] = (uTile.w != 0 ? vec2(0.0) : acc[idx]) + a.xy;
#endif
}
)";

// Divides the sums by the weights: luma 0 where nothing was merged, chroma keeps the sum (CPU behaviour).
const char* kNormalize = R"(
layout(local_size_x = 256) in;
layout(std430, binding = 2) readonly buffer Acc { ACC acc[]; };
layout(std430, binding = 3) writeonly buffer Out { float outv[]; };
void main() {
    int i = int(gl_GlobalInvocationID.x);
    if (i >= uDims.x * uDims.y) return;
    ACC a = acc[i];
#if CHROMA
    float w = a.z > 0.0 ? a.z : 1.0;
    outv[2 * i] = a.x / w;
    outv[2 * i + 1] = a.y / w;
#else
    outv[i] = a.y > 0.0 ? a.x / a.y : 0.0;
#endif
}
)";

}  // namespace

struct GpuMerger::Gl {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    GLuint mismatch = 0, accLuma = 0, accChroma = 0, normLuma = 0, normChroma = 0;
    GLuint refTex = 0, lumaTex = 0, chromaTex = 0;
    GLuint shifts = 0, weights = 0, out = 0, accY = 0, accC = 0, normY = 0, normC = 0;
    GLint maxTexture = 0, maxBlock = 0;
};

namespace {

GLuint compile(const std::string& body, bool chroma, std::string* log) {
    const std::string src = std::string("#version 310 es\n#define CHROMA ") + (chroma ? "1" : "0") + kHeader + body;
    GLuint sh = glCreateShader(GL_COMPUTE_SHADER);
    const char* c = src.c_str();
    glShaderSource(sh, 1, &c, nullptr);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[1024] = {0};
        glGetShaderInfoLog(sh, sizeof buf, nullptr, buf);
        *log = std::string("shader: ") + buf;
        glDeleteShader(sh);
        return 0;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, sh);
    glLinkProgram(prog);
    glDeleteShader(sh);
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char buf[1024] = {0};
        glGetProgramInfoLog(prog, sizeof buf, nullptr, buf);
        *log = std::string("link: ") + buf;
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

GLuint makeTexture(GLenum format, int w, int h) {
    GLuint t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexStorage2D(GL_TEXTURE_2D, 1, format, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    return t;
}

GLuint makeBuffer(size_t bytes, const void* data = nullptr) {
    GLuint b = 0;
    glGenBuffers(1, &b);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, b);
    glBufferData(GL_SHADER_STORAGE_BUFFER, static_cast<GLsizeiptr>(bytes), data, data ? GL_STATIC_DRAW : GL_DYNAMIC_COPY);
    return b;
}

void deleteBuffer(GLuint& b) {
    if (b) glDeleteBuffers(1, &b);
    b = 0;
}

void deleteTexture(GLuint& t) {
    if (t) glDeleteTextures(1, &t);
    t = 0;
}

bool readBuffer(GLuint b, size_t bytes, void* dst) {
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, b);
    void* p = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, static_cast<GLsizeiptr>(bytes), GL_MAP_READ_BIT);
    if (!p) return false;
    std::memcpy(dst, p, bytes);
    return glUnmapBuffer(GL_SHADER_STORAGE_BUFFER) == GL_TRUE;
}

GLuint groups(int n, int size) { return static_cast<GLuint>((n + size - 1) / size); }

}  // namespace

std::unique_ptr<GpuMerger> GpuMerger::create(std::string* error) {
    std::unique_ptr<GpuMerger> g(new GpuMerger());
    g->gl_ = std::make_unique<Gl>();
    Gl& gl = *g->gl_;
    gl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (gl.display == EGL_NO_DISPLAY || !eglInitialize(gl.display, nullptr, nullptr)) {
        *error = "no EGL display";
        return nullptr;
    }
    const EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig cfg;
    EGLint count = 0;
    if (!eglChooseConfig(gl.display, cfgAttr, &cfg, 1, &count) || count < 1) {
        *error = "no ES 3 EGL config";
        return nullptr;
    }
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint ctxAttr[] = {EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 1, EGL_NONE};
    gl.context = eglCreateContext(gl.display, cfg, EGL_NO_CONTEXT, ctxAttr);
    const EGLint pbAttr[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    gl.surface = eglCreatePbufferSurface(gl.display, cfg, pbAttr);
    if (gl.context == EGL_NO_CONTEXT || gl.surface == EGL_NO_SURFACE ||
        !eglMakeCurrent(gl.display, gl.surface, gl.surface, gl.context)) {
        *error = "no OpenGL ES 3.1 context";
        return nullptr;
    }
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    g->renderer_ = std::string(renderer ? renderer : "?") + " / " + (version ? version : "?");
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &gl.maxTexture);
    glGetIntegerv(GL_MAX_SHADER_STORAGE_BLOCK_SIZE, &gl.maxBlock);
    std::string log;
    gl.mismatch = compile(kMismatch, false, &log);
    if (gl.mismatch) gl.accLuma = compile(kAccumulate, false, &log);
    if (gl.accLuma) gl.accChroma = compile(kAccumulate, true, &log);
    if (gl.accChroma) gl.normLuma = compile(kNormalize, false, &log);
    if (gl.normLuma) gl.normChroma = compile(kNormalize, true, &log);
    eglMakeCurrent(gl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (!gl.normChroma) {
        *error = log;
        return nullptr;
    }
    return g;
}

GpuMerger::~GpuMerger() {
    if (!gl_ || gl_->display == EGL_NO_DISPLAY) return;
    Gl& gl = *gl_;
    if (gl.context != EGL_NO_CONTEXT && eglMakeCurrent(gl.display, gl.surface, gl.surface, gl.context)) {
        for (GLuint p : {gl.mismatch, gl.accLuma, gl.accChroma, gl.normLuma, gl.normChroma}) {
            if (p) glDeleteProgram(p);
        }
        eglMakeCurrent(gl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (gl.surface != EGL_NO_SURFACE) eglDestroySurface(gl.display, gl.surface);
    if (gl.context != EGL_NO_CONTEXT) eglDestroyContext(gl.display, gl.context);
}

bool GpuMerger::fail(const char* what) {
    char buf[96];
    std::snprintf(buf, sizeof buf, "%s (GL error 0x%x)", what, glGetError());
    error_ = buf;
    return false;
}

bool GpuMerger::begin(const MergeJob& job) {
    mutex_.lock();  // released in end(), which the merge always calls
    job_ = &job;
    error_.clear();
    Gl& gl = *gl_;
    const size_t pixels = static_cast<size_t>(job.W) * job.H;
    // Luma sums take 8 bytes per pixel (100 MB at 12.5 MP); ES 3.1 guarantees blocks of at least 128 MB.
    if (pixels * 8 > static_cast<size_t>(gl.maxBlock)) return fail("image too large for one GPU buffer");
    if (job.W > gl.maxTexture || job.H > gl.maxTexture) return fail("image larger than the GPU texture limit");
    if (!eglMakeCurrent(gl.display, gl.surface, gl.surface, gl.context)) return fail("eglMakeCurrent failed");
    active_ = true;
    while (glGetError() != GL_NO_ERROR) {
    }
    const size_t tiles = static_cast<size_t>(job.mtx) * job.mty;
    std::vector<int32_t> all(static_cast<size_t>(job.n) * tiles * 4);
    for (int i = 0; i < job.n; ++i) std::memcpy(all.data() + i * tiles * 4, job.shifts[i].data(), tiles * 4 * sizeof(int32_t));
    gl.shifts = makeBuffer(all.size() * sizeof(int32_t), all.data());
    gl.out = makeBuffer(tiles * sizeof(float));
    gl.refTex = makeTexture(GL_R8UI, job.W, job.H);
    gl.lumaTex = makeTexture(GL_R8UI, job.W, job.H);
    gl.chromaTex = makeTexture(GL_RG8UI, job.CW, job.CH);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glBindTexture(GL_TEXTURE_2D, gl.refTex);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, job.W, job.H, GL_RED_INTEGER, GL_UNSIGNED_BYTE, job.y[job.ref]);
    return glGetError() == GL_NO_ERROR || fail("GPU setup failed");
}

bool GpuMerger::uploadFrame(int frame, bool chroma) {
    const MergeJob& job = *job_;
    if (chroma) {
        // NV21 stores V,U pairs: texel r = V, g = U.
        glBindTexture(GL_TEXTURE_2D, gl_->chromaTex);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, job.CW, job.CH, GL_RG_INTEGER, GL_UNSIGNED_BYTE, job.vu[frame]);
    } else {
        glBindTexture(GL_TEXTURE_2D, gl_->lumaTex);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, job.W, job.H, GL_RED_INTEGER, GL_UNSIGNED_BYTE, job.y[frame]);
    }
    return glGetError() == GL_NO_ERROR;
}

bool GpuMerger::tileMismatch(int frame, float* out) {
    if (!active_) return false;
    const MergeJob& job = *job_;
    Gl& gl = *gl_;
    const int tiles = job.mtx * job.mty;
    if (!uploadFrame(frame, false)) return fail("frame upload failed");
    glUseProgram(gl.mismatch);
    glUniform4i(0, job.W, job.H, job.mtx, job.mty);
    glUniform4i(1, kTile, kStep, frame * tiles, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, gl.refTex);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, gl.lumaTex);
    glActiveTexture(GL_TEXTURE0);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, gl.shifts);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, gl.out);
    glDispatchCompute(groups(tiles, 64), 1, 1);
    if (!readBuffer(gl.out, static_cast<size_t>(tiles) * sizeof(float), out)) return fail("mismatch readback failed");
    return glGetError() == GL_NO_ERROR || fail("mismatch pass failed");
}

bool GpuMerger::accumulate(const std::vector<float>& weight, std::vector<float>& y, std::vector<float>& v,
                           std::vector<float>& u) {
    if (!active_) return false;
    const MergeJob& job = *job_;
    Gl& gl = *gl_;
    const int tiles = job.mtx * job.mty;
    const size_t pixels = static_cast<size_t>(job.W) * job.H, cpixels = static_cast<size_t>(job.CW) * job.CH;
    gl.weights = makeBuffer(weight.size() * sizeof(float), weight.data());
    gl.accY = makeBuffer(pixels * 8);
    gl.accC = makeBuffer(cpixels * 16);
    bool first = true;
    for (int i = 0; i < job.n; ++i) {
        bool any = false;
        for (int t = 0; t < tiles && !any; ++t) any = weight[static_cast<size_t>(i) * tiles + t] > 0.f;
        if (!any) continue;
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, gl.shifts);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, gl.weights);
        glActiveTexture(GL_TEXTURE0);

        if (!uploadFrame(i, false)) return fail("frame upload failed");
        glUseProgram(gl.accLuma);
        glUniform4i(0, job.W, job.H, job.mtx, job.mty);
        glUniform4i(1, kTile, kStep, i * tiles, first ? 1 : 0);
        glBindTexture(GL_TEXTURE_2D, gl.lumaTex);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, gl.accY);
        glDispatchCompute(groups(job.W, 16), groups(job.H, 16), 1);

        if (!uploadFrame(i, true)) return fail("chroma upload failed");
        glUseProgram(gl.accChroma);
        glUniform4i(0, job.CW, job.CH, job.mtx, job.mty);
        glUniform4i(1, kTile / 2, kStep / 2, i * tiles, first ? 1 : 0);
        glBindTexture(GL_TEXTURE_2D, gl.chromaTex);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, gl.accC);
        glDispatchCompute(groups(job.CW, 16), groups(job.CH, 16), 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        first = false;
        if (glGetError() != GL_NO_ERROR) return fail("accumulate pass failed");
    }
    if (first) return fail("no frame to merge");
    deleteBuffer(gl.weights);

    gl.normY = makeBuffer(pixels * sizeof(float));
    glUseProgram(gl.normLuma);
    glUniform4i(0, job.W, job.H, job.mtx, job.mty);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, gl.accY);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, gl.normY);
    glDispatchCompute(groups(static_cast<int>(pixels), 256), 1, 1);
    gl.normC = makeBuffer(cpixels * 2 * sizeof(float));
    glUseProgram(gl.normChroma);
    glUniform4i(0, job.CW, job.CH, job.mtx, job.mty);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, gl.accC);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, gl.normC);
    glDispatchCompute(groups(static_cast<int>(cpixels), 256), 1, 1);

    y.resize(pixels);
    if (!readBuffer(gl.normY, pixels * sizeof(float), y.data())) return fail("luma readback failed");
    std::vector<float> vu(cpixels * 2);
    if (!readBuffer(gl.normC, vu.size() * sizeof(float), vu.data())) return fail("chroma readback failed");
    v.resize(cpixels);
    u.resize(cpixels);
    for (size_t p = 0; p < cpixels; ++p) {
        v[p] = vu[2 * p];
        u[p] = vu[2 * p + 1];
    }
    return glGetError() == GL_NO_ERROR || fail("normalise pass failed");
}

void GpuMerger::end() {
    if (active_) {
        Gl& gl = *gl_;
        for (GLuint* b : {&gl.shifts, &gl.weights, &gl.out, &gl.accY, &gl.accC, &gl.normY, &gl.normC}) deleteBuffer(*b);
        for (GLuint* t : {&gl.refTex, &gl.lumaTex, &gl.chromaTex}) deleteTexture(*t);
        glFinish();
        eglMakeCurrent(gl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        active_ = false;
    }
    job_ = nullptr;
    mutex_.unlock();
}

bool gpuSelfTest(GpuMerger& gpu, std::string* report) {
    // Textured scene, four frames shifted by hand-shake, with noise and an object that moves in one frame.
    const int W = 640, H = 480, PAD = 16, n = 4;
    const int SW = W + 2 * PAD, SH = H + 2 * PAD;
    std::mt19937 rng(7);
    std::uniform_real_distribution<float> uni(0.f, 1.f);
    std::normal_distribution<float> noise(0.f, 4.f);
    std::vector<float> scene(static_cast<size_t>(SW) * SH);
    for (int y = 0; y < SH; ++y)
        for (int x = 0; x < SW; ++x)
            scene[static_cast<size_t>(y) * SW + x] = 70.f + 60.f * std::sin(x * 0.11f) * std::cos(y * 0.07f) + 40.f * uni(rng);
    const int dx[n] = {0, 3, -5, 7}, dy[n] = {0, -2, 4, 6};
    std::vector<std::vector<uint8_t>> ys(n), us(n), vs(n);
    for (int i = 0; i < n; ++i) {
        ys[i].resize(static_cast<size_t>(W) * H);
        us[i].assign(static_cast<size_t>(W / 2) * (H / 2), 0);
        vs[i].assign(us[i].size(), 0);
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                float val = scene[static_cast<size_t>(y + PAD + dy[i]) * SW + x + PAD + dx[i]] + noise(rng);
                if (i == 2 && x > 300 && x < 380 && y > 200 && y < 260) val = 230.f;  // moved object
                ys[i][static_cast<size_t>(y) * W + x] = static_cast<uint8_t>(std::fmin(255.f, std::fmax(0.f, val)));
            }
        for (int y = 0; y < H / 2; ++y)
            for (int x = 0; x < W / 2; ++x) {
                const size_t c = static_cast<size_t>(y) * (W / 2) + x;
                const float base = scene[static_cast<size_t>(2 * y + PAD + dy[i]) * SW + 2 * x + PAD + dx[i]];
                us[i][c] = static_cast<uint8_t>(std::fmin(255.f, std::fmax(0.f, 128.f + 0.2f * (base - 120.f) + noise(rng))));
                vs[i][c] = static_cast<uint8_t>(std::fmin(255.f, std::fmax(0.f, 128.f - 0.15f * (base - 120.f) + noise(rng))));
            }
    }
    auto run = [&](MergeAccelerator* accel, std::string* engine) {
        auto stack = std::make_unique<Stack>(W, H, n);
        for (int i = 0; i < n; ++i) {
            stack->addFrame(ys[i].data(), W, 1, us[i].data(), vs[i].data(), W / 2, 1, i, 1.0);
        }
        ProcessParams p;
        p.accelerator = accel;
        *engine = stack->process(p).engine;
        return stack;
    };
    std::string cpuEngine, gpuEngine;
    auto cpu = run(nullptr, &cpuEngine);
    auto onGpu = run(&gpu, &gpuEngine);
    if (gpuEngine != "gpu") {
        *report = "GPU merge did not run: " + gpu.lastError();
        return false;
    }
    auto maxDiff = [](const std::vector<float>& a, const std::vector<float>& b) {
        if (a.size() != b.size()) return 1e9;
        double m = 0;
        for (size_t i = 0; i < a.size(); ++i) m = std::fmax(m, std::fabs(a[i] - b[i]));
        return m;
    };
    const double dy0 = maxDiff(cpu->mergedY(), onGpu->mergedY());
    const double dv = maxDiff(cpu->mergedV(), onGpu->mergedV());
    const double du = maxDiff(cpu->mergedU(), onGpu->mergedU());
    char buf[160];
    std::snprintf(buf, sizeof buf, "GPU vs CPU max difference Y %.4f V %.4f U %.4f (%s)", dy0, dv, du, gpu.renderer().c_str());
    *report = buf;
    return dy0 < 0.05 && dv < 0.05 && du < 0.05;
}

}  // namespace a56
