#include <ravenemu/frontend.h>
#include <libretro.h>

#include <dlfcn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct raven_core_api {
    unsigned (*api_version)(void);
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*init)(void);
    void (*deinit)(void);
    void (*get_system_info)(struct retro_system_info *);
    void (*get_system_av_info)(struct retro_system_av_info *);
    bool (*load_game)(const struct retro_game_info *);
    void (*unload_game)(void);
    void (*run)(void);
} raven_core_api;

struct raven_frontend {
    void *library;
    raven_core_api api;
    raven_frontend_callbacks callbacks;
    struct retro_system_info system;
    struct retro_system_av_info av;
    char *system_directory;
    char *save_directory;
    char *rom_path;
    void *rom;
    int initialized;
    int loaded;
    unsigned pixel_format;
    char error[256];
};

/* Libretro callbacks have no userdata. This first slice deliberately permits
 * one live Libretro instance at a time; all calls must remain serialized. */
static raven_frontend *active = NULL;

static char *copy_string(const char *value) {
    if (!value) value = "";
    size_t size = strlen(value) + 1;
    char *result = (char *)malloc(size);
    if (result) memcpy(result, value, size);
    return result;
}

static void set_error(raven_frontend *host, const char *message) {
    if (host) snprintf(host->error, sizeof(host->error), "%s", message);
}

static void core_log(enum retro_log_level level, const char *fmt, ...) {
    (void)level;
    va_list args;
    va_start(args, fmt);
    vfprintf(stderr, fmt, args);
    va_end(args);
}

static bool environment(unsigned command, void *data) {
    if (!active) return false;
    switch (command) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            if (!data) return false;
            const enum retro_pixel_format format = *(const enum retro_pixel_format *)data;
            if (format != RETRO_PIXEL_FORMAT_0RGB1555 &&
                format != RETRO_PIXEL_FORMAT_RGB565 &&
                format != RETRO_PIXEL_FORMAT_XRGB8888) return false;
            active->pixel_format = (unsigned)format;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            if (!data) return false;
            *(bool *)data = true;
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            if (!data) return false;
            *(const char **)data = active->system_directory;
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            if (!data) return false;
            *(const char **)data = active->save_directory;
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
            if (!data) return false;
            *(const char **)data = active->system_directory;
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            if (!data) return false;
            *(unsigned *)data = 0;  /* Legacy variables; options UI comes later. */
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            if (!data) return false;
            *(bool *)data = false;
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE:
            return false; /* The core must use its own default options. */
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (!data) return false;
            ((struct retro_log_callback *)data)->log = core_log;
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (!data) return false;
            *(int *)data = RETRO_AV_ENABLE_VIDEO | RETRO_AV_ENABLE_AUDIO;
            return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            if (!data) return false;
            *(unsigned *)data = RETRO_LANGUAGE_FRENCH;
            return true;
        /* Features not yet exposed: sensors, achievements, rumble, hardware
         * rendering, core options UI, memory descriptors, and subsystems. */
        default:
            return false;
    }
}

static void on_video(const void *pixels, unsigned width, unsigned height, size_t pitch) {
    if (!active || !active->callbacks.video) return;
    raven_video_frame frame = {pixels, width, height, pitch, active->pixel_format};
    active->callbacks.video(&frame, active->callbacks.userdata);
}

static void on_audio_sample(int16_t left, int16_t right) {
    if (!active || !active->callbacks.audio) return;
    int16_t sample[] = {left, right};
    active->callbacks.audio(sample, 1, active->callbacks.userdata);
}

static size_t on_audio_batch(const int16_t *samples, size_t frames) {
    if (active && active->callbacks.audio && samples)
        active->callbacks.audio(samples, frames, active->callbacks.userdata);
    return frames;
}

static void on_input_poll(void) { /* Input sampled by the embedding platform. */ }

static int16_t on_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (!active || !active->callbacks.input) return 0;
    return active->callbacks.input(port, device, index, id, active->callbacks.userdata);
}

raven_frontend *raven_frontend_create(void) {
    return (raven_frontend *)calloc(1, sizeof(raven_frontend));
}

void raven_frontend_set_callbacks(raven_frontend *host,
                                  const raven_frontend_callbacks *callbacks) {
    if (!host) return;
    if (callbacks) host->callbacks = *callbacks;
    else memset(&host->callbacks, 0, sizeof(host->callbacks));
}

void raven_frontend_unload(raven_frontend *host) {
    if (!host) return;
    if (host->loaded && host->api.unload_game) host->api.unload_game();
    host->loaded = 0;
    free(host->rom);
    host->rom = NULL;
    free(host->rom_path);
    host->rom_path = NULL;
    memset(&host->av, 0, sizeof(host->av));
}

