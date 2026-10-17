#include "stack.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <thread>

namespace a56 {

namespace {

constexpr int kLevels = 5;
constexpr int kAlignTile = 16;
// Search radius per pyramid level, finest first. The coarsest level reaches about ±90 px at full size.
constexpr int kRadius[kLevels] = {1, 2, 2, 3, 4};
constexpr int kMergeTile = 32;
constexpr int kMergeStep = kMergeTile / 2;
constexpr double kPi = 3.14159265358979323846;

double nowMs() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Runs fn(0..n-1) on up to 8 threads. fn must only write data no other index writes.
template <typename F>
void parallelFor(int n, F&& fn) {
    const int threads = std::min({n, 8, static_cast<int>(std::max(1u, std::thread::hardware_concurrency()))});
    if (threads <= 1) {
        for (int i = 0; i < n; ++i) fn(i);
        return;
    }
    std::atomic<int> next{0};
    std::vector<std::thread> pool;
    for (int t = 0; t < threads; ++t) {
        pool.emplace_back([&] {
            for (int i; (i = next.fetch_add(1)) < n;) fn(i);
        });
    }
    for (std::thread& th : pool) th.join();
}

// Sum of absolute differences between two size×size tiles.
inline uint32_t sad(const uint8_t* a, int aStride, const uint8_t* b, int bStride, int size) {
    uint32_t sum = 0;
    for (int y = 0; y < size; ++y) {
        const uint8_t* ra = a + y * aStride;
        const uint8_t* rb = b + y * bStride;
        for (int x = 0; x < size; ++x) sum += static_cast<uint32_t>(std::abs(int(ra[x]) - int(rb[x])));
    }
    return sum;
}

// sin² window: two copies offset by half a tile sum to exactly 1, so overlapping tiles blend seamlessly.
std::vector<float> window(int size) {
    std::vector<float> w(size);
    for (int i = 0; i < size; ++i) {
        double s = std::sin(kPi * (i + 0.5) / size);
        w[i] = static_cast<float>(s * s);
    }
    return w;
}

float srgbDecode(float c) { return c <= 0.04045f ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f); }
float srgbEncode(float c) { return c <= 0.0031308f ? c * 12.92f : 1.055f * std::pow(c, 1.0f / 2.4f) - 0.055f; }

}  // namespace

Stack::Stack(int width, int height, int capacity)
    : width_(width & ~1), height_(height & ~1), capacity_(capacity) {
    frames_.reserve(capacity);
}

bool Stack::addFrame(const uint8_t* y, int yRowStride, int yPixelStride,
                     const uint8_t* u, const uint8_t* v, int uvRowStride, int uvPixelStride,
                     int64_t timestampNs, double exposure) {
    if (count() >= capacity_ || processed()) return false;
    const int w = width_, h = height_, cw = w / 2, ch = h / 2;
    Frame f;
    f.timestamp = timestampNs;
    f.exposure = exposure > 0 ? exposure : 1.0;
    f.y.resize(static_cast<size_t>(w) * h);
    for (int r = 0; r < h; ++r) {
        const uint8_t* src = y + static_cast<size_t>(r) * yRowStride;
        uint8_t* dst = f.y.data() + static_cast<size_t>(r) * w;
        if (yPixelStride == 1) {
            std::memcpy(dst, src, w);
        } else {
            for (int c = 0; c < w; ++c) dst[c] = src[c * yPixelStride];
        }
    }
    f.vu.resize(static_cast<size_t>(cw) * ch * 2);
    for (int r = 0; r < ch; ++r) {
        const uint8_t* vr = v + static_cast<size_t>(r) * uvRowStride;
        const uint8_t* ur = u + static_cast<size_t>(r) * uvRowStride;
        uint8_t* dst = f.vu.data() + static_cast<size_t>(r) * cw * 2;
        for (int c = 0; c < cw; ++c) {
            dst[2 * c] = vr[c * uvPixelStride];
            dst[2 * c + 1] = ur[c * uvPixelStride];
        }
    }
    frames_.push_back(std::move(f));
    return true;
}

std::vector<Stack::Level> Stack::pyramid(const Frame& f) const {
    std::vector<Level> levels(kLevels);
    levels[0].w = width_;
    levels[0].h = height_;
    levels[0].ext = f.y.data();
    for (int l = 1; l < kLevels; ++l) {
        const Level& p = levels[l - 1];
        Level& n = levels[l];
        n.w = p.w / 2;
        n.h = p.h / 2;
        n.own.resize(static_cast<size_t>(n.w) * n.h);
        const uint8_t* src = p.data();
        for (int y = 0; y < n.h; ++y) {
            const uint8_t* r0 = src + static_cast<size_t>(2 * y) * p.w;
            const uint8_t* r1 = r0 + p.w;
            uint8_t* dst = n.own.data() + static_cast<size_t>(y) * n.w;
            for (int x = 0; x < n.w; ++x) {
                dst[x] = static_cast<uint8_t>((r0[2 * x] + r0[2 * x + 1] + r1[2 * x] + r1[2 * x + 1] + 2) >> 2);
            }
        }
    }
    return levels;
}

float Stack::sharpness(const Frame& f) const {
    // Detail energy on a 2× downsampled copy: mean squared gradient per 32×32 block. Sensor noise adds the
    // same energy to every block of every frame, so the flattest blocks (10th percentile) measure that
    // noise floor and it is subtracted. The rest is scene detail, which shake blur reduces. Using the 75th
    // percentile keeps one high-contrast (possibly moving) object from deciding the result.
    const int w = width_ / 2, h = height_ / 2, B = 32;
    std::vector<uint16_t> small(static_cast<size_t>(w) * h);
    for (int r = 0; r < h; ++r) {
        const uint8_t* r0 = f.y.data() + static_cast<size_t>(2 * r) * width_;
        const uint8_t* r1 = r0 + width_;
        for (int c = 0; c < w; ++c) small[static_cast<size_t>(r) * w + c] = static_cast<uint16_t>(r0[2 * c] + r0[2 * c + 1] + r1[2 * c] + r1[2 * c + 1]);
    }
    // Horizontal and vertical detail are scored separately: shake usually blurs one direction.
    std::vector<float> bx2, by2;
    for (int by = 0; by + B < h; by += B) {
        for (int bx = 0; bx + B < w; bx += B) {
            double sx = 0, sy = 0;
            for (int r = by; r < by + B; ++r) {
                const uint16_t* row = small.data() + static_cast<size_t>(r) * w;
                for (int c = bx; c < bx + B; ++c) {
                    const double gx = int(row[c + 1]) - int(row[c]), gy = int(row[c + w]) - int(row[c]);
                    sx += gx * gx;
                    sy += gy * gy;
                }
            }
            bx2.push_back(static_cast<float>(sx / (B * B) / 16.0));
            by2.push_back(static_cast<float>(sy / (B * B) / 16.0));
        }
    }
    if (bx2.size() < 4) return 0.f;
    auto detail = [](std::vector<float>& v) {
        std::sort(v.begin(), v.end());
        const float floor = v[v.size() / 10];        // noise only
        return std::max(1e-3f, v[v.size() * 3 / 4] - floor);  // textured blocks, robust to a few outliers
    };
    // The weaker direction: blur along either axis lowers it fully.
    return std::min(detail(bx2), detail(by2));
}

