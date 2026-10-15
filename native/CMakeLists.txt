cmake_minimum_required(VERSION 3.22.1)
project(RavenEmuNative LANGUAGES C CXX)

# Lock the emulator engines AND public C interface to a reviewed source commit.
# RavenCore 0.1.2 (tag v0.1.2): GBA fixes and speed-up from 0.1.1, GB/GBC
# fixes from the public test suites, HDMA bound fix. C API version 0 and save
# state formats unchanged (GB/GBC 11, GBA 12, which still loads format 11).
# Includes the merged neutral C API naming cleanup. Keep the reviewed
# immutable commit SHA, not the movable tag.
include(FetchContent)
set(RAVENEMU_BUILD_LIBRETRO OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(RAVENEMU_BUILD_TESTS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(ravenemu_cores
    GIT_REPOSITORY https://github.com/GhostPunishR/RavenCore.git
    GIT_TAG bad9ade53076c3c44768cfe2599eeb55c996c434
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(ravenemu_cores)

# The installed preview is assembled with Gradle's debug variant. Without
# overriding Debug's -O0, the emulated CPU/PPU loops run unoptimized even on
# high-end Android devices. Keep symbols/debuggability but optimize just the
# native engine targets in the Android preview. Host/iOS SDKs are untouched.
if(ANDROID)
    foreach(core_target IN ITEMS
            ravenemu_common gb_raven_core gbc_raven_core gba_raven_core ravenemu_c_api)
        target_compile_options(${core_target} PRIVATE "$<$<CONFIG:Debug>:-O2>")
    endforeach()
endif()

# Expose neutral API names without changing the pinned RavenCore checkout.
# Rename declarations and definitions together; the engine implementation and
# its serialized states are unchanged. Both JNI and Swift use this same header.
include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/NeutralBridge.cmake")
ravenemu_neutral_bridge(ravenemu_c_api
    "${ravenemu_cores_SOURCE_DIR}/sdk/include/ravenemu/bridge.h"
    "${CMAKE_BINARY_DIR}/include")
configure_file("${CMAKE_CURRENT_SOURCE_DIR}/apple/module.modulemap"
               "${CMAKE_BINARY_DIR}/include/module.modulemap" COPYONLY)

option(RAVENEMU_BRIDGE_TESTS "Test the frontend's normalized C API" OFF)
if(RAVENEMU_BRIDGE_TESTS)
    enable_testing()
    add_executable(ravenemu_bridge_smoke tests/bridge_smoke.cpp)
    target_link_libraries(ravenemu_bridge_smoke PRIVATE ravenemu_c_api)
    add_test(NAME ravenemu_bridge_smoke COMMAND ravenemu_bridge_smoke)
endif()

if(ANDROID)
    # Pin the official RetroAchievements SDK to its reviewed revision.
    FetchContent_Declare(rcheevos
        GIT_REPOSITORY https://github.com/RetroAchievements/rcheevos.git
        GIT_TAG 1433173220a7eaede6a9ed7a18e94117be1821e0)
    FetchContent_MakeAvailable(rcheevos)
    file(GLOB RA_SDK_SOURCES CONFIGURE_DEPENDS
        "${rcheevos_SOURCE_DIR}/src/*.c"
        "${rcheevos_SOURCE_DIR}/src/rcheevos/*.c"
        "${rcheevos_SOURCE_DIR}/src/rapi/*.c"
        "${rcheevos_SOURCE_DIR}/src/rhash/*.c")
    list(FILTER RA_SDK_SOURCES EXCLUDE REGEX "/rc_(libretro|client_external)\\.c$")
    add_library(ravenemu_rcheevos STATIC ${RA_SDK_SOURCES})
    set_target_properties(ravenemu_rcheevos PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_include_directories(ravenemu_rcheevos PUBLIC "${rcheevos_SOURCE_DIR}/include")
    target_compile_definitions(ravenemu_rcheevos PUBLIC RC_DISABLE_LUA RC_CLIENT_SUPPORTS_HASH)

    add_library(ravenemu_android SHARED android/bridge_jni.cpp android/achievements_bridge.cpp)
    target_compile_features(ravenemu_android PRIVATE cxx_std_20)
    target_link_libraries(ravenemu_android PRIVATE ravenemu_c_api ravenemu_rcheevos)
endif()
