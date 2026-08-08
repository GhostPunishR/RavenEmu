# CGB is currently a hardware mode of the proven GB implementation. Exposing a
# distinct target makes the architecture explicit without duplicating state or
# changing the library's stable console identity.
add_library(gbc_raven_core INTERFACE)
target_include_directories(gbc_raven_core INTERFACE include)
target_link_libraries(gbc_raven_core INTERFACE gb_raven_core)

if(RAVENEMU_BUILD_TESTS)
    add_executable(gbc_raven_core_tests tests/gbc_boundary_test.cpp)
    target_link_libraries(gbc_raven_core_tests PRIVATE gbc_raven_core)
    add_test(NAME gbc_raven_core COMMAND gbc_raven_core_tests)
endif()