std::vector<Stack::Shift> Stack::align(const std::vector<Level>& ref, const std::vector<Level>& alt,
                                       int* tilesX0, int* tilesY0) const {
    const int T = kAlignTile;
    std::vector<Shift> prev;
    int pnx = 0, pny = 0;
    for (int l = kLevels - 1; l >= 0; --l) {
        const Level& R = ref[l];
        const Level& A = alt[l];
        const int nx = (R.w + T - 1) / T, ny = (R.h + T - 1) / T;
        std::vector<Shift> cur(static_cast<size_t>(nx) * ny);
        const uint8_t* rp = R.data();
        const uint8_t* ap = A.data();
        for (int ty = 0; ty < ny; ++ty) {
            const int y0 = std::min(ty * T, R.h - T);
            for (int tx = 0; tx < nx; ++tx) {
                const int x0 = std::min(tx * T, R.w - T);
                const uint8_t* refTile = rp + static_cast<size_t>(y0) * R.w + x0;
                auto fit = [&](Shift c) {  // keep a candidate inside the image
                    c.dx = clampi(c.dx, -x0, A.w - T - x0);
                    c.dy = clampi(c.dy, -y0, A.h - T - y0);
                    return c;
                };
                auto costOf = [&](Shift c) {
                    return sad(refTile, R.w, ap + static_cast<size_t>(y0 + c.dy) * A.w + x0 + c.dx, A.w, T);
                };
                Shift g;
                if (!prev.empty()) {
                    // Start from the best of the parent tile and its four neighbours, upsampled, so tiles
                    // whose parent was wrong (e.g. at the image border) recover from a neighbour.
                    const int px = std::min(((x0 + T / 2) / 2) / T, pnx - 1);
                    const int py = std::min(((y0 + T / 2) / 2) / T, pny - 1);
                    const int nb[5][2] = {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                    uint32_t bestGuess = UINT32_MAX;
                    for (const auto& o : nb) {
                        const int qx = px + o[0], qy = py + o[1];
                        if (qx < 0 || qy < 0 || qx >= pnx || qy >= pny) continue;
                        const Shift c = fit({prev[qy * pnx + qx].dx * 2, prev[qy * pnx + qx].dy * 2});
                        const uint32_t cost = costOf(c);
                        if (cost < bestGuess) {
                            bestGuess = cost;
                            g = c;
                        }
                    }
                }
                g = fit(g);
                const int r = kRadius[l];
                uint32_t best = UINT32_MAX;
                int bestMove = INT_MAX;
                Shift bestShift = g;
                for (int dy = -r; dy <= r; ++dy) {
                    const int sy = g.dy + dy;
                    if (y0 + sy < 0 || y0 + sy + T > A.h) continue;
                    for (int dx = -r; dx <= r; ++dx) {
                        const int sx = g.dx + dx;
                        if (x0 + sx < 0 || x0 + sx + T > A.w) continue;
                        const uint32_t cost = costOf({sx, sy});
                        const int move = std::abs(dx) + std::abs(dy);
                        if (cost < best || (cost == best && move < bestMove)) {
                            best = cost;
                            bestMove = move;
                            bestShift = {sx, sy};
                        }
                    }
                }
                cur[static_cast<size_t>(ty) * nx + tx] = bestShift;
            }
        }
        prev.swap(cur);
        pnx = nx;
        pny = ny;
    }
    *tilesX0 = pnx;
    *tilesY0 = pny;
    return prev;
}

struct Stack::Alignment {
    int ref = 0, n = 0, W = 0, H = 0, atx = 0, aty = 0;
    std::vector<std::vector<Shift>> shifts;
    // Shift of frame i for the merge tile whose centre is (cx, cy) in reference coordinates.
    Shift at(int i, int cx, int cy) const {
        if (i == ref || shifts[i].empty()) return {};
        const int ax = std::min(clampi(cx, 0, W - 1) / kAlignTile, atx - 1);
        const int ay = std::min(clampi(cy, 0, H - 1) / kAlignTile, aty - 1);
        return shifts[i][static_cast<size_t>(ay) * atx + ax];
    }
};

namespace {

// Accumulates one luma plane, warped by per-tile shifts, with sin² tile windows and per-tile weights.
template <typename ShiftFn, typename WeightFn>
void accumulateLuma(const uint8_t* src, int W, int H, int mtx, int mty, ShiftFn shiftOf, WeightFn weightOf,
                    std::vector<float>& acc, std::vector<float>& wsum) {
    const int M = kMergeTile, S = kMergeStep;
    static const std::vector<float> win = window(M);
    // Tile rows ty and ty+2 never overlap, so even rows run in parallel, then odd rows.
    for (int parity = 0; parity < 2; ++parity) parallelFor((mty - parity + 1) / 2, [&](int k) {
        const int ty = 2 * k + parity;
        for (int tx = 0; tx < mtx; ++tx) {
            const float wt = weightOf(tx, ty);
            if (wt <= 0.f) continue;
            const auto sh = shiftOf(tx, ty);
            const int ox = tx * S - S, oy = ty * S - S;
            for (int y = std::max(oy, 0); y < std::min(oy + M, H); ++y) {
                const float wy = win[y - oy] * wt;
                const uint8_t* srow = src + static_cast<size_t>(clampi(y + sh.dy, 0, H - 1)) * W;
                float* arow = acc.data() + static_cast<size_t>(y) * W;
                float* wrow = wsum.data() + static_cast<size_t>(y) * W;
                for (int x = std::max(ox, 0); x < std::min(ox + M, W); ++x) {
                    const float ww = wy * win[x - ox];
                    arow[x] += ww * srow[clampi(x + sh.dx, 0, W - 1)];
                    wrow[x] += ww;
                }
            }
        }
    });
}

// Same for the interleaved NV21 chroma plane at half resolution (shifts halved).
template <typename ShiftFn, typename WeightFn>
void accumulateChroma(const uint8_t* vu, int CW, int CH, int mtx, int mty, ShiftFn shiftOf, WeightFn weightOf,
                      std::vector<float>& accV, std::vector<float>& accU, std::vector<float>& wsum) {
    const int MC = kMergeTile / 2, SC = kMergeStep / 2;
    static const std::vector<float> win = window(MC);
    // Tile rows ty and ty+2 never overlap, so even rows run in parallel, then odd rows.
    for (int parity = 0; parity < 2; ++parity) parallelFor((mty - parity + 1) / 2, [&](int k) {
        const int ty = 2 * k + parity;
        for (int tx = 0; tx < mtx; ++tx) {
            const float wt = weightOf(tx, ty);
            if (wt <= 0.f) continue;
            const auto sh = shiftOf(tx, ty);
            const int dx = static_cast<int>(std::lround(sh.dx / 2.0)), dy = static_cast<int>(std::lround(sh.dy / 2.0));
            const int ox = tx * SC - SC, oy = ty * SC - SC;
            for (int y = std::max(oy, 0); y < std::min(oy + MC, CH); ++y) {
                const float wy = win[y - oy] * wt;
                const uint8_t* srow = vu + static_cast<size_t>(clampi(y + dy, 0, CH - 1)) * CW * 2;
                const size_t base = static_cast<size_t>(y) * CW;
                for (int x = std::max(ox, 0); x < std::min(ox + MC, CW); ++x) {
                    const float ww = wy * win[x - ox];
                    const int sx = clampi(x + dx, 0, CW - 1);
                    accV[base + x] += ww * srow[2 * sx];
                    accU[base + x] += ww * srow[2 * sx + 1];
                    wsum[base + x] += ww;
                }
            }
        }
    });
}

struct Img {
    int w = 0, h = 0;
    std::vector<float> p;
    Img() = default;
    Img(int w_, int h_) : w(w_), h(h_), p(static_cast<size_t>(w_) * h_, 0.f) {}
    float& at(int x, int y) { return p[static_cast<size_t>(y) * w + x]; }
    float at(int x, int y) const { return p[static_cast<size_t>(y) * w + x]; }
};

constexpr float kBinomial[5] = {1 / 16.f, 4 / 16.f, 6 / 16.f, 4 / 16.f, 1 / 16.f};

// Gaussian pyramid step: 5-tap binomial blur, then keep every second pixel.
Img downsample(const Img& src) {
    Img tmp((src.w + 1) / 2, src.h);
    for (int y = 0; y < src.h; ++y)
        for (int x = 0; x < tmp.w; ++x) {
            float s = 0;
            for (int k = -2; k <= 2; ++k) s += kBinomial[k + 2] * src.at(clampi(2 * x + k, 0, src.w - 1), y);
            tmp.at(x, y) = s;
        }
    Img dst(tmp.w, (src.h + 1) / 2);
    for (int y = 0; y < dst.h; ++y)
        for (int x = 0; x < dst.w; ++x) {
            float s = 0;
            for (int k = -2; k <= 2; ++k) s += kBinomial[k + 2] * tmp.at(x, clampi(2 * y + k, 0, tmp.h - 1));
            dst.at(x, y) = s;
        }
    return dst;
}

// Inverse step: zero-insertion upsampling with the same kernel (×2 per axis), to size w × h.
Img upsample(const Img& src, int w, int h) {
    Img tmp(w, src.h);
    for (int y = 0; y < src.h; ++y)
        for (int x = 0; x < w; ++x) {
            float s = 0;
            for (int k = -2; k <= 2; ++k) {
                const int t = x - k;
                if (t & 1) continue;
                s += 2.f * kBinomial[k + 2] * src.at(clampi(t / 2, 0, src.w - 1), y);
            }
            tmp.at(x, y) = s;
        }
    Img dst(w, h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float s = 0;
            for (int k = -2; k <= 2; ++k) {
                const int t = y - k;
                if (t & 1) continue;
                s += 2.f * kBinomial[k + 2] * tmp.at(x, clampi(t / 2, 0, src.h - 1));
            }
            dst.at(x, y) = s;
        }
    return dst;
}

int pyramidLevels(int w, int h) {
    int levels = 1;
    while (levels < 8 && std::min(w, h) >> levels >= 16) ++levels;
    return levels;
}

}  // namespace

StackStats Stack::process(const ProcessParams& params) {
    StackStats st;
    const int n = count();
    st.frames = n;
    if (n == 0) return st;
    const double t0 = nowMs();

    st.sharpness.resize(n);
    parallelFor(n, [&](int i) { st.sharpness[i] = sharpness(frames_[i]); });
    const float best = *std::max_element(st.sharpness.begin(), st.sharpness.end());

    // 1. Reference: for HDR the middle exposure, otherwise the sharpest frame.
    int ref;
    if (params.hdr) {
        std::vector<int> order(n);
        for (int i = 0; i < n; ++i) order[i] = i;
        std::sort(order.begin(), order.end(), [&](int a, int b) { return frames_[a].exposure < frames_[b].exposure; });
        ref = order[n / 2];
    } else {
        ref = static_cast<int>(std::max_element(st.sharpness.begin(), st.sharpness.end()) - st.sharpness.begin());
        for (int i = 0; i < n; ++i) {
            if (i != ref && st.sharpness[i] < params.minSharpness * best) st.rejected.push_back(i);
        }
    }
    st.reference = ref;
    auto isRejected = [&](int i) { return std::find(st.rejected.begin(), st.rejected.end(), i) != st.rejected.end(); };

    // 2. Coarse-to-fine tile alignment to the reference. Frames with another exposure are first brightness-
    //    matched in linear light so the tile differences measure position, not exposure.
    Alignment al;
    al.ref = ref;
    al.n = n;
    al.W = width_;
    al.H = height_;
    al.shifts.resize(n);
    // Detail per 16×16 alignment tile of the reference. Tiles without detail (flat walls, smooth glows)
    // cannot be aligned reliably: any shift matches noise equally well, and on a brightness gradient a
    // wrong shift shows as a square patch. Those tiles take the frame's overall shift instead.
    const int T = kAlignTile, W = width_, H = height_;
    const int gtx = (W + T - 1) / T, gty = (H + T - 1) / T;
    std::vector<float> texture(static_cast<size_t>(gtx) * gty);
    std::vector<char> textured(texture.size(), 1);
    {
        const uint8_t* ry = frames_[ref].y.data();
        parallelFor(gty, [&](int ty) {
            const int y0 = std::min(ty * T, H - T);
            for (int tx = 0; tx < gtx; ++tx) {
                const int x0 = std::min(tx * T, W - T);
                long sum = 0;
                for (int y = y0; y < y0 + T - 1; ++y) {
                    const uint8_t* row = ry + static_cast<size_t>(y) * W;
                    for (int x = x0; x < x0 + T - 1; ++x) sum += std::abs(row[x + 1] - row[x]) + std::abs(row[x + W] - row[x]);
                }
                texture[static_cast<size_t>(ty) * gtx + tx] = static_cast<float>(sum) / ((T - 1) * (T - 1));
            }
        });
        std::vector<float> sorted = texture;
        std::nth_element(sorted.begin(), sorted.begin() + sorted.size() / 5, sorted.end());
        const float flat = 1.5f * sorted[sorted.size() / 5];  // noise-only tiles sit near the 20th percentile
        for (size_t t = 0; t < texture.size(); ++t) textured[t] = texture[t] > flat;
    }

    {
        const std::vector<Level> refPyr = pyramid(frames_[ref]);
        std::vector<int> tilesX(n, 0), tilesY(n, 0);
        parallelFor(n, [&](int i) {
            if (i == ref || isRejected(i)) return;
            int& atx = tilesX[i];
            int& aty = tilesY[i];
            const double ratio = frames_[ref].exposure / frames_[i].exposure;
            const bool sameExposure = std::fabs(ratio - 1.0) < 0.05;
            Frame matched;  // brightness-matched copy for frames with another exposure
            if (!sameExposure) {
                uint8_t lut[256];
                for (int v = 0; v < 256; ++v) {
                    const float lin = srgbDecode(v / 255.f) * static_cast<float>(ratio);
                    lut[v] = static_cast<uint8_t>(std::lround(255.f * srgbEncode(std::min(1.f, lin))));
                }
                matched.y.resize(frames_[i].y.size());
                for (size_t p = 0; p < matched.y.size(); ++p) matched.y[p] = lut[frames_[i].y[p]];
            }
            const std::vector<uint8_t>& altY = sameExposure ? frames_[i].y : matched.y;
            al.shifts[i] = align(refPyr, pyramid(sameExposure ? frames_[i] : matched), &atx, &aty);

            // Reliable tiles: detailed, and (for brackets) neither clipped nor crushed in either frame.
            std::vector<char> reliable(al.shifts[i].size(), 0);
            std::vector<int> xs, ys;
            for (int ty = 0; ty < aty; ++ty) {
                const int y0 = std::min(ty * T, H - T);
                for (int tx = 0; tx < atx; ++tx) {
                    const int x0 = std::min(tx * T, W - T);
                    const size_t t = static_cast<size_t>(ty) * atx + tx;
                    bool ok = textured[t] != 0;
                    if (ok && !sameExposure) {
                        long sr = 0, sa = 0;
                        for (int y = y0; y < y0 + T; ++y) {
                            for (int x = x0; x < x0 + T; ++x) {
                                sr += frames_[ref].y[static_cast<size_t>(y) * W + x];
                                sa += altY[static_cast<size_t>(y) * W + x];
                            }
                        }
                        const long mr = sr / (T * T), ma = sa / (T * T);
                        ok = mr >= 16 && mr <= 239 && ma >= 16 && ma <= 239;
                    }
                    if (ok) {
                        reliable[t] = 1;
                        xs.push_back(al.shifts[i][t].dx);
                        ys.push_back(al.shifts[i][t].dy);
                    }
                }
            }
            if (!xs.empty()) {
                std::nth_element(xs.begin(), xs.begin() + xs.size() / 2, xs.end());
                std::nth_element(ys.begin(), ys.begin() + ys.size() / 2, ys.end());
                const Shift global{xs[xs.size() / 2], ys[ys.size() / 2]};
                // Brackets are taken a fraction of a second apart, so hand-shake between them is close to a
                // pure translation: use one shift for the whole frame, which also avoids tile seams where
                // the brightness-matched frames differ in tone. Same-exposure stacks keep per-tile shifts
                // where there is detail (local motion, lens distortion) and the overall shift elsewhere.
                for (size_t t = 0; t < reliable.size(); ++t) {
                    if (params.hdr || !reliable[t]) al.shifts[i][t] = global;
                }
            }
        });
        for (int i = 0; i < n; ++i) {
            if (tilesX[i] > 0) {
                al.atx = tilesX[i];
                al.aty = tilesY[i];
            }
        }
    }
    st.meanShift.assign(n, 0.f);
    st.medianDx.assign(n, 0);
    st.medianDy.assign(n, 0);
    for (int i = 0; i < n; ++i) {
        if (al.shifts[i].empty()) continue;
        double s = 0;
        std::vector<int> xs, ys;
        for (const Shift& sh : al.shifts[i]) {
            s += std::abs(sh.dx) + std::abs(sh.dy);
            xs.push_back(sh.dx);
            ys.push_back(sh.dy);
        }
        st.meanShift[i] = static_cast<float>(s / al.shifts[i].size());
        std::nth_element(xs.begin(), xs.begin() + xs.size() / 2, xs.end());
        std::nth_element(ys.begin(), ys.begin() + ys.size() / 2, ys.end());
        st.medianDx[i] = xs[xs.size() / 2];
        st.medianDy[i] = ys[ys.size() / 2];
    }
    const double t1 = nowMs();
    st.alignMs = t1 - t0;

    // 3. Merge (same exposure) or fuse (bracketed exposures).
    if (params.hdr && n > 1) {
        fuseExposures(al, st);
    } else {
        mergeRobust(al, st.rejected, st);
    }
    st.mergeMs = nowMs() - t1;
    std::vector<Frame>().swap(frames_);
    return st;
}

void Stack::mergeRobust(const Alignment& al, const std::vector<int>& rejected, StackStats& st) {
    const int n = al.n, ref = al.ref, W = width_, H = height_, CW = W / 2, CH = H / 2;
    const int M = kMergeTile, S = kMergeStep;
    const int mtx = W / S + 2, mty = H / S + 2;
    st.tilesX = mtx;
    st.tilesY = mty;
    auto shiftOf = [&](int i) {
        return [&al, i](int tx, int ty) { return al.at(i, tx * kMergeStep, ty * kMergeStep); };
    };

    // Per tile and frame: robust weight from the mismatch with the reference, relative to the noise level.
    std::vector<float> weight(static_cast<size_t>(n) * mtx * mty, 1.f);
    st.noise.assign(n, 0.f);
    const uint8_t* ry = frames_[ref].y.data();
    parallelFor(n, [&](int i) {
        if (i == ref) return;
        float* wi = weight.data() + static_cast<size_t>(i) * mtx * mty;
        if (std::find(rejected.begin(), rejected.end(), i) != rejected.end()) {
            std::fill(wi, wi + static_cast<size_t>(mtx) * mty, 0.f);
            return;
        }
        const uint8_t* ay = frames_[i].y.data();
        std::vector<float> d(static_cast<size_t>(mtx) * mty);
        for (int ty = 0; ty < mty; ++ty) {
            for (int tx = 0; tx < mtx; ++tx) {
                const Shift sh = al.at(i, tx * S, ty * S);
                const int ox = tx * S - S, oy = ty * S - S;
                double sum = 0;
                int cnt = 0;
                for (int y = std::max(oy, 0); y < std::min(oy + M, H); ++y) {
                    const int sy = clampi(y + sh.dy, 0, H - 1);
                    for (int x = std::max(ox, 0); x < std::min(ox + M, W); ++x) {
                        const int sx = clampi(x + sh.dx, 0, W - 1);
                        sum += std::abs(int(ry[static_cast<size_t>(y) * W + x]) - int(ay[static_cast<size_t>(sy) * W + sx]));
                        ++cnt;
                    }
                }
                d[static_cast<size_t>(ty) * mtx + tx] = cnt ? static_cast<float>(sum / cnt) : 0.f;
            }
        }
        // Most tiles are static, so the median mismatch is the noise level between two frames.
        std::vector<float> sorted = d;
        std::nth_element(sorted.begin(), sorted.begin() + sorted.size() / 2, sorted.end());
        const float noise = std::max(0.5f, sorted[sorted.size() / 2]);
        st.noise[i] = noise;
        const float lo = 1.5f * noise, hi = 3.0f * noise;
        for (size_t t = 0; t < d.size(); ++t) wi[t] = d[t] <= lo ? 1.f : (d[t] >= hi ? 0.f : (hi - d[t]) / (hi - lo));
    });
    st.tileWeight.assign(static_cast<size_t>(mtx) * mty, 1.f);
    if (n > 1) {
        for (size_t t = 0; t < st.tileWeight.size(); ++t) {
            float s = 0;
            for (int i = 0; i < n; ++i) if (i != ref) s += weight[static_cast<size_t>(i) * mtx * mty + t];
            st.tileWeight[t] = s / (n - 1);
        }
    }
    auto weightOf = [&](int i) {
        return [&weight, i, mtx, mty](int tx, int ty) {
            return weight[static_cast<size_t>(i) * mtx * mty + static_cast<size_t>(ty) * mtx + tx];
        };
    };

    {
        std::vector<float> acc(static_cast<size_t>(W) * H, 0.f), wsum(acc.size(), 0.f);
        for (int i = 0; i < n; ++i) accumulateLuma(frames_[i].y.data(), W, H, mtx, mty, shiftOf(i), weightOf(i), acc, wsum);
        for (size_t p = 0; p < acc.size(); ++p) acc[p] = wsum[p] > 0.f ? acc[p] / wsum[p] : 0.f;
        mergedY_.swap(acc);
    }
    {
        std::vector<float> accV(static_cast<size_t>(CW) * CH, 0.f), accU(accV.size(), 0.f), wsum(accV.size(), 0.f);
        for (int i = 0; i < n; ++i) {
            accumulateChroma(frames_[i].vu.data(), CW, CH, mtx, mty, shiftOf(i), weightOf(i), accV, accU, wsum);
        }
        for (size_t p = 0; p < wsum.size(); ++p) {
            const float w = wsum[p] > 0.f ? wsum[p] : 1.f;
            accV[p] /= w;
            accU[p] /= w;
        }
        mergedV_.swap(accV);
        mergedU_.swap(accU);
    }
}

void Stack::fuseExposures(const Alignment& al, StackStats& st) {
    // Mertens exposure fusion: per pixel, favour well-exposed, detailed, colourful frames, and blend with
    // Laplacian pyramids so the weight maps leave no seams.
    const int n = al.n, W = width_, H = height_, CW = W / 2, CH = H / 2, S = kMergeStep;
    const int mtx = W / S + 2, mty = H / S + 2;
    st.tilesX = mtx;
    st.tilesY = mty;
    st.tileWeight.assign(static_cast<size_t>(mtx) * mty, 1.f);
    auto one = [](int, int) { return 1.f; };

    std::vector<double> exposure(n);
    for (int i = 0; i < n; ++i) exposure[i] = frames_[i].exposure;
    std::vector<Img> ys(n), vs(n), us(n), ws(n);
    parallelFor(n, [&](int i) {
        auto shiftOf = [&al, i](int tx, int ty) { return al.at(i, tx * kMergeStep, ty * kMergeStep); };
        Img y(W, H), wsum(W, H);
        accumulateLuma(frames_[i].y.data(), W, H, mtx, mty, shiftOf, one, y.p, wsum.p);
        for (size_t p = 0; p < y.p.size(); ++p) y.p[p] /= std::max(wsum.p[p], 1e-6f);
        Img v(CW, CH), u(CW, CH), cw(CW, CH);
        accumulateChroma(frames_[i].vu.data(), CW, CH, mtx, mty, shiftOf, one, v.p, u.p, cw.p);
        for (size_t p = 0; p < v.p.size(); ++p) {
            const float w = std::max(cw.p[p], 1e-6f);
            v.p[p] /= w;
            u.p[p] /= w;
        }
        ys[i] = std::move(y);
        vs[i] = std::move(v);
        us[i] = std::move(u);
    });
    std::vector<Frame>().swap(frames_);  // the warped float copies are all that is needed now

    // Ghosts: where a bracket, brightness-matched to the reference, disagrees with it (something moved or
    // a screen changed), that bracket's content is replaced by the reference's. Judged per 32×32 tile on
    // pixels well exposed in both, relative to the typical disagreement (tone-curve differences), then
    // interpolated smoothly between tile centres.
    const int ref = al.ref;
    parallelFor(n, [&](int i) {
        if (i == ref) return;
        float lut[256];
        const float ratio = static_cast<float>(exposure[ref] / exposure[i]);
        for (int v = 0; v < 256; ++v) lut[v] = 255.f * srgbEncode(std::min(1.f, srgbDecode(v / 255.f) * ratio));
        std::vector<float> d(static_cast<size_t>(mtx) * mty, -1.f);
        for (int ty = 0; ty < mty; ++ty) {
            for (int tx = 0; tx < mtx; ++tx) {
                double sum = 0;
                int cnt = 0, total = 0;
                for (int y = std::max(ty * S - S, 0); y < std::min(ty * S + S, H); ++y) {
                    for (int x = std::max(tx * S - S, 0); x < std::min(tx * S + S, W); ++x) {
                        ++total;
                        const float r = ys[ref].at(x, y), a = ys[i].at(x, y);
                        const float m = lut[static_cast<int>(std::min(255.f, std::max(0.f, a)) + 0.5f)];
                        // Both must be well exposed. The brightness-matched value may leave the range: a bracket
                        // predicting "brighter than white" where the reference is not clipped is a disagreement.
                        if (r < 12.f || r > 243.f || a < 12.f || a > 243.f) continue;
                        sum += std::fabs(m - r);
                        ++cnt;
                    }
                }
                if (cnt * 4 >= total) d[static_cast<size_t>(ty) * mtx + tx] = static_cast<float>(sum / cnt);
            }
        }
        std::vector<float> known;
        for (float v : d) if (v >= 0) known.push_back(v);
        if (known.size() < 8) return;  // too little overlap to judge
        std::nth_element(known.begin(), known.begin() + known.size() / 2, known.end());
        const float typical = known[known.size() / 2];
        const float lo = std::max(4.f, 2.f * typical), hi = std::max(8.f, 4.f * typical);
        std::vector<float> keep(d.size(), 1.f);
        int ghosts = 0;
        for (size_t t = 0; t < d.size(); ++t) {
            if (d[t] < 0) continue;  // cannot compare (clipped in one frame): that is what HDR needs this frame for
            keep[t] = d[t] <= lo ? 1.f : (d[t] >= hi ? 0.f : (hi - d[t]) / (hi - lo));
            ghosts += keep[t] < 0.5f;
        }
        if (ghosts == 0) return;
        // Grow ghost areas by one tile: objects rarely end on a tile border.
        std::vector<float> grown(keep.size());
        for (int ty = 0; ty < mty; ++ty) {
            for (int tx = 0; tx < mtx; ++tx) {
                float k = 1.f;
                for (int dy = -1; dy <= 1; ++dy)
                    for (int dx = -1; dx <= 1; ++dx)
                        k = std::min(k, keep[static_cast<size_t>(clampi(ty + dy, 0, mty - 1)) * mtx + clampi(tx + dx, 0, mtx - 1)]);
                grown[static_cast<size_t>(ty) * mtx + tx] = k;
            }
        }
        keep.swap(grown);
        // Exposure fusion blurs weights over large areas at coarse pyramid levels, so lowering the weight
        // alone lets a ghost bleed in as haze. Instead the ghosted content is replaced by the reference,
        // brightness-matched to this bracket, so nothing inconsistent is left to blend.
        float toAlt[256];
        for (int v = 0; v < 256; ++v) toAlt[v] = 255.f * srgbEncode(std::min(1.f, srgbDecode(v / 255.f) / ratio));
        auto keepAt = [&](float fx, float fy) {
            const int x0 = std::min(static_cast<int>(fx), mtx - 1), x1 = std::min(x0 + 1, mtx - 1);
            const int y0 = std::min(static_cast<int>(fy), mty - 1), y1 = std::min(y0 + 1, mty - 1);
            const float wx = fx - x0, wy = fy - y0;
            return (keep[y0 * mtx + x0] * (1 - wx) + keep[y0 * mtx + x1] * wx) * (1 - wy) +
                   (keep[y1 * mtx + x0] * (1 - wx) + keep[y1 * mtx + x1] * wx) * wy;
        };
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                const float k = keepAt(static_cast<float>(x) / S, static_cast<float>(y) / S);
                if (k >= 0.999f) continue;
                const float r = ys[ref].at(x, y);
                const float substitute = toAlt[static_cast<int>(std::min(255.f, std::max(0.f, r)) + 0.5f)];
                ys[i].at(x, y) = k * ys[i].at(x, y) + (1 - k) * substitute;
            }
        }
        for (int y = 0; y < CH; ++y) {
            for (int x = 0; x < CW; ++x) {
                const float k = keepAt(2.f * x / S, 2.f * y / S);
                if (k >= 0.999f) continue;
                vs[i].at(x, y) = k * vs[i].at(x, y) + (1 - k) * vs[ref].at(x, y);
                us[i].at(x, y) = k * us[i].at(x, y) + (1 - k) * us[ref].at(x, y);
            }
        }
    });

    // Fusion weights, from the (deghosted) content: favour well-exposed, detailed, colourful pixels.
    parallelFor(n, [&](int i) {
        const Img& y = ys[i];
        const Img& v = vs[i];
        const Img& u = us[i];
        Img w(W, H);
        for (int yy = 0; yy < H; ++yy) {
            for (int xx = 0; xx < W; ++xx) {
                const float c = y.at(xx, yy) / 255.f;
                const float lap = std::fabs(4.f * y.at(xx, yy) - y.at(clampi(xx - 1, 0, W - 1), yy) - y.at(clampi(xx + 1, 0, W - 1), yy) -
                                            y.at(xx, clampi(yy - 1, 0, H - 1)) - y.at(xx, clampi(yy + 1, 0, H - 1))) / 255.f;
                const float wellExposed = std::exp(-(c - 0.5f) * (c - 0.5f) / (2.f * 0.2f * 0.2f));
                const float cv = v.at(xx / 2, yy / 2) - 128.f, cu = u.at(xx / 2, yy / 2) - 128.f;
                const float sat = std::sqrt(cv * cv + cu * cu) / 128.f;
                w.at(xx, yy) = (lap + 0.05f) * wellExposed * (0.5f + sat) + 1e-6f;
            }
        }
        ws[i] = std::move(w);
    });

    // Normalise weights per pixel.
    for (size_t p = 0; p < ws[0].p.size(); ++p) {
        float s = 0;
        for (int i = 0; i < n; ++i) s += ws[i].p[p];
        for (int i = 0; i < n; ++i) ws[i].p[p] /= s;
    }

    const int levels = pyramidLevels(W, H);
    std::vector<Img> result;
    Img chromaW;  // weight of each frame at half resolution, accumulated for chroma
    std::vector<float> accV(static_cast<size_t>(CW) * CH, 0.f), accU(accV.size(), 0.f), accCW(accV.size(), 0.f);
    for (int i = 0; i < n; ++i) {
        // Gaussian pyramid of the weights, Laplacian pyramid of the luma.
        std::vector<Img> gw{ws[i]};
        std::vector<Img> gy{ys[i]};
        Img().p.swap(ws[i].p);
        Img().p.swap(ys[i].p);
        for (int l = 1; l < levels; ++l) {
            gw.push_back(downsample(gw.back()));
            gy.push_back(downsample(gy.back()));
        }
        for (int l = 0; l < levels - 1; ++l) {
            const Img up = upsample(gy[l + 1], gy[l].w, gy[l].h);
            for (size_t p = 0; p < gy[l].p.size(); ++p) gy[l].p[p] -= up.p[p];
        }
        if (result.empty()) {
            for (int l = 0; l < levels; ++l) result.emplace_back(gy[l].w, gy[l].h);
        }
        for (int l = 0; l < levels; ++l) {
            for (size_t p = 0; p < result[l].p.size(); ++p) result[l].p[p] += gw[l].p[p] * gy[l].p[p];
        }
        // Chroma: blend with the smoothed half-resolution weights (pyramid level 1 is CW × CH).
        const Img& wc = levels > 1 ? gw[1] : gw[0];
        for (int yy = 0; yy < CH; ++yy) {
            for (int xx = 0; xx < CW; ++xx) {
                const float w = wc.at(std::min(xx, wc.w - 1), std::min(yy, wc.h - 1));
                const size_t p = static_cast<size_t>(yy) * CW + xx;
                accV[p] += w * vs[i].p[p];
                accU[p] += w * us[i].p[p];
                accCW[p] += w;
            }
        }
        Img().p.swap(vs[i].p);
        Img().p.swap(us[i].p);
    }
    // Collapse the blended pyramid.
    for (int l = levels - 2; l >= 0; --l) {
        const Img up = upsample(result[l + 1], result[l].w, result[l].h);
        for (size_t p = 0; p < result[l].p.size(); ++p) result[l].p[p] += up.p[p];
    }
    mergedY_.swap(result[0].p);
    for (float& v : mergedY_) v = std::min(255.f, std::max(0.f, v));
    for (size_t p = 0; p < accV.size(); ++p) {
        const float w = std::max(accCW[p], 1e-6f);
        accV[p] /= w;
        accU[p] /= w;
    }
    mergedV_.swap(accV);
    mergedU_.swap(accU);
}

