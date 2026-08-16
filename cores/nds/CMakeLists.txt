add_library(nds_raven_core STATIC
    src/cartridge_header.cpp
    src/core.cpp
    src/cpu/arm9.cpp
    src/cpu/cp15.cpp
    src/cpu/thumb.cpp
)
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

    # Le processeur vit lui aussi dans `src/` : il est éprouvé contre une mémoire
    # de test, sans rien du reste de la console.
    add_executable(nds_raven_arm9_tests tests/arm9_test.cpp)
    target_include_directories(nds_raven_arm9_tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/src
        ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support
    )
    target_link_libraries(nds_raven_arm9_tests PRIVATE nds_raven_core)
    add_test(NAME nds_raven_arm9 COMMAND nds_raven_arm9_tests)

    # Le jeu Thumb a sa propre suite : ses fautes sont d'une autre nature — des
    # indicateurs écrits d'office et un état qui bascule — et les mélanger
    # rendrait un échec plus long à situer.
    add_executable(nds_raven_thumb_tests tests/thumb_test.cpp)
    target_include_directories(nds_raven_thumb_tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/src
        ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support
    )
    target_link_libraries(nds_raven_thumb_tests PRIVATE nds_raven_core)
    add_test(NAME nds_raven_thumb COMMAND nds_raven_thumb_tests)

    # Le coprocesseur système a sa propre suite : elle éprouve autant son banc
    # de registres que ce que le processeur fait différemment une fois qu'il est
    # configuré.
    add_executable(nds_raven_cp15_tests tests/cp15_test.cpp)
    target_include_directories(nds_raven_cp15_tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/src
        ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support
    )
    target_link_libraries(nds_raven_cp15_tests PRIVATE nds_raven_core)
    add_test(NAME nds_raven_cp15 COMMAND nds_raven_cp15_tests)
endif()
