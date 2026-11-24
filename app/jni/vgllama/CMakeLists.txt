# Vibegram X: libvgllama, the JNI bridge to llama.cpp (see vgllama.cpp).
# Built by scripts/stt/build_vgllama.sh, not by the Gradle build: the result is committed to
# app/src/main/jniLibs/arm64-v8a. Without ANDROID it builds vgllama_test, a desktop program for checking answers.

cmake_minimum_required(VERSION 3.22)
project(vgllama C CXX)

set(LLAMA_DIR "" CACHE PATH "llama.cpp b11960 source tree")
set(VGLLAMA_NAME "vgllama" CACHE STRING "Library name: vgllama, vgllama_v82 or vgllama_v86")
if (NOT EXISTS "${LLAMA_DIR}/include/llama.h")
  message(FATAL_ERROR "LLAMA_DIR must point to the llama.cpp b11960 sources")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# Build paths must not end up in the binary (GGML_ASSERT and friends print __FILE__)
get_filename_component(VGLLAMA_LLAMA_DIR "${LLAMA_DIR}" REALPATH)
add_compile_options(
  -ffunction-sections -fdata-sections
  "-ffile-prefix-map=${VGLLAMA_LLAMA_DIR}=llama.cpp"
  "-ffile-prefix-map=${LLAMA_DIR}=llama.cpp"
  "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=vgllama"
  "-ffile-prefix-map=${CMAKE_BINARY_DIR}=build"
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
# CPU only, without OpenMP: one backend, no extra runtime libraries in the APK
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
if (ANDROID)
  # the instruction set is chosen by GGML_CPU_ARM_ARCH, one library per CPU generation
  set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${LLAMA_DIR}" llama EXCLUDE_FROM_ALL)

if (ANDROID)
  add_library(vgllama SHARED vgllama.cpp)
  set_target_properties(vgllama PROPERTIES OUTPUT_NAME "${VGLLAMA_NAME}")
  target_link_libraries(vgllama PRIVATE llama log)
  # ggml is linked statically here and in libvgwhisper: --exclude-libs keeps the two copies from clashing
  target_link_options(vgllama PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
  add_executable(vgllama_test vgllama.cpp)
  target_compile_definitions(vgllama_test PRIVATE VG_HOST_TEST)
  target_link_libraries(vgllama_test PRIVATE llama)
endif()
