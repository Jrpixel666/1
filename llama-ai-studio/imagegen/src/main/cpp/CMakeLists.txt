cmake_minimum_required(VERSION 3.22.1)
project("sd_bridge" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(SD_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/stable-diffusion.cpp)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI_DEFAULT ON)
else()
    set(GGML_CPU_KLEIDIAI_DEFAULT OFF)
endif()

# This module vendors its own copy of ggml (stable-diffusion.cpp pins its own fork, which
# can drift from llama.cpp's). Building everything here as STATIC libraries means this
# module contributes exactly one shared object (sd_bridge) to the APK — if ggml/stable-
# diffusion built as shared libs instead, their default output names (libggml.so,
# libggml-base.so, libggml-cpu.so, ...) would collide by filename with the llama.cpp
# module's own identically-named shared libs when both land in lib/arm64-v8a/.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_CUDA OFF CACHE BOOL "" FORCE)
set(SD_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI ${GGML_CPU_KLEIDIAI_DEFAULT} CACHE BOOL "" FORCE)
# OpenMP is left off here (unlike the llama module) solely to avoid a second module
# independently pulling in the NDK's libomp.so shared runtime; ggml's own pthread pool
# still parallelises the CPU backend fine for image generation.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

add_subdirectory(${SD_SRC} sd_build)

add_library(sd_bridge SHARED
    sd_bridge.cpp
    jni_utils.cpp
)

target_include_directories(sd_bridge PRIVATE
    ${SD_SRC}/include
    ${SD_SRC}/thirdparty
    ${SD_SRC}/ggml/include
)

target_link_libraries(sd_bridge PRIVATE
    stable-diffusion
    android
    log
)

target_compile_options(sd_bridge PRIVATE -Wall -Wextra -Wno-unused-parameter)
