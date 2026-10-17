// Host test for the stacking core: synthetic shifted, noisy frames with one moving object.
// Run with processing/host-test.sh (needs only a C++17 compiler).
#include "../../main/cpp/stack.h"

#include <cmath>
#include <cstdio>
#include <algorithm>
#include <cstdlib>
#include <random>
#include <vector>

using a56::Stack;

namespace {
int failures = 0;
void check(bool ok, const char* what, double value) {
    std::printf("%s %-58s %.3f\n", ok ? "PASS" : "FAIL", what, value);
    if (!ok) ++failures;
}
}  // namespace

int main() {
    const int W = 1024, H = 768, PAD = 64, n = 8;
    const int SW = W + 2 * PAD, SH = H + 2 * PAD;
    std::mt19937 rng(42);

    // Textured scene: random rectangles plus a gradient, lightly blurred.
    std::vector<float> scene(static_cast<size_t>(SW) * SH);
    for (int y = 0; y < SH; ++y)
        for (int x = 0; x < SW; ++x) scene[y * SW + x] = 60.f + 80.f * x / SW;
    std::uniform_int_distribution<int> rx(0, SW - 1), ry(0, SH - 1), rs(4, 40), rv(-60, 60);
    for (int k = 0; k < 2500; ++k) {
        int x0 = rx(rng), y0 = ry(rng), w = rs(rng), h = rs(rng), v = rv(rng);
        for (int y = y0; y < std::min(SH, y0 + h); ++y)
            for (int x = x0; x < std::min(SW, x0 + w); ++x) scene[y * SW + x] += v * 0.3f;
    }
    std::vector<float> blurred(scene.size());
    for (int y = 0; y < SH; ++y)
        for (int x = 0; x < SW; ++x) {
            float s = 0;
            for (int dy = -1; dy <= 1; ++dy)
                for (int dx = -1; dx <= 1; ++dx)
                    s += scene[std::clamp(y + dy, 0, SH - 1) * SW + std::clamp(x + dx, 0, SW - 1)];
            blurred[y * SW + x] = std::clamp(s / 9.f, 5.f, 250.f);
        }

    // Hand-shake offsets (pixels) and noise.
    const int sx[n] = {0, 3, -7, 12, -20, 1, 30, -5};
    const int sy[n] = {0, -2, 5, 9, -15, 1, -25, 40};
    std::normal_distribution<float> noise(0.f, 8.f);
    const int movX = 600, movY = 300, movS = 60;  // object that appears only in frame 3

    Stack stack(W, H, n);
    std::vector<uint8_t> y(W * H), u(W / 2 * H / 2), v(W / 2 * H / 2);
    for (int i = 0; i < n; ++i) {
        for (int yy = 0; yy < H; ++yy)
            for (int xx = 0; xx < W; ++xx) {
                const float* srow = &blurred[(yy + PAD + sy[i]) * SW + PAD + sx[i]];
                float val = i == 0 ? srow[xx] : (srow[xx - 1] + srow[xx] + srow[xx + 1]) / 3.f;
                val += noise(rng);
                if (i == 3 && xx >= movX && xx < movX + movS && yy >= movY && yy < movY + movS) val = 250.f;
                y[yy * W + xx] = static_cast<uint8_t>(std::clamp(val, 0.f, 255.f));
            }
        for (int k = 0; k < W / 2 * H / 2; ++k) {
            u[k] = 120;
            v[k] = 136;
        }
        check(stack.addFrame(y.data(), W, 1, u.data(), v.data(), W / 2, 1, i), "addFrame", i);
    }
    check(!stack.addFrame(y.data(), W, 1, u.data(), v.data(), W / 2, 1, 99), "addFrame refuses past capacity", 0);

    const a56::StackStats st = stack.process();
    const int ref = st.reference;
    check(ref != 3, "a frame is not chosen as reference just for a bright moving object", ref);
    std::printf("reference %d, align %.0f ms, merge %.0f ms, sharpness:", ref, st.alignMs, st.mergeMs);
    for (float sv : st.sharpness) std::printf(" %.2f", sv);
    std::printf("\n");
    for (int i = 0; i < n; ++i) {
        if (i == ref) continue;
        const double expected = std::abs(sx[ref] - sx[i]) + std::abs(sy[ref] - sy[i]);
        (void)expected;
        const bool exact = st.medianDx[i] == sx[ref] - sx[i] && st.medianDy[i] == sy[ref] - sy[i];
        check(exact, "median tile shift equals the true hand-shake", st.medianDx[i] - (sx[ref] - sx[i]));
    }

    // Compare the merged result (gain 1, no sharpening) with the clean scene seen from the reference.
    a56::RenderParams plain;
    plain.gain = 1.f;
    plain.sharpen = 0.f;
    double err = 0, errMoving = 0;
    long cnt = 0, cntMoving = 0;
    stack.render(plain, [&](int row, const float* rgb) {
        if (row < 8 || row >= H - 8) return;
        for (int x = 8; x < W - 8; ++x) {
            // R = Y + 1.402 (V-128) with V = 136: recover Y from the green-free estimate via all three channels.
            const float r = rgb[3 * x] * 255.f;
            const float yv = r - 1.402f * 8.f;
            const float truth = blurred[(row + PAD + sy[ref]) * SW + x + PAD + sx[ref]];
            const double e = (yv - truth) * (yv - truth);
            // Where the moving object appeared in frame 3 (in reference coordinates).
            const int ox = x + sx[ref] - sx[3], oy = row + sy[ref] - sy[3];
            if (ox >= movX && ox < movX + movS && oy >= movY && oy < movY + movS) {
                errMoving += e;
                ++cntMoving;
            } else {
                err += e;
                ++cnt;
            }
        }
    });
    const double rmse = std::sqrt(err / cnt), rmseMoving = std::sqrt(errMoving / std::max(1L, cntMoving));
    check(rmse < 4.5, "merged noise RMSE (single frame is 8.0)", rmse);
    check(rmseMoving < 12.0, "no ghost where an object moved in one frame", rmseMoving);

    int ow, oh;
    stack.outputSize(90, &ow, &oh);
    check(ow == H && oh == W, "rotation 90 swaps width and height", ow);
    std::vector<uint8_t> rgba(static_cast<size_t>(ow) * oh * 4);
    a56::RenderParams rot;
    rot.rotation = 90;
    stack.renderRgba8(rgba.data(), ow * 4, rot);
    check(rgba[3] == 255, "RGBA alpha is opaque", rgba[3]);

    const char* path = "/tmp/a56_stack_test.tiff";
    check(stack.writeTiff16(path, rot), "16-bit TIFF written", 0);
    FILE* f = std::fopen(path, "rb");
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fclose(f);
    check(size > static_cast<long>(ow) * oh * 6 && size < static_cast<long>(ow) * oh * 6 + 1024, "TIFF size = header + w*h*6", size);

    check(stack.writeLinearDng("/tmp/a56_stack_test.dng", rot, "samsung", "SM-A566E"), "linear DNG written", 0);

    // --- Night: frames shaken during the exposure are left out.
    {
        Stack night(W, H, 6);
        for (int i = 0; i < 6; ++i) {
            const int blur = (i == 2 || i == 4) ? 9 : 1;  // two frames with heavy shake blur
            for (int yy = 0; yy < H; ++yy)
                for (int xx = 0; xx < W; ++xx) {
                    float acc = 0;
                    for (int k = 0; k < blur; ++k)
                        acc += blurred[(yy + PAD) * SW + std::clamp(xx + PAD + k - blur / 2, 0, SW - 1)];
                    y[yy * W + xx] = static_cast<uint8_t>(std::clamp(acc / blur + noise(rng), 0.f, 255.f));
                }
            night.addFrame(y.data(), W, 1, u.data(), v.data(), W / 2, 1, i);
        }
        a56::ProcessParams np;
        np.minSharpness = 0.7f;
        const a56::StackStats ns = night.process(np);
        std::printf("night sharpness:");
        for (float sv : ns.sharpness) std::printf(" %.2f", sv);
        std::printf("\n");
        const bool ok = ns.rejected.size() == 2 && std::count(ns.rejected.begin(), ns.rejected.end(), 2) == 1 &&
                        std::count(ns.rejected.begin(), ns.rejected.end(), 4) == 1;
        check(ok, "night mode rejects the two shake-blurred frames", static_cast<double>(ns.rejected.size()));
    }

    // --- HDR: -2 / 0 / +2 EV brackets of a scene brighter than one exposure can hold.
    {
        auto encode = [](float lin) {
            lin = std::clamp(lin, 0.f, 1.f);
            return lin <= 0.0031308f ? lin * 12.92f : 1.055f * std::pow(lin, 1 / 2.4f) - 0.055f;
        };
        // Linear radiance spanning ~0.005 .. 3.0 (ratio 600:1), textured.
        std::vector<float> radiance(static_cast<size_t>(SW) * SH);
        for (int yy = 0; yy < SH; ++yy)
            for (int xx = 0; xx < SW; ++xx)
                radiance[yy * SW + xx] = 0.005f * std::pow(600.f, static_cast<float>(xx) / SW) * (0.6f + blurred[yy * SW + xx] / 300.f);
        const float exposures[3] = {0.25f, 1.f, 4.f};
        const int hx[3] = {0, 6, -9}, hy[3] = {0, -4, 7};
        Stack hdr(W, H, 3);
        std::vector<std::vector<uint8_t>> frames(3, std::vector<uint8_t>(W * H));
        std::normal_distribution<float> small(0.f, 2.f);
        for (int i = 0; i < 3; ++i) {
            for (int yy = 0; yy < H; ++yy)
                for (int xx = 0; xx < W; ++xx) {
                    const float lin = radiance[(yy + PAD + hy[i]) * SW + xx + PAD + hx[i]] * exposures[i];
                    frames[i][yy * W + xx] = static_cast<uint8_t>(std::clamp(255.f * encode(lin) + small(rng), 0.f, 255.f));
                }
            for (int k = 0; k < W / 2 * H / 2; ++k) { u[k] = 128; v[k] = 128; }
            hdr.addFrame(frames[i].data(), W, 1, u.data(), v.data(), W / 2, 1, i, exposures[i]);
        }
        a56::ProcessParams hp;
        hp.hdr = true;
        const a56::StackStats hs = hdr.process(hp);
        check(hs.reference == 1, "HDR reference is the middle exposure", hs.reference);
        std::printf("HDR median shifts: (%d,%d) (%d,%d) expected (%d,%d) (%d,%d)\n", hs.medianDx[0], hs.medianDy[0],
                    hs.medianDx[2], hs.medianDy[2], hx[1] - hx[0], hy[1] - hy[0], hx[1] - hx[2], hy[1] - hy[2]);
        // Brightening a -2 EV frame 4× quantises it, so HDR alignment is allowed ±1 px.
        auto near = [](int a, int b) { return std::abs(a - b) <= 1; };
        check(near(hs.medianDx[0], hx[1] - hx[0]) && near(hs.medianDy[0], hy[1] - hy[0]), "HDR aligns the -2 EV frame (±1 px)", hs.medianDx[0]);
        check(near(hs.medianDx[2], hx[1] - hx[2]) && near(hs.medianDy[2], hy[1] - hy[2]), "HDR aligns the +2 EV frame (±1 px)", hs.medianDx[2]);
        long clippedRef = 0, darkRef = 0, clippedOut = 0, darkOut = 0;
        for (int p = 0; p < W * H; ++p) {
            clippedRef += frames[1][p] >= 250;
            darkRef += frames[1][p] <= 12;
        }
        a56::RenderParams hr;
        hr.gain = 1.f;
        hr.sharpen = 0.f;
        hdr.render(hr, [&](int, const float* rgb) {
            for (int x = 0; x < W; ++x) {
                const float g = rgb[3 * x + 1] * 255.f;
                clippedOut += g >= 250.f;
                darkOut += g <= 12.f;
            }
        });
        std::printf("HDR clipped %ld -> %ld, crushed %ld -> %ld\n", clippedRef, clippedOut, darkRef, darkOut);
        check(clippedOut < clippedRef / 3, "HDR recovers most clipped highlights", static_cast<double>(clippedOut) / std::max(1L, clippedRef));
        check(darkOut < darkRef / 2, "HDR lifts most crushed shadows", static_cast<double>(darkOut) / std::max(1L, darkRef));
    }

    std::printf("%s\n", failures == 0 ? "ALL PASSED" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
