add_library(gb_raven_core STATIC src/core.cpp)
target_include_directories(gb_raven_core
    PUBLIC include
    PRIVATE src
)
# `gbc_hardware` est déclarée par `cores/gbc` et porte les composants CGB déjà
# extraits (KEY1, port infrarouge). Seuls les en-têtes privés de `src/` les
# incluent : l'API publique du cœur GB n'en expose aucun, d'où PRIVATE.
#
# Passer par la cible plutôt que par `../gbc/include` n'est pas cosmétique :
# un chemin d'inclusion brut ne transporte ni les objets ni les dépendances
# transitives, et l'extraction restait donc condamnée aux composants
# header-only.
target_link_libraries(gb_raven_core
    PUBLIC ravenemu_common
    PRIVATE gbc_hardware
)
set_target_properties(gb_raven_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(RAVENEMU_BUILD_TESTS)
    add_executable(gb_raven_core_tests tests/cgb_core_test.cpp)
    target_include_directories(gb_raven_core_tests PRIVATE ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support)
    target_link_libraries(gb_raven_core_tests PRIVATE gb_raven_core)
    add_test(NAME gb_raven_core COMMAND gb_raven_core_tests)
endif()
