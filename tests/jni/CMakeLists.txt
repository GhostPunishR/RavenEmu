# The Android bridge's RetroAchievements JNI (android/src/main/cpp/jni/
# achievements_bridge.c) with the app's NativeAchievements.java, on the host's
# JVM: handles, UTF-8 both ways, the player's instance on RavenCore's Game Boy
# core. Skipped without a JDK.
find_package(Java 17 COMPONENTS Development)
if(NOT Java_FOUND)
    message(STATUS "RetroAchievements JNI test skipped: no JDK")
    return()
endif()
get_filename_component(raven_javac "${Java_JAVAC_EXECUTABLE}" REALPATH)
get_filename_component(raven_jdk "${raven_javac}" DIRECTORY)
get_filename_component(raven_jdk "${raven_jdk}" DIRECTORY)
find_path(RAVEN_JNI_INCLUDE jni.h PATHS "${raven_jdk}/include" NO_DEFAULT_PATH)
if(NOT RAVEN_JNI_INCLUDE)
    message(STATUS "RetroAchievements JNI test skipped: no jni.h under ${raven_jdk}")
    return()
endif()

set(ANDROID_SOURCES "${CMAKE_CURRENT_LIST_DIR}/../../android/src/main")
# The bridge is a shared library, as on Android.
set_property(TARGET raven_frontend raven_achievements raven_rcheevos PROPERTY POSITION_INDEPENDENT_CODE ON)
add_library(raven_jni_achievements MODULE "${ANDROID_SOURCES}/cpp/jni/achievements_bridge.c" test_host.c)
target_compile_features(raven_jni_achievements PRIVATE c_std_11)
target_include_directories(raven_jni_achievements PRIVATE "${ANDROID_SOURCES}/cpp/jni"
    "${RAVEN_JNI_INCLUDE}" "${RAVEN_JNI_INCLUDE}/linux" "${RAVEN_JNI_INCLUDE}/darwin")
target_link_libraries(raven_jni_achievements PRIVATE raven_frontend raven_achievements)
# System.loadLibrary("raven_android"), from its own folder.
set_target_properties(raven_jni_achievements PROPERTIES OUTPUT_NAME raven_android
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/library")
if(CMAKE_C_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(raven_jni_achievements PRIVATE -Wall -Wextra -Wpedantic)
endif()

set(raven_java_sources "${ANDROID_SOURCES}/java/com/ravenemu/app/bridge/NativeAchievements.java"
    "${CMAKE_CURRENT_LIST_DIR}/AchievementsBridgeCheck.java")
set(raven_classes "${CMAKE_CURRENT_BINARY_DIR}/classes")
add_custom_command(OUTPUT "${raven_classes}/com/ravenemu/app/bridge/AchievementsBridgeCheck.class"
    COMMAND "${Java_JAVAC_EXECUTABLE}" -encoding UTF-8 -d "${raven_classes}" ${raven_java_sources}
    DEPENDS ${raven_java_sources} VERBATIM)
add_custom_target(raven_jni_achievements_classes
    DEPENDS "${raven_classes}/com/ravenemu/app/bridge/AchievementsBridgeCheck.class")
add_dependencies(raven_jni_achievements raven_jni_achievements_classes ravenemu_gb_libretro)
add_test(NAME raven_jni_achievements
    COMMAND "${Java_JAVA_EXECUTABLE}" -Xcheck:jni -Djava.library.path=$<TARGET_FILE_DIR:raven_jni_achievements>
        -cp "${raven_classes}" com.ravenemu.app.bridge.AchievementsBridgeCheck $<TARGET_FILE:ravenemu_gb_libretro>)
