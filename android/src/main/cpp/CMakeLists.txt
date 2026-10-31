cmake_minimum_required(VERSION 3.22.1)
project(RavenEmuAndroid LANGUAGES C CXX)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(RAVEN_FRONTEND_TESTS OFF CACHE BOOL "" FORCE)

# The dev APK is Gradle's debug variant, whose native code the NDK builds at
# -O0: the cores then run about six times slower (RavenCore's GBA core, host
# measure: 14.7 ms a frame against 2.4 ms at -O2, for a 16.7 ms budget), and
# every game crawls on a phone. As the beta does in native/CMakeLists.txt,
# keep the debug symbols but optimise. Set before the frontend is added, so
# that it, rcheevos and the RavenCore cores fetched under it inherit it; the
# last -O wins. scripts/check_native_optimization.py checks it in CI.
add_compile_options("$<$<CONFIG:Debug>:-O2>")
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../../../../frontend"
                 "${CMAKE_CURRENT_BINARY_DIR}/raven_frontend")
include(FetchContent)
FetchContent_GetProperties(ravencore)

add_library(raven_android SHARED
    jni/bridge.c
    jni/achievements_bridge.c
    hardware/audio/audio_pipeline.c
    hardware/audio/sinc_resampler.c
    hardware/audio/aaudio/aaudio_driver.c
    hardware/video/gles2/gles2_renderer.c
    hardware/input/input_driver.c
    hardware/input/sensor_driver.c
    media/state_thumbnail.c)
target_compile_features(raven_android PRIVATE c_std_11)
target_link_libraries(raven_android PRIVATE raven_frontend raven_achievements GLESv2 aaudio android log m)
target_include_directories(raven_android PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/hardware/audio"
    "${CMAKE_CURRENT_LIST_DIR}/hardware/audio/aaudio"
    "${CMAKE_CURRENT_LIST_DIR}/hardware/video/gles2"
    "${CMAKE_CURRENT_LIST_DIR}/hardware/input"
    "${CMAKE_CURRENT_LIST_DIR}/media"
    "${CMAKE_CURRENT_LIST_DIR}/../../../../frontend/include"
    "${ravencore_SOURCE_DIR}/libretro/include")
add_dependencies(raven_android ravenemu_gb_libretro ravenemu_gbc_libretro ravenemu_gba_libretro)
