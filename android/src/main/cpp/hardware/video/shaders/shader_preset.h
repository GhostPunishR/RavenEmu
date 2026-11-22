/* RetroArch GLSL shader presets (.glslp) for RavenEmu (AGPLv3), read from
 * their documented format: "key = value" lines, #reference, one shader file
 * per pass, lookup textures and parameter values. Written for RavenEmu; no
 * RetroArch source is used. No GL: host-testable. */
#ifndef RAVEN_SHADER_PRESET_H
#define RAVEN_SHADER_PRESET_H

#include <stddef.h>

#define RAVEN_SHADER_MAX_PASSES 16
#define RAVEN_SHADER_MAX_LUTS 8
#define RAVEN_SHADER_MAX_VALUES 128
#define RAVEN_SHADER_NAME 64
#define RAVEN_SHADER_PATH 512

enum raven_shader_scale {
    RAVEN_SCALE_DEFAULT = 0, /* source ×1, the last pass viewport ×1 */
    RAVEN_SCALE_SOURCE,
    RAVEN_SCALE_VIEWPORT,
    RAVEN_SCALE_ABSOLUTE
};

enum raven_shader_wrap {
    RAVEN_WRAP_EDGE = 0, /* clamp_to_edge, and clamp_to_border, which GLES2 lacks */
    RAVEN_WRAP_REPEAT,
    RAVEN_WRAP_MIRRORED
};

struct raven_shader_pass {
    char path[RAVEN_SHADER_PATH];   /* the .glsl, relative to the preset's folder */
    char alias[RAVEN_SHADER_NAME];  /* "" without one */
    int filter;                     /* how this pass reads its input: -1 unset, 0 nearest, 1 linear */
    enum raven_shader_wrap wrap;
    enum raven_shader_scale scale_x, scale_y;
    float factor_x, factor_y;       /* a scale, or pixels when absolute */
    int mipmap_input, float_framebuffer, srgb_framebuffer;
    unsigned frame_count_mod;       /* 0: none */
};

struct raven_shader_lut {
    char name[RAVEN_SHADER_NAME];   /* the sampler's name in the shaders */
    char path[RAVEN_SHADER_PATH];
    int linear, mipmap;
    enum raven_shader_wrap wrap;
};

/* Every other key with a number: a parameter's value, as RetroArch reads it. */
struct raven_shader_value {
    char name[RAVEN_SHADER_NAME];
    float value;
};

struct raven_shader_preset {
    unsigned passes, luts, values;
    int final_filter;               /* filter_linear<passes>: -1 unset */
    struct raven_shader_pass pass[RAVEN_SHADER_MAX_PASSES];
    struct raven_shader_lut lut[RAVEN_SHADER_MAX_LUTS];
    struct raven_shader_value value[RAVEN_SHADER_MAX_VALUES];
};

/* One file of the folder, `path` already checked: its bytes, NUL-terminated
 * and malloc'd, or NULL when absent, unreadable or too large. */
typedef char *(*raven_shader_reader)(void *user, const char *path, size_t *size);

/* Reads the preset at `path`, relative to the folder, and the presets it
 * references (eight levels at most). 0 on success; otherwise -1 and a
 * French message in `error`. */
int raven_shader_preset_load(struct raven_shader_preset *preset, const char *path,
                             raven_shader_reader read, void *user, char *error, size_t error_size);

/* `relative` from the folder `base` ("" for the root) into `out`: slashes
 * only, "." and ".." resolved. -1 for an absolute path, a drive, a scheme or
 * a path that leaves the folder. */
int raven_shader_resolve(const char *base, const char *relative, char *out, size_t out_size);

#endif