void Stack::outputSize(int rotation, int* w, int* h) const {
    const bool swap = rotation == 90 || rotation == 270;
    *w = swap ? height_ : width_;
    *h = swap ? width_ : height_;
}

struct Stack::Prepared {
    std::vector<float> lut;  // 0..255 YUV-domain value → 0..1 output (tone mapped, or linear for DNG)
    int ow = 0, oh = 0, rotation = 0;
    float sharpen = 0;
    float saturation = 1.f;    // chroma scale
    float vShift = 0.f, uShift = 0.f;  // chroma offsets (white-balance pull)
};

void Stack::renderRow(const Prepared& prep, int oy, float* rgb) const {
    const int W = width_, H = height_, CW = W / 2;
    const float* Y = mergedY_.data();
    const int lutMax = static_cast<int>(prep.lut.size()) - 1;
    auto tone = [&](float c) {
        const float f = std::min(std::max(c, 0.f), 255.f) * (lutMax / 255.f);
        const int i = std::min(static_cast<int>(f), lutMax - 1);
        const float t = f - i;
        return prep.lut[i] + (prep.lut[i + 1] - prep.lut[i]) * t;
    };
    for (int ox = 0; ox < prep.ow; ++ox) {
        int sx, sy;
        switch (prep.rotation) {
            case 90: sx = oy; sy = H - 1 - ox; break;
            case 180: sx = W - 1 - ox; sy = H - 1 - oy; break;
            case 270: sx = W - 1 - oy; sy = ox; break;
            default: sx = ox; sy = oy; break;
        }
        float luma = Y[static_cast<size_t>(sy) * W + sx];
        if (prep.sharpen > 0.f) {
            float blur = 0;
            for (int dy = -1; dy <= 1; ++dy) {
                const float* row = Y + static_cast<size_t>(clampi(sy + dy, 0, H - 1)) * W;
                for (int dx = -1; dx <= 1; ++dx) blur += row[clampi(sx + dx, 0, W - 1)];
            }
            luma += prep.sharpen * (luma - blur / 9.f);
        }
        const size_t c = static_cast<size_t>(sy / 2) * CW + sx / 2;
        const float v = (mergedV_[c] - 128.f + prep.vShift) * prep.saturation;
        const float u = (mergedU_[c] - 128.f + prep.uShift) * prep.saturation;
        rgb[3 * ox] = tone(luma + 1.402f * v);
        rgb[3 * ox + 1] = tone(luma - 0.344136f * u - 0.714136f * v);
        rgb[3 * ox + 2] = tone(luma + 1.772f * u);
    }
}

