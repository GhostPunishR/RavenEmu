cmake_minimum_required(VERSION 3.22.1)
project(RavenEmuAndroidNative LANGUAGES C CXX)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(RAVEN_FRONTEND_TESTS OFF CACHE BOOL "" FORCE)
# Use exactly the same RavenCore commit and Libretro frontend as host tests.
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../../../../frontend"
                 "${CMAKE_CURRENT_BINARY_DIR}/raven_frontend")

add_library(raven_android SHARED bridge.c)
target_compile_features(raven_android PRIVATE c_std_11)
target_link_libraries(raven_android PRIVATE raven_frontend GLESv2 log)
target_include_directories(raven_android PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../../../frontend/include")
add_dependencies(raven_android
    ravenemu_gb_libretro ravenemu_gbc_libretro ravenemu_gba_libretro)

# Android package includes all four shared libraries (the three cores and
# lib r a v e n _ a n d r o i d). The cores are dynamically opened via dlopen.
