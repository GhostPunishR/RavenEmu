/* One pass of a RetroArch GLSL shader (.glsl) for RavenEmu (AGPLv3): its
 * "#pragma parameter" lines, and the vertex and fragment sources GLES2
 * compiles from the single file. Written for RavenEmu from the documented
 * format. No GL: host-testable. */
#ifndef RAVEN_SHADER_SOURCE_H
#define RAVEN_SHADER_SOURCE_H

#include "shader_preset.h"

#define RAVEN_SHADER_MAX_PARAMETERS 64
#define RAVEN_SHADER_DESCRIPTION 128

/* #pragma parameter NAME "Description" initial minimum maximum [step] */
struct raven_shader_parameter {
    char name[RAVEN_SHADER_NAME];
    char description[RAVEN_SHADER_DESCRIPTION];
    float initial, minimum, maximum, step;
};

/* Adds the parameters of `source` not already in `out` (a name is shared by
 * every pass that declares it); returns the new count, at most `max`. */
unsigned raven_shader_parameters(const char *source, struct raven_shader_parameter *out,
                                 unsigned count, unsigned max);

/* The source of one stage, malloc'd: "#define VERTEX" or "#define FRAGMENT"
 * and "#define PARAMETER_UNIFORM", a default float precision for the
 * fragment, then the file without its #version line (GLSL ES 1.00 has
 * none). NULL when out of memory. */
char *raven_shader_stage(const char *source, int fragment);

#endif
