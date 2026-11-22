/* The shader preset of the video driver, for RavenEmu (AGPLv3): read and
 * kept in memory on a worker thread, compiled on the GL thread, compiled
 * again after the context is lost, dropped for the built-in effects. */
#ifndef RAVEN_SHADER_STATE_H
#define RAVEN_SHADER_STATE_H

#include <GLES2/gl2.h>
#include <stddef.h>
#include <stdint.h>
#include "shader_source.h"

/* Worker thread: reads the preset `preset` of the folder `folder` (an
 * absolute path) and its shaders; it waits for raven_shaders_apply, its
 * lookup textures for raven_shaders_set_lut. -1 and a French message on
 * failure. A newer call replaces a prepared preset not yet applied. */
int raven_shaders_prepare(const char *folder, const char *preset, char *error, size_t error_size);

/* The prepared preset's lookup textures, as paths relative to its folder. */
unsigned raven_shaders_lut_count(void);
int raven_shaders_lut_path(unsigned index, char *out, size_t size);
/* Worker thread: a texture's pixels (RGBA, top row first), copied. */
int raven_shaders_set_lut(unsigned index, unsigned width, unsigned height, const uint8_t *rgba);

/* Any thread, no state: the parameters of a preset's passes, with the
 * preset's own values. Returns their count, or -1 with a message. */
int raven_shaders_describe(const char *folder, const char *preset, struct raven_shader_parameter *out,
                           unsigned max, char *error, size_t error_size);

/* Any thread: a parameter's value, for the prepared and the shown preset. */
void raven_shaders_set_parameter(const char *name, float value);

/* GL thread: compiles the prepared preset and shows it; on failure the
 * previous one stays (or the built-in effects) and -1 comes with a message. */
int raven_shaders_apply(char *error, size_t error_size);
/* GL thread: the built-in effects again. */
void raven_shaders_clear(void);
/* GL thread, a new context: the old one's objects are gone, not deleted. */
void raven_shaders_context_lost(void);
/* No GL call: the next frame starts a new history (another game). */
void raven_shaders_reset(void);
/* GL thread: draws through the preset; 0 without one, or when it failed. */
int raven_shaders_draw(GLuint input, const uint8_t *rgba, int fresh, unsigned width, unsigned height,
                       int vx, int vy, int vw, int vh);

#endif
