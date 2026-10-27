cmake_minimum_required(VERSION 3.22)
project(pinlink_whisper)

# whisper.cpp is a pinned git submodule (third_party/whisper.cpp, MIT).
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# ggml's own thread pool; avoids bundling the NDK's OpenMP runtime.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Owner's phone (Snapdragon 7s Gen 2, Cortex-A78/A55) has fp16 + dotprod.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif ()

add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

add_library(pinlink_whisper SHARED whisper_jni.cpp)
target_link_libraries(pinlink_whisper whisper log)
if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(pinlink_whisper PRIVATE -O3)
endif ()
