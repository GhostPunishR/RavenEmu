cmake_minimum_required(VERSION 3.22.1)

project(ravenemu_cores LANGUAGES CXX)

option(RAVENEMU_BUILD_TESTS "Construire les tests des cœurs" OFF)

# Réglages communs à toutes les cibles : C++20, avertissements en erreurs de
# relecture. `-Wconversion` et `-Wsign-conversion` sont indispensables ici —
# un cœur d'émulation manipule des entiers bornés en permanence, et une
# conversion implicite y est un bogue matériel, pas un détail de style.
add_library(ravenemu_options INTERFACE)
target_compile_features(ravenemu_options INTERFACE cxx_std_20)
if(MSVC)
    target_compile_options(ravenemu_options INTERFACE /W4 /permissive-)
else()
    target_compile_options(ravenemu_options INTERFACE
        -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion
    )
endif()

# Doit précéder les sous-répertoires : CTest n'enregistre que les suites
# déclarées après lui.
if(RAVENEMU_BUILD_TESTS)
    enable_testing()
endif()

add_subdirectory(shared)
add_subdirectory(CGBRavenCore)
add_subdirectory(GBARavenCore)

# Cible d'assemblage consommée par les tests et par la couche `native/`.
# `cores/` ne connaît volontairement ni JNI, ni Android, ni la JVM.
add_library(ravenemu_cores INTERFACE)
target_link_libraries(ravenemu_cores INTERFACE cgb_raven_core gba_raven_core)
