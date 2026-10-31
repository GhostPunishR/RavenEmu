cmake_minimum_required(VERSION 3.22.1)
project(RavenEmuNativeFrontend LANGUAGES C CXX)

# The same immutable RavenCore version already used by native/CMakeLists.txt.
include(FetchContent)
set(RAVENEMU_BUILD_LIBRETRO ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(RAVEN_CORE_REVISION "15675cc3af4f9b07f2e10cc5a7ca6234ea1c77cc")
FetchContent_Declare(ravencore
    GIT_REPOSITORY https://github.com/GhostPunishR/RavenCore.git
    GIT_TAG ${RAVEN_CORE_REVISION}
    GIT_SHALLOW FALSE)
FetchContent_MakeAvailable(ravencore)

add_library(raven_frontend STATIC src/libretro_host.c src/frame_clock.c src/frame_pacer.c)
target_compile_features(raven_frontend PUBLIC c_std_11)
# RavenCore's own version, for the User-Agent RetroAchievements asks clients to send.
get_directory_property(RAVEN_CORE_VERSION DIRECTORY "${ravencore_SOURCE_DIR}" DEFINITION PROJECT_VERSION)
if(NOT RAVEN_CORE_VERSION MATCHES "^[0-9]+(\\.[0-9]+)+$")
    message(FATAL_ERROR "RavenCore version unreadable: '${RAVEN_CORE_VERSION}'")
endif()
target_compile_definitions(raven_frontend PUBLIC RAVEN_CORE_REVISION="${RAVEN_CORE_REVISION}"
    RAVEN_CORE_VERSION="${RAVEN_CORE_VERSION}")
target_include_directories(raven_frontend PUBLIC include
    PRIVATE ${ravencore_SOURCE_DIR}/libretro/include)
target_link_libraries(raven_frontend PUBLIC ${CMAKE_DL_LIBS})
if(UNIX)
    target_link_libraries(raven_frontend PUBLIC m)
endif()
if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(raven_frontend PRIVATE -Wall -Wextra -Wpedantic)
endif()

# RetroAchievements: the frontend's module over the official rcheevos SDK.
include(cmake/rcheevos.cmake)
add_library(raven_achievements STATIC src/achievements.c)
target_compile_features(raven_achievements PUBLIC c_std_11)
target_include_directories(raven_achievements PRIVATE "${rcheevos_SOURCE_DIR}/src")
target_link_libraries(raven_achievements PUBLIC raven_frontend PRIVATE raven_rcheevos)
if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(raven_achievements PRIVATE -Wall -Wextra -Wpedantic)
endif()

option(RAVEN_FRONTEND_TESTS "Test the native frontend with a fake Libretro core" ON)
if(RAVEN_FRONTEND_TESTS AND NOT ANDROID)
    enable_testing()
    add_executable(raven_frame_clock_test tests/test_frame_clock.c)
    target_link_libraries(raven_frame_clock_test PRIVATE raven_frontend)
    if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
        target_compile_options(raven_frame_clock_test PRIVATE -Wall -Wextra -Wpedantic)
    endif()
    add_test(NAME raven_frame_clock COMMAND raven_frame_clock_test)
    add_executable(raven_frame_pacer_test tests/test_frame_pacer.c)
    target_link_libraries(raven_frame_pacer_test PRIVATE raven_frontend)
    if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
        target_compile_options(raven_frame_pacer_test PRIVATE -Wall -Wextra -Wpedantic)
    endif()
    add_test(NAME raven_frame_pacer COMMAND raven_frame_pacer_test)
    # Host-only hardware tests are maintained outside of the frontend library.
    add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../tests/hardware"
                     "${CMAKE_CURRENT_BINARY_DIR}/hardware-tests")
    # The Android bridge's RetroAchievements JNI, on the host's JVM.
    add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../tests/jni"
                     "${CMAKE_CURRENT_BINARY_DIR}/jni-tests")
    add_library(raven_frontend_fake_core SHARED tests/fake_core.c)
    target_include_directories(raven_frontend_fake_core PRIVATE
        ${ravencore_SOURCE_DIR}/libretro/include)
    set_target_properties(raven_frontend_fake_core PROPERTIES PREFIX "")
    # The same fake core, declaring its options through v1 or legacy variables.
    foreach(variant V1 VARIABLES)
        string(TOLOWER ${variant} variant_name)
        add_library(raven_frontend_fake_core_${variant_name} SHARED tests/fake_core.c)
        target_include_directories(raven_frontend_fake_core_${variant_name} PRIVATE
            ${ravencore_SOURCE_DIR}/libretro/include)
        target_compile_definitions(raven_frontend_fake_core_${variant_name} PRIVATE FAKE_CORE_${variant})
        set_target_properties(raven_frontend_fake_core_${variant_name} PROPERTIES PREFIX "")
    endforeach()
    add_executable(raven_frontend_options_test tests/test_options.c)
    target_link_libraries(raven_frontend_options_test PRIVATE raven_frontend)
    if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
        target_compile_options(raven_frontend_options_test PRIVATE -Wall -Wextra -Wpedantic)
    endif()
    add_test(NAME raven_frontend_options COMMAND raven_frontend_options_test
             $<TARGET_FILE:raven_frontend_fake_core>
             $<TARGET_FILE:raven_frontend_fake_core_v1>
             $<TARGET_FILE:raven_frontend_fake_core_variables>)
    add_executable(raven_frontend_test tests/test_host.c)
    target_link_libraries(raven_frontend_test PRIVATE raven_frontend)
    target_include_directories(raven_frontend_test PRIVATE ${ravencore_SOURCE_DIR}/libretro/include)
    add_test(NAME raven_frontend_smoke COMMAND raven_frontend_test
             $<TARGET_FILE:raven_frontend_fake_core>)
    # A game with input lag, for preemptive frames, and one whose states are incomplete.
    add_library(raven_frontend_fake_core_lag SHARED tests/fake_core_lag.c)
    add_library(raven_frontend_fake_core_lag_incomplete SHARED tests/fake_core_lag.c)
    target_compile_definitions(raven_frontend_fake_core_lag_incomplete PRIVATE FAKE_LAG_INCOMPLETE)
    foreach(lag_core raven_frontend_fake_core_lag raven_frontend_fake_core_lag_incomplete)
        target_include_directories(${lag_core} PRIVATE ${ravencore_SOURCE_DIR}/libretro/include)
        set_target_properties(${lag_core} PROPERTIES PREFIX "")
    endforeach()
    add_executable(raven_frontend_preempt_test tests/test_preempt.c)
    target_link_libraries(raven_frontend_preempt_test PRIVATE raven_frontend)
    target_include_directories(raven_frontend_preempt_test PRIVATE ${ravencore_SOURCE_DIR}/libretro/include)
    if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
        target_compile_options(raven_frontend_preempt_test PRIVATE -Wall -Wextra -Wpedantic)
    endif()
    add_test(NAME raven_frontend_preempt COMMAND raven_frontend_preempt_test
             $<TARGET_FILE:raven_frontend_fake_core_lag>
             $<TARGET_FILE:raven_frontend_fake_core_lag_incomplete>)
    add_executable(raven_frontend_achievements_test tests/test_achievements.c)
    target_link_libraries(raven_frontend_achievements_test PRIVATE raven_achievements)
    add_dependencies(raven_frontend_achievements_test ravenemu_gb_libretro)
    if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
        target_compile_options(raven_frontend_achievements_test PRIVATE -Wall -Wextra -Wpedantic)
    endif()
    add_test(NAME raven_frontend_achievements COMMAND raven_frontend_achievements_test
             $<TARGET_FILE:ravenemu_gb_libretro>)
    add_executable(raven_frontend_real_core_test tests/test_real_cores.c)
    target_link_libraries(raven_frontend_real_core_test PRIVATE raven_frontend)
    add_dependencies(raven_frontend_real_core_test
        ravenemu_gb_libretro ravenemu_gbc_libretro ravenemu_gba_libretro)
    add_test(NAME raven_frontend_real_cores COMMAND raven_frontend_real_core_test
        $<TARGET_FILE:ravenemu_gb_libretro>
        $<TARGET_FILE:ravenemu_gbc_libretro>
        $<TARGET_FILE:ravenemu_gba_libretro>)
endif()
