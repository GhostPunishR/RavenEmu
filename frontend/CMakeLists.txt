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

add_library(raven_frontend STATIC src/libretro_host.c src/frame_clock.c)
target_compile_features(raven_frontend PUBLIC c_std_11)
target_compile_definitions(raven_frontend PUBLIC RAVEN_CORE_REVISION="${RAVEN_CORE_REVISION}")
target_include_directories(raven_frontend PUBLIC include
    PRIVATE ${ravencore_SOURCE_DIR}/libretro/include)
target_link_libraries(raven_frontend PUBLIC ${CMAKE_DL_LIBS})
if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(raven_frontend PRIVATE -Wall -Wextra -Wpedantic)
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
    set(native_input_dir "${CMAKE_CURRENT_LIST_DIR}/../androidNative/src/main/cpp")
    add_executable(raven_native_input_test
        "${CMAKE_CURRENT_LIST_DIR}/../androidNative/src/test/cpp/test_native_input.c"
        "${native_input_dir}/native_input.c")
    target_compile_features(raven_native_input_test PRIVATE c_std_11)
    target_include_directories(raven_native_input_test PRIVATE
        "${native_input_dir}" "${ravencore_SOURCE_DIR}/libretro/include")
    if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
        target_compile_options(raven_native_input_test PRIVATE -Wall -Wextra -Wpedantic)
    endif()
    add_test(NAME raven_native_input COMMAND raven_native_input_test)
    add_executable(raven_native_preview_test
        "${CMAKE_CURRENT_LIST_DIR}/../androidNative/src/test/cpp/test_native_preview.c"
        "${native_input_dir}/native_preview.c")
    target_compile_features(raven_native_preview_test PRIVATE c_std_11)
    target_include_directories(raven_native_preview_test PRIVATE "${native_input_dir}")
    if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
        target_compile_options(raven_native_preview_test PRIVATE -Wall -Wextra -Wpedantic)
    endif()
    add_test(NAME raven_native_preview COMMAND raven_native_preview_test)
    find_package(Threads REQUIRED)
    add_executable(raven_native_audio_test
        "${CMAKE_CURRENT_LIST_DIR}/../androidNative/src/test/cpp/test_native_audio.c"
        "${native_input_dir}/native_audio.c" "${native_input_dir}/retroarch_aaudio.c")
    target_compile_features(raven_native_audio_test PRIVATE c_std_11)
    target_include_directories(raven_native_audio_test PRIVATE "${native_input_dir}"
        "${CMAKE_CURRENT_LIST_DIR}/../androidNative/src/test/cpp/fakes_audio")
    target_link_libraries(raven_native_audio_test PRIVATE Threads::Threads m)
    add_test(NAME raven_native_audio COMMAND raven_native_audio_test)
    add_executable(raven_native_video_test
        "${CMAKE_CURRENT_LIST_DIR}/../androidNative/src/test/cpp/test_native_video.c"
        "${native_input_dir}/native_video.c")
    target_compile_features(raven_native_video_test PRIVATE c_std_11)
    target_include_directories(raven_native_video_test PRIVATE "${native_input_dir}"
        "${CMAKE_CURRENT_LIST_DIR}/../androidNative/src/test/cpp/fake_gles2")
    add_test(NAME raven_native_video COMMAND raven_native_video_test)
    option(RAVEN_VIDEO_PIXEL_TESTS "Exercise the GLES shader with software EGL" OFF)
    if(RAVEN_VIDEO_PIXEL_TESTS)
        find_package(Python3 REQUIRED COMPONENTS Interpreter)
        find_library(RAVEN_PIXEL_GL NAMES GLESv2 GL libGL.so.1 REQUIRED)
        find_library(RAVEN_PIXEL_EGL NAMES EGL libEGL.so.1 REQUIRED)
        add_library(raven_native_video_pixels SHARED "${native_input_dir}/native_video.c")
        target_compile_features(raven_native_video_pixels PRIVATE c_std_11)
        target_include_directories(raven_native_video_pixels PRIVATE
            "${CMAKE_CURRENT_LIST_DIR}/../androidNative/src/test/cpp/fake_gles2")
        target_link_libraries(raven_native_video_pixels PRIVATE "${RAVEN_PIXEL_GL}")
        add_test(NAME raven_native_video_pixels COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_LIST_DIR}/../scripts/test_native_video_pixels.py"
            $<TARGET_FILE:raven_native_video_pixels> --egl "${RAVEN_PIXEL_EGL}" --gl "${RAVEN_PIXEL_GL}")
    endif()
    if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
        target_compile_options(raven_native_audio_test PRIVATE -Wall -Wextra -Wpedantic)
        target_compile_options(raven_native_video_test PRIVATE -Wall -Wextra -Wpedantic)
    endif()
    add_library(raven_frontend_fake_core SHARED tests/fake_core.c)
    target_include_directories(raven_frontend_fake_core PRIVATE
        ${ravencore_SOURCE_DIR}/libretro/include)
    set_target_properties(raven_frontend_fake_core PROPERTIES PREFIX "")
    add_executable(raven_frontend_test tests/test_host.c)
    target_link_libraries(raven_frontend_test PRIVATE raven_frontend)
    add_test(NAME raven_frontend_smoke COMMAND raven_frontend_test
             $<TARGET_FILE:raven_frontend_fake_core>)
    add_executable(raven_frontend_real_core_test tests/test_real_cores.c)
    target_link_libraries(raven_frontend_real_core_test PRIVATE raven_frontend)
    add_dependencies(raven_frontend_real_core_test
        ravenemu_gb_libretro ravenemu_gbc_libretro ravenemu_gba_libretro)
    add_test(NAME raven_frontend_real_cores COMMAND raven_frontend_real_core_test
        $<TARGET_FILE:ravenemu_gb_libretro>
        $<TARGET_FILE:ravenemu_gbc_libretro>
        $<TARGET_FILE:ravenemu_gba_libretro>)
endif()
