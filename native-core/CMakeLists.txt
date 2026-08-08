cmake_minimum_required(VERSION 3.22.1)

project(ravenemu_native LANGUAGES CXX)

option(RAVENEMU_BUILD_TESTS "Build the native core tests" OFF)
option(RAVENEMU_BUILD_JNI "Build the JNI bridge on desktop" OFF)

add_library(ravenemu_cores STATIC
    src/core.cpp
    src/gameboy/gameboy_core.cpp
    src/gba/gba_core.cpp
    src/sha256.cpp
)

target_include_directories(ravenemu_cores
    PUBLIC include
    PRIVATE src
)
target_compile_features(ravenemu_cores PUBLIC cxx_std_20)
set_target_properties(ravenemu_cores PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(MSVC)
    target_compile_options(ravenemu_cores PRIVATE /W4 /permissive-)
else()
    target_compile_options(ravenemu_cores PRIVATE
        -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion
    )
endif()

if(ANDROID OR RAVENEMU_BUILD_JNI)
    if(NOT ANDROID)
        find_package(JNI REQUIRED)
    endif()
    add_library(ravenemu_native SHARED src/jni_bridge.cpp)
    target_link_libraries(ravenemu_native PRIVATE ravenemu_cores)
    target_include_directories(ravenemu_native PRIVATE
        include
        src
    )
    if(NOT ANDROID)
        target_include_directories(ravenemu_native PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
    target_compile_features(ravenemu_native PRIVATE cxx_std_20)
    if(NOT MSVC)
        target_compile_options(ravenemu_native PRIVATE
            -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion
        )
    endif()
endif()

if(RAVENEMU_BUILD_TESTS)
    enable_testing()
    add_executable(ravenemu_native_tests
        tests/native_core_test.cpp
    )
    target_link_libraries(ravenemu_native_tests PRIVATE ravenemu_cores)
    target_include_directories(ravenemu_native_tests PRIVATE src)
    target_compile_features(ravenemu_native_tests PRIVATE cxx_std_20)
    add_test(NAME ravenemu_native_tests COMMAND ravenemu_native_tests)
endif()
