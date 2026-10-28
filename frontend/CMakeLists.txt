cmake_minimum_required(VERSION 3.22.1)
project(RavenEmuNativeFrontend LANGUAGES C CXX)

# The same immutable RavenCore version already used by native/CMakeLists.txt.
include(FetchContent)
set(RAVENEMU_BUILD_LIBRETRO ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_Declare(ravencore
    GIT_REPOSITORY https://github.com/GhostPunishR/RavenCore.git
    GIT_TAG 15675cc3af4f9b07f2e10cc5a7ca6234ea1c77cc
    GIT_SHALLOW FALSE)
FetchContent_MakeAvailable(ravencore)

add_library(raven_frontend STATIC src/libretro_host.c)
target_compile_features(raven_frontend PUBLIC c_std_11)
target_include_directories(raven_frontend PUBLIC include
    PRIVATE ${ravencore_SOURCE_DIR}/libretro/include)
target_link_libraries(raven_frontend PUBLIC ${CMAKE_DL_LIBS})
if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(raven_frontend PRIVATE -Wall -Wextra -Wpedantic)
endif()

option(RAVEN_FRONTEND_TESTS "Test the native frontend with a fake Libretro core" ON)
if(RAVEN_FRONTEND_TESTS AND NOT ANDROID)
    enable_testing()
    add_library(raven_frontend_fake_core SHARED tests/fake_core.c)
    target_include_directories(raven_frontend_fake_core PRIVATE
        ${ravencore_SOURCE_DIR}/libretro/include)
    set_target_properties(raven_frontend_fake_core PROPERTIES PREFIX "")
    add_executable(raven_frontend_test tests/test_host.c)
    target_link_libraries(raven_frontend_test PRIVATE raven_frontend)
    add_test(NAME raven_frontend_smoke COMMAND raven_frontend_test
             $<TARGET_FILE:raven_frontend_fake_core>)
endif()
