// GPU (OpenGL ES 3.1 compute) implementation of the same-exposure merge; see MergeAccelerator in stack.h.
#pragma once

#include <memory>
#include <mutex>
#include <string>

#include "stack.h"

namespace a56 {

class GpuMerger : public MergeAccelerator {
public:
    // Creates a headless EGL context; returns nullptr (with the reason in *error) if the device cannot.
    static std::unique_ptr<GpuMerger> create(std::string* error);
    ~GpuMerger() override;

    bool begin(const MergeJob& job) override;
    bool tileMismatch(int frame, float* out) override;
    bool accumulate(const std::vector<float>& weight, std::vector<float>& y, std::vector<float>& v,
                    std::vector<float>& u) override;
    void end() override;

    const std::string& lastError() const { return error_; }
    const std::string& renderer() const { return renderer_; }

private:
    GpuMerger() = default;
    struct Gl;
    bool fail(const char* what);
    bool uploadFrame(int frame, bool chroma);

    std::unique_ptr<Gl> gl_;
    std::mutex mutex_;  // one merge at a time; the context is current only between begin() and end()
    bool active_ = false;
    const MergeJob* job_ = nullptr;
    std::string error_;
    std::string renderer_;
};

// Merges a small synthetic burst on the CPU and with [gpu] and compares the results. False (with the
// largest difference in *report) means the GPU must not be used.
bool gpuSelfTest(GpuMerger& gpu, std::string* report);

}  // namespace a56
