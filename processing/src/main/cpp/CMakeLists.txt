cmake_minimum_required(VERSION 3.22.1)
project(a56processing CXX)

add_library(a56processing SHARED a56processing.cpp)
find_library(log-lib log)
target_link_libraries(a56processing ${log-lib})
