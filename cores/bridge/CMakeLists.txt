# Pont JNI : seule frontière entre la JVM et les cœurs. Il ne transporte que
# des poignées, des scalaires et des tableaux primitifs.
if(NOT ANDROID)
    find_package(JNI REQUIRED)
endif()

add_library(ravenemu_native SHARED src/jni_bridge.cpp)
target_link_libraries(ravenemu_native PRIVATE ravenemu_cores ravenemu_options)
if(NOT ANDROID)
    target_include_directories(ravenemu_native PRIVATE ${JNI_INCLUDE_DIRS})
endif()
