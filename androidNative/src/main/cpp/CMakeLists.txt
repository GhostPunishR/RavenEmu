cmake_minimum_required(VERSION 3.22.1)
project(RavenEmuAndroidNative LANGUAGES C CXX)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(RAVEN_FRONTEND_TESTS OFF CACHE BOOL "" FORCE)
# Use exactly the same RavenCore commit and Libretro frontend as host tests.
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../../../../frontend"
                 "${CMAKE_CURRENT_BINARY_DIR}/raven_frontend")
# FetchContent declares ravencore in the frontend subdirectory. Look up the
# populated path in this parent directory (directory variables are scoped).
include(FetchContent)
FetchContent_GetProperties(ravencore)

add_library(raven_android SHARED bridge.c native_audio.c native_menu.c)
target_compile_features(raven_android PRIVATE c_std_11)
target_link_libraries(raven_android PRIVATE raven_frontend GLESv2 aaudio log m)
target_include_directories(raven_android PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../../../frontend/include"
    "${ravencore_SOURCE_DIR}/libretro/include")
add_dependencies(raven_android
    ravenemu_gb_libretro ravenemu_gbc_libretro ravenemu_gba_libretro)

# Gradle explicitly packages the JNI library and three Libretro core .so files.
# The frontend finds them through ApplicationInfo.nativeLibraryDir and dlopen.