void raven_frontend_close_core(raven_frontend *host) {
    if (!host) return;
    raven_frontend_unload(host);
    if (host->initialized && host->api.deinit) host->api.deinit();
    host->initialized = 0;
    if (host->library) dlclose(host->library);
    host->library = NULL;
    if (active == host) active = NULL;
    free(host->system_directory);
    free(host->save_directory);
    host->system_directory = NULL;
    host->save_directory = NULL;
    memset(&host->api, 0, sizeof(host->api));
    memset(&host->system, 0, sizeof(host->system));
}

void raven_frontend_destroy(raven_frontend *host) {
    if (!host) return;
    raven_frontend_close_core(host);
    free(host);
}

int raven_frontend_open_core(raven_frontend *host, const char *library_path,
                            const char *system_directory, const char *save_directory) {
    if (!host || !library_path || !*library_path) return 0;
    if (active && active != host) {
        set_error(host, "A Libretro core is already active");
        return 0;
    }
    raven_frontend_close_core(host);
    host->error[0] = 0;
    host->system_directory = copy_string(system_directory);
    host->save_directory = copy_string(save_directory);
    if (!host->system_directory || !host->save_directory) {
        set_error(host, "Unable to allocate core paths");
        goto failed;
    }
    host->library = dlopen(library_path, RTLD_NOW | RTLD_LOCAL);
    if (!host->library) {
        const char *reason = dlerror();
        set_error(host, reason ? reason : "Unable to open Libretro library");
        goto failed;
    }
    /* POSIX dlsym() supports functions. memcpy avoids a direct cast to a
     * function pointer, forbidden by ISO C but defined on POSIX platforms. */
#define RESOLVE(name) do { \
    void *symbol = dlsym(host->library, "retro_" #name); \
    if (!symbol) { set_error(host, "Missing Libretro symbol: retro_" #name); goto failed; } \
    memcpy(&host->api.name, &symbol, sizeof(host->api.name)); \
} while (0)
    RESOLVE(api_version);
    RESOLVE(set_environment);
    RESOLVE(set_video_refresh);
    RESOLVE(set_audio_sample);
    RESOLVE(set_audio_sample_batch);
    RESOLVE(set_input_poll);
    RESOLVE(set_input_state);
    RESOLVE(init);
    RESOLVE(deinit);
    RESOLVE(get_system_info);
    RESOLVE(get_system_av_info);
    RESOLVE(load_game);
    RESOLVE(unload_game);
    RESOLVE(run);
#undef RESOLVE
    if (host->api.api_version() != RETRO_API_VERSION) {
        set_error(host, "Incompatible Libretro API version");
        goto failed;
    }
    active = host;
    host->pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
    host->api.set_environment(environment);
    host->api.set_video_refresh(on_video);
    host->api.set_audio_sample(on_audio_sample);
    host->api.set_audio_sample_batch(on_audio_batch);
    host->api.set_input_poll(on_input_poll);
    host->api.set_input_state(on_input_state);
    host->api.init();
    host->initialized = 1;
    host->api.get_system_info(&host->system);
    return 1;

failed:
    raven_frontend_close_core(host);
    return 0;
}

int raven_frontend_load_rom(raven_frontend *host, const void *rom,
                            size_t size, const char *path) {
    if (!host || !host->initialized || !rom || !size || active != host) {
        set_error(host, "Core not opened or ROM is empty");
        return 0;
    }
    raven_frontend_unload(host);
    host->rom = malloc(size);
    host->rom_path = copy_string(path);
    if (!host->rom || !host->rom_path) {
        set_error(host, "Unable to allocate ROM");
        raven_frontend_unload(host);
        return 0;
    }
    memcpy(host->rom, rom, size);
    struct retro_game_info game = {host->rom_path, host->rom, size, NULL};
    if (!host->api.load_game(&game)) {
        set_error(host, "Libretro core rejected the ROM");
        raven_frontend_unload(host);
        return 0;
    }
    host->loaded = 1;
    host->api.get_system_av_info(&host->av);
    return 1;
}

int raven_frontend_run_frame(raven_frontend *host) {
    if (!host || !host->loaded || active != host) {
        set_error(host, "No active game");
        return 0;
    }
    host->api.run();
    return 1;
}

int raven_frontend_get_info(const raven_frontend *host, raven_core_info *out) {
    if (!host || !out || !host->initialized) return 0;
    out->name = host->system.library_name;
    out->version = host->system.library_version;
    out->extensions = host->system.valid_extensions;
    out->width = host->av.geometry.base_width;
    out->height = host->av.geometry.base_height;
    out->fps = host->av.timing.fps;
    out->sample_rate = host->av.timing.sample_rate;
    return 1;
}

const char *raven_frontend_last_error(const raven_frontend *host) {
    return host ? host->error : "NULL frontend";
}
