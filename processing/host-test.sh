#!/usr/bin/env bash
# Builds and runs the native stacking tests on this machine (no Android SDK needed).
# The GPU merge test also runs when OpenGL ES headers and Mesa are installed
# (apt-get install libegl-dev libgles-dev libegl-mesa0 libgl1-mesa-dri).
set -euo pipefail
cd "$(dirname "$0")"
out="${TMPDIR:-/tmp}/a56_stack_test"
c++ -std=c++17 -O2 -Wall -Wextra -pthread -o "$out" src/main/cpp/stack.cpp src/test/cpp/stack_test.cpp
"$out"
if [ -f /usr/include/GLES3/gl31.h ] && [ -f /usr/include/EGL/egl.h ]; then
    gpu="${TMPDIR:-/tmp}/a56_gpu_test"
    c++ -std=c++17 -O2 -Wall -Wextra -pthread -o "$gpu" src/main/cpp/stack.cpp src/main/cpp/gpu_merge.cpp \
        src/test/cpp/gpu_test.cpp -lEGL -lGLESv2
    "$gpu"
else
    echo "SKIP GPU merge test: OpenGL ES headers not installed"
fi
