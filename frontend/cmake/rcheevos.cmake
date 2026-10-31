# RetroAchievements' official SDK (rcheevos, MIT), pinned to the revision the
# beta reviewed, with the beta's two safety fixes (native/cmake, shared with
# RavenEmuDSK) applied to a copy: FetchContent's checkout is never modified,
# and a SDK update that breaks a precondition stops the configure step.
set(RAVEN_RCHEEVOS_REVISION "1433173220a7eaede6a9ed7a18e94117be1821e0")
FetchContent_Declare(rcheevos
    GIT_REPOSITORY https://github.com/RetroAchievements/rcheevos.git
    GIT_TAG ${RAVEN_RCHEEVOS_REVISION})
FetchContent_MakeAvailable(rcheevos)
# The checkout built into the app, for its source archive (scripts/package_native_sources.py).
set(RAVEN_RCHEEVOS_SOURCE_DIR "${rcheevos_SOURCE_DIR}" CACHE INTERNAL "rcheevos checkout built into the app")
file(GLOB RAVEN_RA_SOURCES CONFIGURE_DEPENDS
    "${rcheevos_SOURCE_DIR}/src/*.c"
    "${rcheevos_SOURCE_DIR}/src/rcheevos/*.c"
    "${rcheevos_SOURCE_DIR}/src/rapi/*.c"
    "${rcheevos_SOURCE_DIR}/src/rhash/*.c")
# rc_libretro.c reads a core's memory through its Libretro memory map; the
# external-client bridge is for RAIntegration on Windows only.
list(FILTER RAVEN_RA_SOURCES EXCLUDE REGEX "/rc_client_external\\.c$")

file(READ "${rcheevos_SOURCE_DIR}/src/rc_client.c" RAVEN_RC_CLIENT)
function(raven_rc_replace before after)
    string(FIND "${RAVEN_RC_CLIENT}" "${before}" position)
    if(position EQUAL -1)
        message(FATAL_ERROR "rcheevos safety patch no longer applies; review the SDK update")
    endif()
    string(REPLACE "${before}" "${after}" updated "${RAVEN_RC_CLIENT}")
    set(RAVEN_RC_CLIENT "${updated}" PARENT_SCOPE)
endfunction()
# Unload clears client->game before raising events; use the game being unloaded.
raven_rc_replace(
    "static void rc_client_raise_mastery_event(rc_client_t* client, rc_client_subset_info_t* subset)"
    "static void rc_client_raise_mastery_event(rc_client_t* client, rc_client_game_info_t* game, rc_client_subset_info_t* subset)")
raven_rc_replace("if (subset == client->game->subsets)" "if (subset == game->subsets)")
raven_rc_replace(
    "static void rc_client_subset_raise_pending_events(rc_client_t* client, rc_client_subset_info_t* subset)"
    "static void rc_client_subset_raise_pending_events(rc_client_t* client, rc_client_game_info_t* game, rc_client_subset_info_t* subset)")
raven_rc_replace("rc_client_raise_mastery_event(client, subset);" "rc_client_raise_mastery_event(client, game, subset);")
raven_rc_replace("rc_client_subset_raise_pending_events(client, subset);" "rc_client_subset_raise_pending_events(client, game, subset);")
# An award may finish after its game was closed or another ROM was loaded.
raven_rc_replace(
    "if (award_achievement_response.achievements_remaining == 0) {"
    "if (award_achievement_response.achievements_remaining == 0 && ach_data->client->game && ach_data->game_hash && strcmp(ach_data->client->game->public_.hash, ach_data->game_hash) == 0) {")
set(RAVEN_RC_PATCHED "${CMAKE_CURRENT_BINARY_DIR}/rcheevos-patched/rc_client.c")
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/rcheevos-patched")
file(WRITE "${RAVEN_RC_PATCHED}.new" "${RAVEN_RC_CLIENT}")
# Only a changed patch rewrites the copy, so it is not rebuilt on every configure.
file(COPY_FILE "${RAVEN_RC_PATCHED}.new" "${RAVEN_RC_PATCHED}" ONLY_IF_DIFFERENT)
list(REMOVE_ITEM RAVEN_RA_SOURCES "${rcheevos_SOURCE_DIR}/src/rc_client.c")
list(APPEND RAVEN_RA_SOURCES "${RAVEN_RC_PATCHED}")

add_library(raven_rcheevos STATIC ${RAVEN_RA_SOURCES})
set_target_properties(raven_rcheevos PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(raven_rcheevos PRIVATE "${rcheevos_SOURCE_DIR}/src")
target_include_directories(raven_rcheevos PUBLIC "${rcheevos_SOURCE_DIR}/include"
    "${ravencore_SOURCE_DIR}/libretro/include")
target_compile_definitions(raven_rcheevos PUBLIC RC_DISABLE_LUA RC_CLIENT_SUPPORTS_HASH)
if(UNIX)
    target_link_libraries(raven_rcheevos PUBLIC m)
endif()
