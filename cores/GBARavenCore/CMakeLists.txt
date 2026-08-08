# Cœur Game Boy Advance.
#
# Même découpage par organe. Trois unités de traduction seulement : le
# décodage ARM/Thumb et l'arbitrage du bus sont définis hors-ligne, parce
# qu'ils dépendent de périphériques déclarés après eux.
add_library(gba_raven_core STATIC
    src/core.cpp
    src/cpu/cpu.cpp
    src/memory/bus.cpp
)
target_include_directories(gba_raven_core PRIVATE src)
target_link_libraries(gba_raven_core PUBLIC ravenemu_shared)
set_target_properties(gba_raven_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(RAVENEMU_BUILD_TESTS)
    add_executable(gba_raven_core_tests tests/gba_core_test.cpp)
    target_include_directories(gba_raven_core_tests PRIVATE ${CMAKE_SOURCE_DIR}/testing)
    target_link_libraries(gba_raven_core_tests PRIVATE gba_raven_core)
    add_test(NAME gba_raven_core COMMAND gba_raven_core_tests)
endif()