Stack::Prepared Stack::prepare(const RenderParams& p) const {
    Prepared prep;
    prep.rotation = p.rotation;
    prep.sharpen = p.sharpen;
    outputSize(p.rotation, &prep.ow, &prep.oh);
    // Undo the underexposure in linear light, then roll highlights off so the brightest input stays white
    // (extended Reinhard with white point = gain; gain 1 leaves the image unchanged).
    const float gain = std::max(1.f, p.gain);
    const bool bright = p.look == 1;
    prep.lut.resize(4096);
    for (size_t i = 0; i < prep.lut.size(); ++i) {
        const float x = gain * srgbDecode(static_cast<float>(i) / (prep.lut.size() - 1));
        float t = srgbEncode(std::min(1.f, x * (1.f + x / (gain * gain)) / (1.f + x)));
        if (bright) {
            // Lift shadows and midtones (bright, airy), then a mild S-curve so it does not look flat.
            t = std::pow(t, 0.85f);
            const float s = t * t * (3.f - 2.f * t);
            t = t + 0.18f * (s - t);
        }
        prep.lut[i] = t;
    }
    if (bright && !mergedV_.empty()) {
        prep.saturation = 1.08f;
        // Grey-world estimate of the remaining colour cast from mid-grey, unsaturated pixels; a quarter of it
        // is removed, so warm light stays warm but less orange.
        double sv = 0, su = 0;
        long n = 0;
        const int CW = width_ / 2, CH = height_ / 2;
        for (int y = 0; y < CH; y += 8) {
            for (int x = 0; x < CW; x += 8) {
                const size_t c = static_cast<size_t>(y) * CW + x;
                const float yy = mergedY_[static_cast<size_t>(2 * y) * width_ + 2 * x];
                const float dv = mergedV_[c] - 128.f, du = mergedU_[c] - 128.f;
                if (yy < 40.f || yy > 230.f || dv * dv + du * du > 30.f * 30.f) continue;
                sv += dv;
                su += du;
                ++n;
            }
        }
        if (n > 100) {
            prep.vShift = -0.25f * static_cast<float>(sv / n);
            prep.uShift = -0.25f * static_cast<float>(su / n);
        }
    }
    return prep;
}

