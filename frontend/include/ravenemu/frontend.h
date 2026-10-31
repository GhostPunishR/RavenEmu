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
    /* Optional, NULL when the platform has none: the core then learns, when
     * it asks, that there is no rumble or no sensor. Same thread as the core.
     * rumble: retro_rumble_effect and its 0..65535 strength. sensor_state:
     * a retro_sensor_action at `rate` Hz, nonzero when the device has the
     * sensor. sensor_input: a RETRO_SENSOR_* reading, accelerometer in m/s²
     * along the screen as shown, gyroscope in rad/s, illuminance in lux. */
    int (*rumble)(unsigned port, unsigned effect, uint16_t strength, void *userdata);
    int (*sensor_state)(unsigned port, unsigned action, unsigned rate, void *userdata);
    float (*sensor_input)(unsigned port, unsigned id, void *userdata);
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
/* Restarts the game in place, as the console's reset button (retro_reset):
 * RetroAchievements' Hardcore asks for it. 0 without a game, or a core
 * without the call; the frames kept for preemption are forgotten. */
int raven_frontend_reset(raven_frontend *host);
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

/* Cheats, as entries of the core's menu (retro_cheat_reset and
 * retro_cheat_set, both optional): the whole list is replaced by `codes`,
 * each enabled, in order; an empty code is kept off. A core names a refused
 * code in a message (RavenCore: « code de triche N refusé (raison) »): the
 * index of the first refused code is returned, its message copied to `reason`
 * and kept from take_message; -1 when every code was taken; -2 without a
 * game, without a core that takes cheats, or with more than
 * RAVEN_FRONTEND_CHEATS_MAX codes. Emulation thread. */
#define RAVEN_FRONTEND_CHEATS_MAX 256u
int raven_frontend_set_cheats(raven_frontend *host, const char *const *codes, size_t count,
                              char *reason, size_t reason_size);

/* Preemptive frames (RetroArch's idea, RavenEmu code): with n frames, the
 * host keeps the core's state before each of the last n frames; when port
 * 0's joypad differs from the previous frame's, run_frame goes back n frames
 * and runs them again with the new buttons, neither shown nor heard, before
 * the frame itself. A game that answers a press k frames later then answers
 * min(n, k) frames sooner; more than k makes the picture jump. Each frame
 * costs a saved state, a change n more frames. 0, the default, turns it off;
 * above RAVEN_FRONTEND_PREEMPT_MAX is refused. The setting stays across
 * games; the history starts again at each load, restored state or SRAM, and
 * at forget_preemption (a pause: buttons released meanwhile are not a
 * change to replay). Emulation thread, like every call to the core. */
#define RAVEN_FRONTEND_PREEMPT_MAX 3u
int raven_frontend_set_preemptive_frames(raven_frontend *host, unsigned frames);
unsigned raven_frontend_preemptive_frames(const raven_frontend *host);
/* Frames the last run_frame ran again, 0 without a replay. */
unsigned raven_frontend_replayed_frames(const raven_frontend *host);
void raven_frontend_forget_preemption(raven_frontend *host);

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
