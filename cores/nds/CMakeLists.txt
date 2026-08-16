add_library(nds_raven_core STATIC
    src/cartridge_header.cpp
    src/core.cpp
    src/cpu/arm_core.cpp
    src/cpu/cp15.cpp
    src/cpu/thumb.cpp
    src/memory/arm7_memory_map.cpp
    src/memory/arm9_memory_map.cpp
    src/memory/system_memory.cpp
    src/system/inter_processor.cpp
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

    # La carte mémoire est éprouvée pour elle-même, puis avec le processeur
    # au-dessus : c'est leur assemblage qui dit si les adresses mènent où il faut.
    add_executable(nds_raven_arm9_memory_tests tests/arm9_memory_test.cpp)
    target_include_directories(nds_raven_arm9_memory_tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/src
        ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support
    )
    target_link_libraries(nds_raven_arm9_memory_tests PRIVATE nds_raven_core)
    add_test(NAME nds_raven_arm9_memory COMMAND nds_raven_arm9_memory_tests)

    # La carte du processeur secondaire est éprouvée avec celle du principal :
    # ce qui compte est ce que les deux voient de la mémoire qu'ils partagent.
    add_executable(nds_raven_arm7_memory_tests tests/arm7_memory_test.cpp)
    target_include_directories(nds_raven_arm7_memory_tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/src
        ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support
    )
    target_link_libraries(nds_raven_arm7_memory_tests PRIVATE nds_raven_core)
    add_test(NAME nds_raven_arm7_memory COMMAND nds_raven_arm7_memory_tests)

    # Le processeur secondaire partage son implémentation avec le principal :
    # sa suite n'éprouve donc que ce qui les sépare.
    add_executable(nds_raven_arm7_tests tests/arm7_test.cpp)
    target_include_directories(nds_raven_arm7_tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/src
        ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support
    )
    target_link_libraries(nds_raven_arm7_tests PRIVATE nds_raven_core)
    add_test(NAME nds_raven_arm7 COMMAND nds_raven_arm7_tests)

    # La communication entre processeurs n'a de sens qu'avec les deux cartes et
    # les deux contrôleurs montés ensemble : sa suite les assemble.
    add_executable(nds_raven_link_tests tests/inter_processor_test.cpp)
    target_include_directories(nds_raven_link_tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/src
        ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support
    )
    target_link_libraries(nds_raven_link_tests PRIVATE nds_raven_core)
    add_test(NAME nds_raven_link COMMAND nds_raven_link_tests)
endif()