void Stack::render(const RenderParams& p, const std::function<void(int, const float*)>& emit) const {
    if (!processed()) return;
    const Prepared prep = prepare(p);
    std::vector<float> row(static_cast<size_t>(prep.ow) * 3);
    for (int oy = 0; oy < prep.oh; ++oy) {
        renderRow(prep, oy, row.data());
        emit(oy, row.data());
    }
}

bool Stack::writeLinearDng(const std::string& path, const RenderParams& p, const std::string& make,
                           const std::string& model) const {
    if (!processed()) return false;
    RenderParams linear = p;
    linear.look = 0;  // no look in the DNG: Lightroom does the styling
    Prepared prep = prepare(linear);
    prep.sharpen = 0.f;  // leave sharpening to Lightroom
    for (size_t i = 0; i < prep.lut.size(); ++i) prep.lut[i] = srgbDecode(static_cast<float>(i) / (prep.lut.size() - 1));
    const double baselineExposure = std::log2(std::max(1.f, p.gain));
    // XYZ (D65) → linear sRGB, i.e. the "camera" space of this data.
    const std::vector<double> xyzToSrgb = {3.2404542, -1.5371385, -0.4985314, -0.9692660, 1.8760108,
                                           0.0415560, 0.0556434, -0.2040259, 1.0572252};
    std::vector<TiffTag> tags = {
        TiffTag::longs(254, {0}),                                   // main image
        TiffTag::ascii(271, make),
        TiffTag::ascii(272, model),
        TiffTag::ascii(305, "A56Cam"),
        TiffTag::bytes(50706, {1, 4, 0, 0}),                        // DNGVersion
        TiffTag::bytes(50707, {1, 1, 0, 0}),                        // DNGBackwardVersion
        TiffTag::ascii(50708, make + " " + model + " A56Cam linear"),  // UniqueCameraModel
        TiffTag::longs(50717, {65535, 65535, 65535}),               // WhiteLevel
        TiffTag::rationals(50721, xyzToSrgb, true),                 // ColorMatrix1
        TiffTag::rationals(50728, {1, 1, 1}, false),                // AsShotNeutral: already white balanced
        TiffTag::rationals(50730, {baselineExposure}, true),        // BaselineExposure
        TiffTag::shorts(50778, {21}),                               // CalibrationIlluminant1 = D65
    };
    return writeTiff(path, prep.ow, prep.oh, 34892 /* LinearRaw */, std::move(tags), rowsInParallel(prep, 65535.f));
}

