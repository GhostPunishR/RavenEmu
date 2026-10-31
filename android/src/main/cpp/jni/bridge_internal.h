#ifndef RAVENEMU_ANDROID_BRIDGE_INTERNAL_H
#define RAVENEMU_ANDROID_BRIDGE_INTERNAL_H

#include <ravenemu/frontend.h>

/* Between the player's bridge (bridge.c) and its RetroAchievements
 * (achievements_bridge.c). Emulation (GL) thread, or the UI thread once
 * GLSurfaceView paused it, like every core call. */

/* The player's frontend; NULL without a game. */
raven_frontend *raven_bridge_session(void);

/* After each frame shown; while paused, to keep the session alive. */
void raven_bridge_achievements_frame(void);
void raven_bridge_achievements_idle(void);
/* A state was loaded: the achievements start over from it. */
void raven_bridge_achievements_state_loaded(void);
/* Before the game unloads: its memory map goes with it. */
void raven_bridge_achievements_close(void);

#endif
