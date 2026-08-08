# Frontière CGB distincte : le cœur complet partage encore l'implémentation GB,
# mais les composants matériels CGB vivent et sont testés sous cores/gbc.
add_library(gbc_raven_core INTERFACE)
target_include_directories(gbc_raven_core INTERFACE include)
target_link_libraries(gbc_raven_core INTERFACE gb_raven_core)

if(RAVENEMU_BUILD_TESTS)
    add_executable(gbc_raven_core_tests tests/gbc_hardware_test.cpp)
    target_include_directories(gbc_raven_core_tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/../gb/src
        ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support
    )
    target_link_libraries(gbc_raven_core_tests PRIVATE gbc_raven_core)
    add_test(NAME gbc_raven_core COMMAND gbc_raven_core_tests)
endif()
