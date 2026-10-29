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

add_library(raven_frontend STATIC src/libretro_host.c)
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
