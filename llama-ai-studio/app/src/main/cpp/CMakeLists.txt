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

# --- Vulkan GPU backend (Mali-G715 on Tensor G4, any Vulkan 1.2+ GPU) --------------------
# The NDK's Vulkan headers are C-only and older than ggml-vulkan needs, so it compiles against
# the vendored Khronos headers, links the NDK's libvulkan.so stub (the device's real loader at
# runtime) and compiles its shaders with the NDK's glslc. ggml builds its vulkan-shaders-gen
# tool for the *host* automatically when cross-compiling.
set(VULKAN_THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/vulkan)
option(LLAMA_STUDIO_VULKAN "Build ggml's Vulkan backend" ON)
if (LLAMA_STUDIO_VULKAN AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(Vulkan_INCLUDE_DIR ${VULKAN_THIRD_PARTY}/Vulkan-Headers/include CACHE PATH "" FORCE)
    find_library(LLAMA_STUDIO_LIBVULKAN vulkan REQUIRED)
    set(Vulkan_LIBRARY ${LLAMA_STUDIO_LIBVULKAN} CACHE FILEPATH "" FORCE)
    file(GLOB LLAMA_STUDIO_GLSLC "${ANDROID_NDK}/shader-tools/*/glslc")
    list(GET LLAMA_STUDIO_GLSLC 0 LLAMA_STUDIO_GLSLC)
    set(Vulkan_GLSLC_EXECUTABLE ${LLAMA_STUDIO_GLSLC} CACHE FILEPATH "" FORCE)
    set(SPIRV-Headers_DIR ${VULKAN_THIRD_PARTY}/cmake CACHE PATH "" FORCE)
    message(STATUS "Vulkan: headers=${Vulkan_INCLUDE_DIR} lib=${Vulkan_LIBRARY} glslc=${Vulkan_GLSLC_EXECUTABLE}")
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_SRC} llama_build)

if (TARGET llama-common)
    # The app-wide -fvisibility=hidden (cppFlags) would hide every llama-common symbol, but the
    # bridge calls common_fit_params (GPU + CPU auto-fit) from it. Later flag wins.
    target_compile_options(llama-common PRIVATE -fvisibility=default)
endif()

if (TARGET ggml-vulkan)
    # Upstream expects SPIRV-Headers on the Vulkan SDK include path; we vendor them separately.
    target_include_directories(ggml-vulkan PRIVATE ${VULKAN_THIRD_PARTY}/SPIRV-Headers/include)
endif()

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