bool Stack::writeTiff16(const std::string& path, const RenderParams& p) const {
    if (!processed()) return false;
    const Prepared prep = prepare(p);
    // Rendered straight into the file row by row: no full 16-bit image is held in memory.
    return writeTiffRgb16(path, prep.ow, prep.oh, rowsInParallel(prep, 65535.f));
}

void Stack::renderRgba8(uint8_t* dst, int strideBytes, const RenderParams& p) const {
    if (!processed()) return;
    const Prepared prep = prepare(p);
    parallelFor(prep.oh, [&](int y) {
        std::vector<float> rgb(static_cast<size_t>(prep.ow) * 3);
        renderRow(prep, y, rgb.data());
        uint8_t* row = dst + static_cast<size_t>(y) * strideBytes;
        for (int x = 0; x < prep.ow; ++x) {
            row[4 * x] = static_cast<uint8_t>(std::lround(rgb[3 * x] * 255.f));
            row[4 * x + 1] = static_cast<uint8_t>(std::lround(rgb[3 * x + 1] * 255.f));
            row[4 * x + 2] = static_cast<uint8_t>(std::lround(rgb[3 * x + 2] * 255.f));
            row[4 * x + 3] = 255;
        }
    });
}

std::function<void(int, uint16_t*)> Stack::rowsInParallel(const Prepared& prep, float scale) const {
    // The TIFF writer asks for rows in order; render blocks of 64 rows in parallel ahead of it.
    struct Block {
        int first = -1;
        std::vector<uint16_t> data;
    };
    auto block = std::make_shared<Block>();
    return [this, &prep, scale, block](int y, uint16_t* out) {
        const int rows = 64, rowLen = prep.ow * 3;
        if (block->first < 0 || y < block->first || y >= block->first + rows) {
            block->first = y - y % rows;
            const int count = std::min(rows, prep.oh - block->first);
            block->data.assign(static_cast<size_t>(rows) * rowLen, 0);
            parallelFor(count, [&](int k) {
                std::vector<float> rgb(static_cast<size_t>(rowLen));
                renderRow(prep, block->first + k, rgb.data());
                uint16_t* dst = block->data.data() + static_cast<size_t>(k) * rowLen;
                for (int i = 0; i < rowLen; ++i) dst[i] = static_cast<uint16_t>(std::lround(std::min(1.f, std::max(0.f, rgb[i])) * scale));
            });
        }
        std::memcpy(out, block->data.data() + static_cast<size_t>(y - block->first) * rowLen, rowLen * sizeof(uint16_t));
    };
}

