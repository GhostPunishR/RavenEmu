cmake_minimum_required(VERSION 3.22.1)
project(ravenemu_native LANGUAGES CXX)

option(RAVENEMU_BUILD_TESTS "Construire les tests des cœurs" OFF)

add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../cores"
    "${CMAKE_CURRENT_BINARY_DIR}/cores"
)
add_subdirectory(api)
add_subdirectory(jni)
