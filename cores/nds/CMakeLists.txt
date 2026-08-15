add_library(nds_raven_core STATIC src/cartridge_header.cpp src/core.cpp)
target_include_directories(nds_raven_core
    PUBLIC include
    PRIVATE src
)
target_link_libraries(nds_raven_core PUBLIC ravenemu_common)
set_target_properties(nds_raven_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(RAVENEMU_BUILD_TESTS)
    # Le test éprouve le CRC de l'en-tête, qui vit dans `src/` : il lui faut donc
    # les en-têtes internes, que la cible garde en PRIVATE.
    add_executable(nds_raven_core_tests tests/nds_header_test.cpp)
    target_include_directories(nds_raven_core_tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/src
        ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support
    )
    target_link_libraries(nds_raven_core_tests PRIVATE nds_raven_core)
    add_test(NAME nds_raven_core COMMAND nds_raven_core_tests)
endif()
