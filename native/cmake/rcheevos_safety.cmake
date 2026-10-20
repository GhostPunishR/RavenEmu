# Local safety fixes for pinned rcheevos 1433173, shared with RavenEmuDSK.
# Do not modify FetchContent's checkout. Keep these exact preconditions so an
# SDK update requires reviewing the patch.
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
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/rcheevos-patched")
set(RAVEN_RC_PATCHED "${CMAKE_CURRENT_BINARY_DIR}/rcheevos-patched/rc_client.c")
file(WRITE "${RAVEN_RC_PATCHED}" "${RAVEN_RC_CLIENT}")
list(REMOVE_ITEM RA_SDK_SOURCES "${rcheevos_SOURCE_DIR}/src/rc_client.c")
list(APPEND RA_SDK_SOURCES "${RAVEN_RC_PATCHED}")
