#ifndef RAVENEMU_FRONTEND_H
#define RAVENEMU_FRONTEND_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* First native frontend slice: one active Libretro instance per process.
 * The pointers delivered to callbacks are valid only during the callback. */
typedef struct raven_frontend raven_frontend;

typedef struct raven_video_frame {
    const void *pixels;  /* NULL means duplicate the previous frame. */
    unsigned width;
    unsigned height;
    size_t pitch;
    unsigned pixel_format;  /* Libretro retro_pixel_format enum. */
} raven_video_frame;

typedef struct raven_frontend_callbacks {
    void (*video)(const raven_video_frame *frame, void *userdata);
    void (*audio)(const int16_t *stereo_samples, size_t frames, void *userdata);
    int16_t (*input)(unsigned port, unsigned device, unsigned index,
                     unsigned id, void *userdata);
    void *userdata;
} raven_frontend_callbacks;

typedef struct raven_core_info {
    const char *name;       /* Owned by the loaded core, valid until close. */
    const char *version;
    const char *extensions;
    unsigned width;
    unsigned height;
    double fps;
    double sample_rate;
} raven_core_info;

/* A core option, as the core declared it (Libretro options v2 or v1, or the
 * legacy « Label; a|b|c » variables). Strings are owned by the host and stay
 * valid until the core closes or declares its options again. */
typedef struct raven_core_option {
    const char *key;
    const char *label;
    const char *info;      /* Empty when the core gives none. */
    const char *category;  /* Empty when the core gives none. */
    size_t value_count;
    const char *const *values;
    const char *const *labels;
    const char *value;     /* The current value: the core's default until set. */
} raven_core_option;

raven_frontend *raven_frontend_create(void);
void raven_frontend_destroy(raven_frontend *host);
int raven_frontend_open_core(raven_frontend *host, const char *library_path,
                            const char *system_directory, const char *save_directory);
void raven_frontend_set_callbacks(raven_frontend *host,
                                  const raven_frontend_callbacks *callbacks);
/* The ROM is copied by the host and kept alive until unload. */
int raven_frontend_load_rom(raven_frontend *host, const void *rom,
                            size_t size, const char *path);
int raven_frontend_run_frame(raven_frontend *host);
/* Battery SRAM is owned by the loaded core. Access only on the emulation
 * thread, after load_game and before unload_game. Zero size means no battery. */
size_t raven_frontend_battery_size(const raven_frontend *host);
int raven_frontend_read_battery(const raven_frontend *host,
                                void *dst, size_t size);
int raven_frontend_restore_battery(raven_frontend *host,
                                   const void *src, size_t size);

/* Optional quick-save snapshot; format/version is core-specific. */
size_t raven_frontend_state_size(const raven_frontend *host);
int raven_frontend_save_state(raven_frontend *host, void *dst, size_t size);
int raven_frontend_load_state(raven_frontend *host,
                              const void *src, size_t size);
void raven_frontend_unload(raven_frontend *host);
void raven_frontend_close_core(raven_frontend *host);
int raven_frontend_get_info(const raven_frontend *host, raven_core_info *out);

/* Core options of the open core, read and set on the emulation thread like
 * every call to the core. A value set before load_rom applies when the game
 * loads; one set while it runs reaches the core at its next frame, through
 * RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE, if the core rereads that option.
 * set_option keeps the current value and returns 0 for a key the core did not
 * declare or a value it does not offer. */
size_t raven_frontend_option_count(const raven_frontend *host);
int raven_frontend_get_option(const raven_frontend *host, size_t index,
                              raven_core_option *out);
const char *raven_frontend_option_value(const raven_frontend *host, const char *key);
int raven_frontend_set_option(raven_frontend *host, const char *key, const char *value);
/* The latest message the core asks to show (RETRO_ENVIRONMENT_SET_MESSAGE,
 * or SET_MESSAGE_EXT unless meant for the log alone or at debug level), once:
 * NULL when no new one came. Emulation thread. Valid UTF-8 without 4-byte
 * sequences, so Java's modified UTF-8 takes it as is; at most
 * RAVEN_FRONTEND_MESSAGE_MAX - 1 bytes. Valid until the next call to the core. */
#define RAVEN_FRONTEND_MESSAGE_MAX 256
const char *raven_frontend_take_message(raven_frontend *host);
const char *raven_frontend_last_error(const raven_frontend *host);

#ifdef __cplusplus
}
#endif
#endif
