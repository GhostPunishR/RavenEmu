#ifndef RAVENEMU_ANDROID_BRIDGE_INTERNAL_H
#define RAVENEMU_ANDROID_BRIDGE_INTERNAL_H

#include <ravenemu/frontend.h>
#include <stddef.h>
#include <stdint.h>

/* Between the player's bridge (bridge.c) and its RetroAchievements
 * (achievements_bridge.c). Emulation (GL) thread, or the UI thread once
 * GLSurfaceView paused it, like every core call. */

/* The player's frontend; NULL without a game. */
raven_frontend *raven_bridge_session(void);

/* After each frame shown; while paused, to keep the session alive. */
void raven_bridge_achievements_frame(void);
void raven_bridge_achievements_idle(void);
/* The game was reset: the achievements start over. */
void raven_bridge_achievements_state_loaded(void);
/* A save state's share: the achievements' progress written into `out` when
 * `capacity` holds it. Returns its size (the size needed when `out` is
 * NULL), 0 without a game or when it could not be written. */
size_t raven_bridge_achievements_progress(uint8_t *out, size_t capacity);
/* A state was loaded with this progress, or without (NULL): the achievements
 * take it back, or start over. */
void raven_bridge_achievements_progress_loaded(const uint8_t *progress, size_t size);
/* Before the game unloads: its memory map goes with it. */
void raven_bridge_achievements_close(void);

#endif