std::vector<uint8_t> Stack::frameNv21(int index) const {
    if (index < 0 || index >= count()) return {};
    const Frame& f = frames_[index];
    std::vector<uint8_t> out(f.y.size() + f.vu.size());
    std::memcpy(out.data(), f.y.data(), f.y.size());
    std::memcpy(out.data() + f.y.size(), f.vu.data(), f.vu.size());
    return out;
}

namespace {
void put16(std::vector<uint8_t>& b, uint16_t v) { b.push_back(v & 0xFF); b.push_back(v >> 8); }
void put32(std::vector<uint8_t>& b, uint32_t v) { for (int i = 0; i < 4; ++i) b.push_back((v >> (8 * i)) & 0xFF); }
}  // namespace

TiffTag TiffTag::shorts(uint16_t tag, std::initializer_list<uint16_t> v) {
    TiffTag t{tag, 3, static_cast<uint32_t>(v.size()), {}};
    for (uint16_t x : v) put16(t.data, x);
    return t;
}
TiffTag TiffTag::longs(uint16_t tag, std::initializer_list<uint32_t> v) {
    TiffTag t{tag, 4, static_cast<uint32_t>(v.size()), {}};
    for (uint32_t x : v) put32(t.data, x);
    return t;
}
TiffTag TiffTag::bytes(uint16_t tag, std::initializer_list<uint8_t> v) {
    return TiffTag{tag, 1, static_cast<uint32_t>(v.size()), std::vector<uint8_t>(v)};
}
TiffTag TiffTag::ascii(uint16_t tag, const std::string& s) {
    TiffTag t{tag, 2, static_cast<uint32_t>(s.size() + 1), std::vector<uint8_t>(s.begin(), s.end())};
    t.data.push_back(0);
    return t;
}
TiffTag TiffTag::rationals(uint16_t tag, const std::vector<double>& v, bool isSigned) {
    TiffTag t{tag, static_cast<uint16_t>(isSigned ? 10 : 5), static_cast<uint32_t>(v.size()), {}};
    for (double x : v) {
        const int32_t num = static_cast<int32_t>(std::lround(x * 10000.0));
        put32(t.data, static_cast<uint32_t>(num));
        put32(t.data, 10000);
    }
    return t;
}

