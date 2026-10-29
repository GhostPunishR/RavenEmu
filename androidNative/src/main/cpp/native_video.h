#ifndef RAVEN_NATIVE_VIDEO_H
#define RAVEN_NATIVE_VIDEO_H

#include <stdint.h>

/* Software-frame GLES2 adapter. All calls belong to the EGL/GL owner.
 * init_gl is called only after a new context has been made current;
 * previous context handles must not be deleted in the new context. */
void raven_video_init_gl(void);

/* GL owner only, no immediate GL calls. Preferences survive reset/context
 * changes. Integer scaling falls back to aspect-fit below native size.
 * Brightness is clamped to -50..50, contrast to 50..150 (percent). */
void raven_video_set_options(int integer_scale, int brightness, int contrast);

/* rgba is tightly packed, top-first RGBA8888, with width*height*4 bytes.
 * dirty announces new CPU pixels; identical frames still redraw but do not
 * upload. Allocation/upload failures retain a retry even after dirty clears.
 * NULL/invalid geometry clears the display without showing the old texture. */
void raven_video_draw(const uint8_t *rgba, unsigned width, unsigned height,
                      int dirty, int surface_width, int surface_height);

/* Invalidate uploaded content on ROM close/change. No GL calls, so the
 * lifecycle may invoke this after the GL thread has been suspended. */
void raven_video_reset(void);

#endif
