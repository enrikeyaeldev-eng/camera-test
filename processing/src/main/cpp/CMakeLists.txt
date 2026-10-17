cmake_minimum_required(VERSION 3.22.1)
project(a56processing CXX)

add_library(a56processing SHARED a56processing.cpp stack_jni.cpp stack.cpp gpu_merge.cpp)
target_compile_options(a56processing PRIVATE -O3 -Wall)
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(a56processing ${log-lib} ${jnigraphics-lib} EGL GLESv3)
