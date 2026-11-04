cmake_minimum_required(VERSION 3.22.1)
project("llama_bridge" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LLAMA_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

# KleidiAI only builds for AArch64 targets; ggml itself guards the option, but we only
# turn it on for the one ABI this app ships (Pixel 9 / Tensor G4 is arm64-v8a only).
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI_DEFAULT ON)
else()
    set(GGML_CPU_KLEIDIAI_DEFAULT OFF)
endif()
option(GGML_CPU_KLEIDIAI "" ${GGML_CPU_KLEIDIAI_DEFAULT})

set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP ON CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} llama_build)

add_library(llama_bridge SHARED
    llama_bridge.cpp
    jni_utils.cpp
)

target_include_directories(llama_bridge PRIVATE
    ${LLAMA_SRC}
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/common
    ${LLAMA_SRC}/ggml/include
)

target_link_libraries(llama_bridge PRIVATE
    llama
    llama-common
    vendor::nlohmann
    android
    log
)

target_compile_options(llama_bridge PRIVATE -Wall -Wextra -Wno-unused-parameter)
