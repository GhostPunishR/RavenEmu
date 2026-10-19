cmake_minimum_required(VERSION 3.22.1)
project(RavenEmuNative LANGUAGES C CXX)

# Lock the emulator engines AND public C interface to a reviewed source commit.
# RavenCore 0.7.1 + post-release RTC fix (upstream PR #28): detect Pokémon
# RTC drivers without the Seiko marker; fix GPIO protocol and RTC calendar.
# C API version 0 and state formats GB/GBC 11, GBA 16 are unchanged.
# GBA still reads formats 15 through 11. Battery RTC keeps its 16-byte layout.
# Old states created without RTC may not load once the cartridge gains RTC;
# resume from the game's battery save instead (see native/README.md).
# Keep the reviewed immutable commit SHA, not a movable branch/tag.
include(FetchContent)
set(RAVENEMU_BUILD_LIBRETRO OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_Declare(ravencore
    GIT_REPOSITORY https://github.com/GhostPunishR/RavenCore.git
    GIT_TAG 241f56c3564ca1389813753d5c13dd931771fe40
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(ravencore)

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

# JNI includes <ravenemu/bridge.h> straight from the pinned RavenCore SDK.
# Swift imports the same header through a module map, which must sit next to
# it: copy both to build/include, the folder Xcode and the CI point Swift to.
configure_file("${ravencore_SOURCE_DIR}/sdk/include/ravenemu/bridge.h"
               "${CMAKE_BINARY_DIR}/include/bridge.h" COPYONLY)
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
