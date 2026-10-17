// Host test for the GPU merge: runs the OpenGL ES compute path (Mesa's software renderer on the build
// machine) and checks it matches the CPU merge. Run with processing/host-test.sh.
#include "../../main/cpp/gpu_merge.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <random>

namespace {
int failures = 0;
void check(bool ok, const char* what, double value) {
    std::printf("%s %-58s %.4f\n", ok ? "PASS" : "FAIL", what, value);
    if (!ok) ++failures;
}
}  // namespace

int main() {
    setenv("EGL_PLATFORM", "surfaceless", 0);  // headless Mesa; Android ignores it
    std::string error;
    auto gpu = a56::GpuMerger::create(&error);
    if (!gpu) {
        std::printf("FAIL no GPU context: %s\n", error.c_str());
        return 1;
    }
    std::printf("GPU: %s\n", gpu->renderer().c_str());

    std::string report;
    const bool ok = a56::gpuSelfTest(*gpu, &report);
    std::printf("%s\n", report.c_str());
    check(ok, "GPU merge matches the CPU merge (self-test)", 0);

    // Odd size (not a multiple of the tile step), frames with a rejected one, and a second run on the
    // same context to catch state left over from the first.
    const int W = 1000, H = 750, n = 6;
    std::mt19937 rng(3);
    std::normal_distribution<float> noise(0.f, 6.f);
    std::vector<std::vector<uint8_t>> ys(n, std::vector<uint8_t>(W * H)), us(n, std::vector<uint8_t>(W / 2 * (H / 2))),
        vs(n, std::vector<uint8_t>(W / 2 * (H / 2)));
    for (int i = 0; i < n; ++i) {
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                const float base = 120.f + 80.f * std::sin((x + 2 * i) * 0.05f) * std::cos((y - i) * 0.04f);
                ys[i][y * W + x] = static_cast<uint8_t>(std::fmin(255.f, std::fmax(0.f, base + noise(rng))));
            }
        for (size_t c = 0; c < us[i].size(); ++c) {
            us[i][c] = static_cast<uint8_t>(std::fmin(255.f, std::fmax(0.f, 120.f + noise(rng))));
            vs[i][c] = static_cast<uint8_t>(std::fmin(255.f, std::fmax(0.f, 140.f + noise(rng))));
        }
    }
    double ms[2] = {0, 0};
    std::vector<float> results[2][3];
    std::string engines[2];
    for (int k = 0; k < 2; ++k) {
        a56::Stack s(W, H, n);
        for (int i = 0; i < n; ++i) s.addFrame(ys[i].data(), W, 1, us[i].data(), vs[i].data(), W / 2, 1, i, 1.0);
        a56::ProcessParams p;
        p.minSharpness = 0.f;
        p.accelerator = k ? gpu.get() : nullptr;
        const auto t0 = std::chrono::steady_clock::now();
        const a56::StackStats st = s.process(p);
        ms[k] = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        engines[k] = st.engine;
        results[k][0] = s.mergedY();
        results[k][1] = s.mergedV();
        results[k][2] = s.mergedU();
    }
    double worst = 0;
    for (int c = 0; c < 3; ++c)
        for (size_t i = 0; i < results[0][c].size(); ++i) worst = std::fmax(worst, std::fabs(results[0][c][i] - results[1][c][i]));
    std::printf("CPU %.0f ms, GPU (software) %.0f ms\n", ms[0], ms[1]);
    check(engines[1] == "gpu", "second merge on the same context ran on the GPU", 0);
    check(worst < 0.05, "odd-size merge matches the CPU (max difference)", worst);

    std::printf("%s\n", failures == 0 ? "ALL PASSED" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
