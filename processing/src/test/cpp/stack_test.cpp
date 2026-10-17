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

    // --- Bright (iPhone-style) look: brighter midtones, still monotonic, white stays white.
    {
        Stack ramp(W, H, 1);
        for (int yy = 0; yy < H; ++yy)
            for (int xx = 0; xx < W; ++xx) y[yy * W + xx] = static_cast<uint8_t>(xx * 255 / (W - 1));
        for (int k = 0; k < W / 2 * H / 2; ++k) { u[k] = 128; v[k] = 128; }
        ramp.addFrame(y.data(), W, 1, u.data(), v.data(), W / 2, 1, 0);
        ramp.process();
        a56::RenderParams natural, bright;
        natural.gain = bright.gain = 1.f;
        natural.sharpen = bright.sharpen = 0.f;
        bright.look = 1;
        std::vector<float> rn(W), rb(W);
        ramp.render(natural, [&](int row, const float* rgb) { if (row == H / 2) for (int x = 0; x < W; ++x) rn[x] = rgb[3 * x + 1]; });
        ramp.render(bright, [&](int row, const float* rgb) { if (row == H / 2) for (int x = 0; x < W; ++x) rb[x] = rgb[3 * x + 1]; });
        bool monotonic = true;
        // Local tone mapping may dip by a fraction of a JPEG step inside a run of equal input values.
        for (int x = 1; x < W; ++x) monotonic = monotonic && rb[x] >= rb[x - 1] - 0.5f / 255.f;
        check(monotonic, "bright look keeps tones in order", 0);
        check(rb[W / 2] > rn[W / 2] + 0.03f, "bright look lifts midtones", rb[W / 2] - rn[W / 2]);
        check(rb[W - 1] > 0.99f && rb[0] < 0.01f, "bright look keeps black black and white white", rb[W - 1]);
    }

    // --- Glitches seen on the phone: blocks on smooth gradients and HDR ghosts.
    {
        // Smooth radial glow (like a lamp on a wall) with only a little texture at the edges, plus noise.
        auto glow = [&](int xx, int yy) {
            const float dx = (xx - SW * 0.5f) / SW, dy = (yy - SH * 0.4f) / SH;
            return 40.f + 180.f * std::exp(-(dx * dx + dy * dy) * 12.f);
        };
        const int gx[4] = {0, 9, -14, 5}, gy[4] = {0, -6, 11, -3};
        Stack g(W, H, 4);
        std::normal_distribution<float> n4(0.f, 4.f);
        for (int i = 0; i < 4; ++i) {
            for (int yy = 0; yy < H; ++yy)
                for (int xx = 0; xx < W; ++xx) {
                    float val = glow(xx + PAD + gx[i], yy + PAD + gy[i]);
                    if (xx < 64 || yy < 64) val = blurred[(yy + PAD + gy[i]) * SW + xx + PAD + gx[i]];  // textured border
                    y[yy * W + xx] = static_cast<uint8_t>(std::clamp(val + n4(rng), 0.f, 255.f));
                }
            for (int k = 0; k < W / 2 * H / 2; ++k) { u[k] = 128; v[k] = 128; }
            g.addFrame(y.data(), W, 1, u.data(), v.data(), W / 2, 1, i);
        }
        const a56::StackStats gs = g.process();
        a56::RenderParams plain;
        plain.gain = 1.f;
        plain.sharpen = 0.f;
        // Blockiness: compare the merged glow with the true glow seen from the reference; a wrongly shifted
        // tile on a gradient shows up as a local brightness error.
        // Average the signed error over 16×16 blocks: noise averages out, a block-wide bias does not.
        const int B = 16, bw = W / B, bh = H / B;
        std::vector<double> blockErr(static_cast<size_t>(bw) * bh, 0.0);
        g.render(plain, [&](int row, const float* rgb) {
            for (int x = 0; x < bw * B; ++x) {
                blockErr[(row / B) * bw + x / B] +=
                    rgb[3 * x + 1] * 255.0 - glow(x + PAD + gx[gs.reference], row + PAD + gy[gs.reference]);
            }
        });
        double worst = 0;
        for (int by = 6; by < bh - 2; ++by)
            for (int bx = 6; bx < bw - 2; ++bx) worst = std::max(worst, std::fabs(blockErr[by * bw + bx] / (B * B)));
        check(worst < 1.5, "no tile blocks on a smooth gradient (worst 16x16 block bias)", worst);
    }
    {
        // HDR of a lamp glow on a wall: clipped near the lamp in the brighter brackets, smooth everywhere.
        auto encode = [](float lin) {
            lin = std::clamp(lin, 0.f, 1.f);
            return lin <= 0.0031308f ? lin * 12.92f : 1.055f * std::pow(lin, 1 / 2.4f) - 0.055f;
        };
        auto radiance = [&](int xx, int yy) {
            const float dx = (xx - SW * 0.5f) / SW, dy = (yy - SH * 0.4f) / SH;
            return 0.03f + 2.5f * std::exp(-(dx * dx + dy * dy) * 10.f);
        };
        const float exposures[3] = {0.25f, 1.f, 4.f};
        const int hx[3] = {-6, 0, 8}, hy[3] = {4, 0, -5};
        Stack hdr(W, H, 3);
        std::vector<uint8_t> fy(W * H);
        std::normal_distribution<float> n3(0.f, 3.f);
        for (int i = 0; i < 3; ++i) {
            for (int yy = 0; yy < H; ++yy)
                for (int xx = 0; xx < W; ++xx) {
                    float val = 255.f * encode(radiance(xx + PAD + hx[i], yy + PAD + hy[i]) * exposures[i]);
                    if (xx < 64) val = blurred[(yy + PAD + hy[i]) * SW + xx + PAD + hx[i]];  // some detail to align on
                    fy[yy * W + xx] = static_cast<uint8_t>(std::clamp(val + n3(rng), 0.f, 255.f));
                }
            for (int k = 0; k < W / 2 * H / 2; ++k) { u[k] = 128; v[k] = 128; }
            hdr.addFrame(fy.data(), W, 1, u.data(), v.data(), W / 2, 1, i, exposures[i]);
        }
        a56::ProcessParams hp;
        hp.hdr = true;
        hdr.process(hp);
        a56::RenderParams plain;
        plain.gain = 1.f;
        plain.sharpen = 0.f;
        const int B = 16, bw = W / B, bh = H / B;
        std::vector<double> m(static_cast<size_t>(bw) * bh, 0.0);
        hdr.render(plain, [&](int row, const float* rgb) {
            if (row >= bh * B) return;
            for (int x = 0; x < bw * B; ++x) m[(row / B) * bw + x / B] += rgb[3 * x + 1] * 255.0 / (B * B);
        });
        double worst = 0;
        for (int by = 2; by < bh - 2; ++by)
            for (int bx = 6; bx < bw - 2; ++bx) {
                const double lap = std::fabs(4 * m[by * bw + bx] - m[by * bw + bx - 1] - m[by * bw + bx + 1] -
                                             m[(by - 1) * bw + bx] - m[(by + 1) * bw + bx]) / 4;
                worst = std::max(worst, lap);
            }
        check(worst < 2.0, "HDR: no blocks on a lamp glow (worst block step)", worst);
    }
    {
        // HDR with something that moved: a bright object only in the +2 EV frame.
        auto encode = [](float lin) {
            lin = std::clamp(lin, 0.f, 1.f);
            return lin <= 0.0031308f ? lin * 12.92f : 1.055f * std::pow(lin, 1 / 2.4f) - 0.055f;
        };
        std::vector<float> radiance(static_cast<size_t>(SW) * SH);
        for (int yy = 0; yy < SH; ++yy)
            for (int xx = 0; xx < SW; ++xx) radiance[yy * SW + xx] = 0.02f + 0.25f * blurred[yy * SW + xx] / 255.f;
        const float exposures[3] = {0.25f, 1.f, 4.f};
        const int ox = 300, oy = 200, os = 120;
        // Fuse the same brackets with and without a bright object (e.g. a screen) that is only in the -2 EV
        // bracket, where it is perfectly exposed. Without deghosting it would show up in the result.
        auto fuse = [&](bool withObject) {
            Stack hdr(W, H, 3);
            std::vector<uint8_t> fy(W * H);
            for (int i = 0; i < 3; ++i) {
                for (int yy = 0; yy < H; ++yy)
                    for (int xx = 0; xx < W; ++xx) {
                        float lin = radiance[(yy + PAD) * SW + xx + PAD] * exposures[i];
                        if (withObject && i == 0 && xx >= ox && xx < ox + os && yy >= oy && yy < oy + os) lin = 2.0f * exposures[i];
                        fy[yy * W + xx] = static_cast<uint8_t>(std::clamp(255.f * encode(lin), 0.f, 255.f));
                    }
                for (int k = 0; k < W / 2 * H / 2; ++k) { u[k] = 128; v[k] = 128; }
                hdr.addFrame(fy.data(), W, 1, u.data(), v.data(), W / 2, 1, i, exposures[i]);
            }
            a56::ProcessParams hp;
            hp.hdr = true;
            hdr.process(hp);
            a56::RenderParams plain;
            plain.gain = 1.f;
            plain.sharpen = 0.f;
            std::vector<float> g(static_cast<size_t>(W) * H);
            hdr.render(plain, [&](int row, const float* rgb) {
                for (int x = 0; x < W; ++x) g[row * W + x] = rgb[3 * x + 1] * 255.f;
            });
            return g;
        };
        const std::vector<float> with = fuse(true), without = fuse(false);
        double inside = 0;
        long ni = 0;
        for (int yy = oy; yy < oy + os; ++yy)
            for (int xx = ox; xx < ox + os; ++xx) {
                inside += std::fabs(with[yy * W + xx] - without[yy * W + xx]);
                ++ni;
            }
        const double ghost = inside / ni;  // mean brightness the moving object adds to the result
        double signedSum = 0, maxBlock = 0;
        for (int yy = oy; yy < oy + os; ++yy)
            for (int xx = ox; xx < ox + os; ++xx) signedSum += with[yy * W + xx] - without[yy * W + xx];
        for (int by = oy; by + 16 <= oy + os; by += 16)
            for (int bx = ox; bx + 16 <= ox + os; bx += 16) {
                double b = 0;
                for (int yy = by; yy < by + 16; ++yy)
                    for (int xx = bx; xx < bx + 16; ++xx) b += with[yy * W + xx] - without[yy * W + xx];
                maxBlock = std::max(maxBlock, b / 256);
            }
        std::printf("HDR ghost: mean |diff| %.2f, signed %.2f, brightest 16px block %+.2f\n", ghost, signedSum / ni, maxBlock);
        check(ghost < 8.0, "HDR leaves out an object that moved in one bracket (ghost level)", ghost);
    }

    // --- Local tone mapping: a dark subject next to a bright lamp (HDR burst, gain 4) is lifted, the lamp is
    // held back but stays brighter than the subject, and the subject has no halo along the edge.
    {
        Stack lamp(W, H, 1);
        std::normal_distribution<float> tex(0.f, 6.f);
        for (int yy = 0; yy < H; ++yy)
            for (int xx = 0; xx < W; ++xx) y[yy * W + xx] = static_cast<uint8_t>(std::clamp((xx < W / 2 ? 70.f : 235.f) + tex(rng), 0.f, 255.f));
        for (int k = 0; k < W / 2 * H / 2; ++k) { u[k] = 128; v[k] = 128; }
        lamp.addFrame(y.data(), W, 1, u.data(), v.data(), W / 2, 1, 0);
        lamp.process();
        a56::RenderParams natural, bright;
        natural.gain = bright.gain = 4.f;
        natural.sharpen = bright.sharpen = 0.f;
        bright.look = 1;
        auto columnMeans = [&](const a56::RenderParams& p) {
            std::vector<double> col(W, 0.0);
            lamp.render(p, [&](int, const float* rgb) { for (int x = 0; x < W; ++x) col[x] += rgb[3 * x + 1] / H; });
            return col;
        };
        const std::vector<double> cn = columnMeans(natural), cb = columnMeans(bright);
        auto band = [&](const std::vector<double>& c, int x0, int x1) {
            double s = 0;
            for (int x = x0; x < x1; ++x) s += c[x];
            return s / (x1 - x0);
        };
        const double subjectN = band(cn, 100, 300), subjectB = band(cb, 100, 300);
        const double lampN = band(cn, W - 300, W - 100), lampB = band(cb, W - 300, W - 100);
        const double nearEdge = band(cb, W / 2 - 24, W / 2 - 8);
        std::printf("local tone: subject %.3f -> %.3f, lamp %.3f -> %.3f, subject near edge %.3f\n", subjectN, subjectB, lampN, lampB, nearEdge);
        // The bright look also sits a quarter stop under auto, so the net lift is small but must stay positive.
        check(subjectB > subjectN + 0.01, "bright look lifts a dark subject next to a lamp", subjectB - subjectN);
        check(lampB < lampN - 0.02 && lampB > subjectB + 0.15, "bright look holds the lamp back, still brighter", lampN - lampB);
        check(std::fabs(nearEdge - subjectB) < 0.03, "no halo along the subject's edge", nearEdge - subjectB);
    }

    std::printf("%s\n", failures == 0 ? "ALL PASSED" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
