add_library(gb_raven_core STATIC src/core.cpp)
target_include_directories(gb_raven_core
    PUBLIC include
    PRIVATE
        src
        ${CMAKE_CURRENT_LIST_DIR}/../gbc/include
)
target_link_libraries(gb_raven_core PUBLIC ravenemu_common)
set_target_properties(gb_raven_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(RAVENEMU_BUILD_TESTS)
    add_executable(gb_raven_core_tests tests/cgb_core_test.cpp)
    target_include_directories(gb_raven_core_tests PRIVATE ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support)
    target_link_libraries(gb_raven_core_tests PRIVATE gb_raven_core)
    add_test(NAME gb_raven_core COMMAND gb_raven_core_tests)
endif()
