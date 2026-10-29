# Hardware adapters: host-only tests, independent of the frontend implementation.
set(DRIVERS "${CMAKE_CURRENT_LIST_DIR}/../../android/src/main/cpp/hardware")
set(MEDIA "${CMAKE_CURRENT_LIST_DIR}/../../android/src/main/cpp/media")
set(CORE_HEADERS "${ravencore_SOURCE_DIR}/libretro/include")

add_executable(raven_native_input_test test_input_driver.c "${DRIVERS}/input/input_driver.c")
target_compile_features(raven_native_input_test PRIVATE c_std_11)
target_include_directories(raven_native_input_test PRIVATE "${DRIVERS}/input" "${CORE_HEADERS}")
add_test(NAME raven_native_input COMMAND raven_native_input_test)

add_executable(raven_native_preview_test test_state_thumbnail.c "${MEDIA}/state_thumbnail.c")
target_compile_features(raven_native_preview_test PRIVATE c_std_11)
target_include_directories(raven_native_preview_test PRIVATE "${MEDIA}")
add_test(NAME raven_native_preview COMMAND raven_native_preview_test)

find_package(Threads REQUIRED)
add_executable(raven_native_audio_test test_audio_pipeline.c
    "${DRIVERS}/audio/audio_pipeline.c" "${DRIVERS}/audio/aaudio/aaudio_driver.c")
target_compile_features(raven_native_audio_test PRIVATE c_std_11)
target_include_directories(raven_native_audio_test PRIVATE
    "${DRIVERS}/audio" "${DRIVERS}/audio/aaudio"
    "${CMAKE_CURRENT_LIST_DIR}/fakes_audio")
target_link_libraries(raven_native_audio_test PRIVATE Threads::Threads m)
add_test(NAME raven_native_audio COMMAND raven_native_audio_test)

add_executable(raven_native_video_test test_gles2_renderer.c "${DRIVERS}/video/gles2/gles2_renderer.c")
target_compile_features(raven_native_video_test PRIVATE c_std_11)
target_include_directories(raven_native_video_test PRIVATE
    "${DRIVERS}/video/gles2" "${CMAKE_CURRENT_LIST_DIR}/fake_gles2")
add_test(NAME raven_native_video COMMAND raven_native_video_test)

option(RAVEN_VIDEO_PIXEL_TESTS "Run EGL software shader verification" OFF)
if(RAVEN_VIDEO_PIXEL_TESTS)
    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    find_library(RAVEN_PIXEL_GL NAMES GLESv2 GL libGL.so.1 REQUIRED)
    find_library(RAVEN_PIXEL_EGL NAMES EGL libEGL.so.1 REQUIRED)
    add_library(raven_native_video_pixels SHARED "${DRIVERS}/video/gles2/gles2_renderer.c")
    target_compile_features(raven_native_video_pixels PRIVATE c_std_11)
    target_include_directories(raven_native_video_pixels PRIVATE "${CMAKE_CURRENT_LIST_DIR}/fake_gles2")
    target_link_libraries(raven_native_video_pixels PRIVATE "${RAVEN_PIXEL_GL}")
    add_test(NAME raven_native_video_pixels COMMAND "${Python3_EXECUTABLE}"
        "${CMAKE_CURRENT_LIST_DIR}/../../scripts/test_native_video_pixels.py"
        $<TARGET_FILE:raven_native_video_pixels> --egl "${RAVEN_PIXEL_EGL}" --gl "${RAVEN_PIXEL_GL}")
endif()
if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(raven_native_audio_test PRIVATE -Wall -Wextra -Wpedantic)
    target_compile_options(raven_native_video_test PRIVATE -Wall -Wextra -Wpedantic)
endif()
