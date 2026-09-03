add_library(ravenemu_common STATIC
    src/core.cpp
    src/sha256.cpp
)
target_include_directories(ravenemu_common PUBLIC include)
target_link_libraries(ravenemu_common PUBLIC ravenemu_options)
set_target_properties(ravenemu_common PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(RAVENEMU_BUILD_TESTS)
    add_executable(ravenemu_common_tests tests/sha256_test.cpp)
    target_include_directories(ravenemu_common_tests PRIVATE tests/support)
    target_link_libraries(ravenemu_common_tests PRIVATE ravenemu_common)
    add_test(NAME ravenemu_common COMMAND ravenemu_common_tests)

    # La lecture d'un descripteur a sa propre suite : c'est par elle qu'une
    # cartouche entre sans passer par la mémoire de l'hôte, et ses deux fautes
    # possibles sont silencieuses.
    add_executable(ravenemu_read_exactly_tests tests/read_exactly_test.cpp)
    target_include_directories(ravenemu_read_exactly_tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/tests/support
    )
    target_link_libraries(ravenemu_read_exactly_tests PRIVATE ravenemu_common)
    add_test(NAME ravenemu_read_exactly COMMAND ravenemu_read_exactly_tests)
endif()
