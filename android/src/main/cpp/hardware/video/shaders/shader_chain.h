/* The passes of a RetroArch GLSL preset on GLES2, for RavenEmu (AGPLv3):
 * one program per pass, a framebuffer for each but the one drawn to the
 * screen, the lookup textures, and the uniforms the format names (MVPMatrix,
 * TextureSize, InputSize, OutputSize, FrameCount, FrameDirection, Texture,
 * Orig*, Prev*, PassN*, PassPrevN*, aliases, parameters). Written for
 * RavenEmu from the documented format; no RetroArch source is used. Every
 * call belongs to the GL thread. */
#ifndef RAVEN_SHADER_CHAIN_H
#define RAVEN_SHADER_CHAIN_H

#include <GLES2/gl2.h>
#include <stddef.h>
#include <stdint.h>
#include "shader_source.h"

struct raven_shader_lut_image {
    unsigned width, height;
    uint8_t *rgba;          /* top row first, width*height*4 bytes */
};

struct raven_shader_chain;

/* Compiles every pass; NULL and a French message on failure. The sources,
 * images and parameters are copied or uploaded: the caller keeps its own. */
struct raven_shader_chain *raven_shader_chain_create(const struct raven_shader_preset *preset,
        char *const *sources, const struct raven_shader_lut_image *luts,
        const struct raven_shader_parameter *parameters, unsigned parameter_count,
        char *error, size_t error_size);

/* A parameter's value from the next frame on; unknown names are ignored. */
void raven_shader_chain_set_parameter(struct raven_shader_chain *chain, const char *name, float value);

/* Draws `input`, the game's texture of width × height (its CPU pixels
 * `rgba` for the frame history), through the passes, the last one into the
 * viewport of the default framebuffer. `fresh` marks a new game frame:
 * FrameCount and the history move on. 0 when a framebuffer cannot be made:
 * the caller then draws the picture itself. Leaves framebuffer 0 bound. */
int raven_shader_chain_draw(struct raven_shader_chain *chain, GLuint input, const uint8_t *rgba, int fresh,
                            unsigned width, unsigned height, int vx, int vy, int vw, int vh);

/* Forgets the history, as a new game starts. */
void raven_shader_chain_reset(struct raven_shader_chain *chain);

/* Frees the chain; its GL objects too when `delete_gl` (the context lives). */
void raven_shader_chain_destroy(struct raven_shader_chain *chain, int delete_gl);

#endif