bool writeTiff(const std::string& path, int width, int height, uint16_t photometric,
               std::vector<TiffTag> extra, const std::function<void(int, uint16_t*)>& row) {
    const uint64_t dataBytes = static_cast<uint64_t>(width) * height * 6;
    if (dataBytes > 0xFFFFFFFFull) return false;
    std::vector<TiffTag> tags = std::move(extra);
    tags.push_back(TiffTag::longs(256, {static_cast<uint32_t>(width)}));
    tags.push_back(TiffTag::longs(257, {static_cast<uint32_t>(height)}));
    tags.push_back(TiffTag::shorts(258, {16, 16, 16}));
    tags.push_back(TiffTag::shorts(259, {1}));                 // no compression
    tags.push_back(TiffTag::shorts(262, {photometric}));
    tags.push_back(TiffTag::longs(273, {0}));                  // StripOffsets, patched below
    tags.push_back(TiffTag::shorts(274, {1}));                 // top-left orientation
    tags.push_back(TiffTag::shorts(277, {3}));
    tags.push_back(TiffTag::longs(278, {static_cast<uint32_t>(height)}));
    tags.push_back(TiffTag::longs(279, {static_cast<uint32_t>(dataBytes)}));
    tags.push_back(TiffTag::shorts(284, {1}));                 // interleaved
    std::sort(tags.begin(), tags.end(), [](const TiffTag& a, const TiffTag& b) { return a.tag < b.tag; });

    // Layout: header, IFD, then out-of-line values (> 4 bytes), then pixels.
    const uint32_t ifdSize = 2 + static_cast<uint32_t>(tags.size()) * 12 + 4;
    uint32_t blobOff = 8 + ifdSize;
    std::vector<uint32_t> offsets(tags.size(), 0);
    for (size_t i = 0; i < tags.size(); ++i) {
        if (tags[i].data.size() > 4) {
            offsets[i] = blobOff;
            blobOff += static_cast<uint32_t>(tags[i].data.size());
            blobOff += blobOff & 1;
        }
    }
    const uint32_t dataOff = blobOff;
    for (TiffTag& t : tags) {
        if (t.tag == 273) {
            t.data.clear();
            put32(t.data, dataOff);
        }
    }

    std::vector<uint8_t> head;
    head.insert(head.end(), {'I', 'I', 42, 0});
    put32(head, 8);
    put16(head, static_cast<uint16_t>(tags.size()));
    for (size_t i = 0; i < tags.size(); ++i) {
        const TiffTag& t = tags[i];
        put16(head, t.tag);
        put16(head, t.type);
        put32(head, t.count);
        if (t.data.size() > 4) {
            put32(head, offsets[i]);
        } else {
            std::vector<uint8_t> inl = t.data;
            inl.resize(4, 0);
            head.insert(head.end(), inl.begin(), inl.end());
        }
    }
    put32(head, 0);
    for (size_t i = 0; i < tags.size(); ++i) {
        if (tags[i].data.size() <= 4) continue;
        head.insert(head.end(), tags[i].data.begin(), tags[i].data.end());
        if (head.size() & 1) head.push_back(0);
    }
    if (head.size() != dataOff) return false;

    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    bool ok = std::fwrite(head.data(), 1, head.size(), f) == head.size();
    std::vector<uint16_t> buf(static_cast<size_t>(width) * 3);
    for (int y = 0; ok && y < height; ++y) {
        row(y, buf.data());
        ok = std::fwrite(buf.data(), sizeof(uint16_t), buf.size(), f) == buf.size();  // little-endian host
    }
    ok = (std::fclose(f) == 0) && ok;
    return ok;
}

bool writeTiffRgb16(const std::string& path, int width, int height,
                    const std::function<void(int, uint16_t*)>& row) {
    return writeTiff(path, width, height, 2,
                     {TiffTag::rationals(282, {72}, false), TiffTag::rationals(283, {72}, false),
                      TiffTag::shorts(296, {2}), TiffTag::ascii(305, "A56Cam")},
                     row);
}

}  // namespace a56
