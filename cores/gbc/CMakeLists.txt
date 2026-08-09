add_library(gbc_raven_core STATIC src/core.cpp)
target_include_directories(gbc_raven_core PUBLIC include)
target_link_libraries(gbc_raven_core PUBLIC gb_raven_core ravenemu_common)
set_target_properties(gbc_raven_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(RAVENEMU_BUILD_TESTS)
    add_executable(gbc_raven_core_tests tests/gbc_hardware_test.cpp)
    target_include_directories(gbc_raven_core_tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/../gb/src
        ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support
    )
    target_link_libraries(gbc_raven_core_tests PRIVATE gbc_raven_core)
    add_test(NAME gbc_raven_core COMMAND gbc_raven_core_tests)
endif()
