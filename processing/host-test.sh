#!/usr/bin/env bash
# Builds and runs the native stacking tests on this machine (no Android SDK needed).
set -euo pipefail
cd "$(dirname "$0")"
out="${TMPDIR:-/tmp}/a56_stack_test"
c++ -std=c++17 -O2 -Wall -Wextra -pthread -o "$out" src/main/cpp/stack.cpp src/test/cpp/stack_test.cpp
"$out"
