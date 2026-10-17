// Burst stacking core: align and merge full-resolution YUV frames, then tone map.
// Plain C++17 with no Android dependencies, so it is unit-tested on the build machine.
#pragma once

#include <cstdint>
#include <functional>
#include <initializer_list>
#include <string>
#include <vector>

namespace a56 {

struct StackStats {
    int frames = 0;
    int reference = -1;
    std::vector<float> sharpness;
    // Merge tile grid and, per tile, the mean weight given to the non-reference frames (0..1).
    int tilesX = 0;
    int tilesY = 0;
    std::vector<float> tileWeight;
    // Per frame: median tile mismatch (noise estimate) and mean |displacement| in pixels.
    std::vector<float> noise;
    std::vector<int> rejected;  // frames left out (blurred by shake)
    std::vector<float> meanShift;
    // Per frame: median tile shift in pixels (the hand-shake relative to the reference).
    std::vector<int> medianDx;
    std::vector<int> medianDy;
    double alignMs = 0;
    double mergeMs = 0;
    std::string engine = "cpu";  // what ran the merge: "cpu" or "gpu"
};

// Input of the same-exposure merge, for a MergeAccelerator. Frames are luma (W×H) and NV21 chroma (CW×CH
// V,U pairs); the merge works on mtx × mty overlapping tiles (32 px, step 16; chroma 16 px, step 8).
struct MergeJob {
    int W = 0, H = 0, CW = 0, CH = 0, mtx = 0, mty = 0, n = 0, ref = 0;
    std::vector<const uint8_t*> y, vu;
    // Per frame and tile: luma dx, dy and chroma dx, dy (4 ints per tile, row-major tiles).
    std::vector<std::vector<int32_t>> shifts;
};

// Optional accelerator (the GPU) for the same-exposure merge. It must compute what the CPU merge computes;
// any false return makes the merge fall back to the CPU.
class MergeAccelerator {
public:
    virtual ~MergeAccelerator() = default;
    virtual bool begin(const MergeJob& job) = 0;
    // Mean |reference - frame| over each tile (mtx*mty values), with the frame shifted per tile.
    virtual bool tileMismatch(int frame, float* out) = 0;
    // Weighted, windowed sum of all frames (weight: n*mtx*mty), normalised: luma W*H, chroma CW*CH each.
    virtual bool accumulate(const std::vector<float>& weight, std::vector<float>& y, std::vector<float>& v,
                            std::vector<float>& u) = 0;
    virtual void end() = 0;
};

struct ProcessParams {
    // Night: frames less sharp than this fraction of the sharpest (shake blur) are left out. 0 keeps all.
    float minSharpness = 0.f;
    // HDR: frames have different exposures and are combined by exposure fusion instead of averaging.
    bool hdr = false;
    // Runs the merge on the GPU when set; nullptr or any failure uses the CPU.
    MergeAccelerator* accelerator = nullptr;
};

struct RenderParams {
    float gain = 2.0f;     // linear gain that undoes the burst's underexposure
    float sharpen = 0.4f;  // unsharp-mask amount on luma
    int rotation = 0;      // clockwise: 0, 90, 180 or 270
    // 0 = natural; 1 = bright, iPhone-style: local tone mapping (dark areas lifted, bright areas held back),
    // lifted midtones, gentle contrast, slightly less saturation and the white balance pulled halfway to neutral.
    int look = 0;
};

class Stack {
public:
    Stack(int width, int height, int capacity);

    int width() const { return width_; }
    int height() const { return height_; }
    int count() const { return static_cast<int>(frames_.size()); }

    // Copies one YUV_420_888 frame (any row/pixel strides) into owned memory. False when full.
    bool addFrame(const uint8_t* y, int yRowStride, int yPixelStride,
                  const uint8_t* u, const uint8_t* v, int uvRowStride, int uvPixelStride,
                  int64_t timestampNs, double exposure = 1.0);

    // Picks a reference, aligns the others to it and merges (or, for HDR, fuses) them. Frames are freed afterwards.
    StackStats process(const ProcessParams& params = {});
    bool processed() const { return !mergedY_.empty(); }

