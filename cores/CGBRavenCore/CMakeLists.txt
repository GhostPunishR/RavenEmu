# Cœur Game Boy / Game Boy Color.
#
# Un dossier par organe : `cpu/`, `ppu/`, `apu/`, `memory/`, `cartridge/`,
# `timer/`, `serial/`, `input/`, `interrupt/`, `speed/`. Les organes sont
# déclarés en en-têtes et assemblés par `machine/`, seule unité de traduction
# à les connaître tous.
add_library(cgb_raven_core STATIC src/core.cpp)
target_include_directories(cgb_raven_core PRIVATE src)
target_link_libraries(cgb_raven_core PUBLIC ravenemu_shared)
set_target_properties(cgb_raven_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(RAVENEMU_BUILD_TESTS)
    add_executable(cgb_raven_core_tests tests/cgb_core_test.cpp)
    target_include_directories(cgb_raven_core_tests PRIVATE ${CMAKE_SOURCE_DIR}/testing)
    target_link_libraries(cgb_raven_core_tests PRIVATE cgb_raven_core)
    add_test(NAME cgb_raven_core COMMAND cgb_raven_core_tests)
endif()
