# Contrat public des cœurs et utilitaires que les deux partagent.
add_library(ravenemu_shared STATIC
    src/core.cpp
    src/sha256.cpp
)
target_include_directories(ravenemu_shared PUBLIC include)
target_link_libraries(ravenemu_shared PUBLIC ravenemu_options)
set_target_properties(ravenemu_shared PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(RAVENEMU_BUILD_TESTS)
    add_executable(ravenemu_shared_tests tests/sha256_test.cpp)
    target_include_directories(ravenemu_shared_tests PRIVATE ${CMAKE_SOURCE_DIR}/testing)
    target_link_libraries(ravenemu_shared_tests PRIVATE ravenemu_shared)
    add_test(NAME ravenemu_shared COMMAND ravenemu_shared_tests)
endif()
