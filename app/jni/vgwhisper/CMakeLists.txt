# Vibegram X: libvgwhisper, the JNI bridge to whisper.cpp (see vgwhisper.cpp).
# Built by scripts/stt/build_vgwhisper.sh, not by the Gradle build: the result is committed to
# app/src/main/jniLibs/arm64-v8a. Without ANDROID it builds a desktop library for testing with a desktop JDK.

cmake_minimum_required(VERSION 3.22)
project(vgwhisper C CXX)

set(WHISPER_DIR "" CACHE PATH "whisper.cpp v1.9.5 source tree")
set(VGWHISPER_NAME "vgwhisper" CACHE STRING "Library name: vgwhisper, vgwhisper_v82 or vgwhisper_v86")
if (NOT EXISTS "${WHISPER_DIR}/include/whisper.h")
  message(FATAL_ERROR "WHISPER_DIR must point to the whisper.cpp v1.9.5 sources")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# Build paths must not end up in the binary (GGML_ASSERT and friends print __FILE__)
get_filename_component(VGWHISPER_WHISPER_DIR "${WHISPER_DIR}" REALPATH)
add_compile_options(
  -ffunction-sections -fdata-sections
  "-ffile-prefix-map=${VGWHISPER_WHISPER_DIR}=whisper.cpp"
  "-ffile-prefix-map=${WHISPER_DIR}=whisper.cpp"
  "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=vgwhisper"
  "-ffile-prefix-map=${CMAKE_BINARY_DIR}=build"
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
# CPU only, without OpenMP: one backend, no extra runtime libraries in the APK
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
if (ANDROID)
  # the instruction set is chosen by GGML_CPU_ARM_ARCH, one library per CPU generation
  set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${WHISPER_DIR}" whisper EXCLUDE_FROM_ALL)

add_library(vgwhisper SHARED vgwhisper.cpp)
set_target_properties(vgwhisper PROPERTIES OUTPUT_NAME "${VGWHISPER_NAME}")
target_link_libraries(vgwhisper PRIVATE whisper)
if (ANDROID)
  target_link_libraries(vgwhisper PRIVATE log)
  target_link_options(vgwhisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
  find_package(JNI REQUIRED)
  target_include_directories(vgwhisper PRIVATE ${JNI_INCLUDE_DIRS})
endif()