    void outputSize(int rotation, int* w, int* h) const;
    // Calls emit(row, rgb) for every output row, top to bottom; rgb holds width*3 values in 0..1.
    void render(const RenderParams& p, const std::function<void(int, const float*)>& emit) const;
    bool writeTiff16(const std::string& path, const RenderParams& p) const;
    // Linear DNG for Lightroom: linear sRGB-primaries data, no tone curve or sharpening; the brightness
    // correction goes into BaselineExposure so highlights keep their headroom.
    bool writeLinearDng(const std::string& path, const RenderParams& p, const std::string& make,
                        const std::string& model) const;
    void renderRgba8(uint8_t* dst, int strideBytes, const RenderParams& p) const;

    // NV21 copy of a stored frame (debug output); empty once frames are freed.
    std::vector<uint8_t> frameNv21(int index) const;

    // The merged planes (0..255), for tests that compare merge engines.
    const std::vector<float>& mergedY() const { return mergedY_; }
    const std::vector<float>& mergedV() const { return mergedV_; }
    const std::vector<float>& mergedU() const { return mergedU_; }

private:
    struct Frame {
        std::vector<uint8_t> y;   // width*height
        std::vector<uint8_t> vu;  // NV21 chroma: (width/2)*(height/2) V,U pairs
        int64_t timestamp = 0;
        double exposure = 1.0;   // exposure time × ISO, relative between frames
    };
    struct Level {
        int w = 0, h = 0;
        std::vector<uint8_t> own;        // downsampled levels own their pixels
        const uint8_t* ext = nullptr;    // level 0 points at the frame itself
        const uint8_t* data() const { return ext ? ext : own.data(); }
    };
    struct Prepared;
    Prepared prepare(const RenderParams& p) const;
    void renderRow(const Prepared& prep, int oy, float* rgb) const;
    std::function<void(int, uint16_t*)> rowsInParallel(const Prepared& prep, float scale) const;
    struct Shift {
        int dx = 0, dy = 0;
    };

    std::vector<Level> pyramid(const Frame& f) const;
    struct Alignment;
    // Brings frame i to the reference exposure in place and marks the merge tiles it had clipped.
    void matchExposure(int i, double refExposure, const Alignment& al);
    void mergeRobust(const Alignment& al, const std::vector<int>& rejected, StackStats& st, MergeAccelerator* accel);
    void fuseExposures(const Alignment& al, StackStats& st);
    std::vector<Shift> align(const std::vector<Level>& ref, const std::vector<Level>& alt, int* tilesX0, int* tilesY0) const;
    float sharpness(const Frame& f) const;

    int width_, height_, capacity_;
    std::vector<Frame> frames_;
    std::vector<std::vector<char>> clipTiles_;  // per frame: merge tiles it had clipped (other exposure only)
    std::vector<float> mergedY_;   // width*height, 0..255
    std::vector<float> mergedV_;   // (width/2)*(height/2)
    std::vector<float> mergedU_;
};

// One TIFF directory entry; data holds the little-endian value bytes.
struct TiffTag {
    uint16_t tag;
    uint16_t type;   // 1 BYTE, 2 ASCII, 3 SHORT, 4 LONG, 5 RATIONAL, 10 SRATIONAL
    uint32_t count;
    std::vector<uint8_t> data;
    static TiffTag shorts(uint16_t tag, std::initializer_list<uint16_t> v);
    static TiffTag longs(uint16_t tag, std::initializer_list<uint32_t> v);
    static TiffTag bytes(uint16_t tag, std::initializer_list<uint8_t> v);
    static TiffTag ascii(uint16_t tag, const std::string& s);
    static TiffTag rationals(uint16_t tag, const std::vector<double>& v, bool isSigned);
};

// Writes a little-endian, uncompressed, 3 × 16-bit-per-pixel TIFF (one strip) with the given photometric
// interpretation plus extra tags. row(y, out) fills width*3 values for row y.
bool writeTiff(const std::string& path, int width, int height, uint16_t photometric,
               std::vector<TiffTag> extra, const std::function<void(int, uint16_t*)>& row);

// Writes a baseline little-endian RGB TIFF with 16 bits per sample, one strip.
// row(y, out) must fill width*3 uint16 values for row y.
bool writeTiffRgb16(const std::string& path, int width, int height,
                    const std::function<void(int, uint16_t*)>& row);

}  // namespace a56
