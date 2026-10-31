#include <ravenemu/frontend.h>
#include <libretro.h>
#include "frontend_internal.h"

#include <dlfcn.h>
#include <stdarg.h>
#include <stdint.h>
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
    /* Optional here: a core without it cannot be restarted in place. */
    void (*reset)(void);
    void *(*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
    size_t (*serialize_size)(void);
    bool (*serialize)(void *, size_t);
    bool (*unserialize)(const void *, size_t);
} raven_core_api;

/* A declared core option and the index of its current value. */
typedef struct raven_option {
    char *key;
    char *label;
    char *info;
    char *category;
    size_t value_count;
    char **values;
    char **labels;
    size_t selected;
} raven_option;

/* Bounds on what a core may declare; RavenCore declares about a dozen. */
#define RAVEN_MAX_OPTIONS 128

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
    raven_option *options;
    size_t option_count;
    int options_updated;  /* Reported once by GET_VARIABLE_UPDATE. */
    char message[RAVEN_FRONTEND_MESSAGE_MAX];
    int message_pending;
    /* Preemptive frames (raven_frontend_set_preemptive_frames): the state
     * before each of the last `preempt_frames` frames, in a ring of slots. */
    unsigned preempt_frames;     /* The setting; kept across games. */
    unsigned preempt_valid;      /* States of the current timeline in the ring. */
    unsigned preempt_head;       /* Slot of the coming frame. */
    unsigned preempt_replayed;   /* By the last run_frame. */
    int preempt_replaying;       /* Rerunning frames: no image, no sound. */
    int preempt_ran;             /* A frame ran since the game loaded. */
    uint16_t preempt_joypad;     /* Port 0's buttons at the last frame. */
    uint64_t serialization_quirks;
    /* The core's memory map (SET_MEMORY_MAPS), copied: the achievements read
     * the game through it. Descriptors point into the core's memory. */
    struct retro_memory_map memory_map;
    struct retro_memory_descriptor *memory_descriptors;
    size_t preempt_state_size;
    unsigned char *preempt_states;
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

static char *copy_range(const char *start, size_t length) {
    char *result = (char *)malloc(length + 1);
    if (!result) return NULL;
    memcpy(result, start, length);
    result[length] = 0;
    return result;
}

static void free_option(raven_option *option) {
    for (size_t i = 0; i < option->value_count; ++i) {
        if (option->values) free(option->values[i]);
        if (option->labels) free(option->labels[i]);
    }
    free(option->values);
    free(option->labels);
    free(option->key);
    free(option->label);
    free(option->info);
    free(option->category);
    memset(option, 0, sizeof(*option));
}

static void free_options(raven_option *options, size_t count) {
    for (size_t i = 0; i < count; ++i) free_option(&options[i]);
    free(options);
}

static raven_option *find_option(const raven_frontend *host, const char *key) {
    if (!host || !key) return NULL;
    for (size_t i = 0; i < host->option_count; ++i)
        if (!strcmp(host->options[i].key, key)) return &host->options[i];
    return NULL;
}

static int find_value(const raven_option *option, const char *value, size_t *index) {
    if (!value) return 0;
    for (size_t i = 0; i < option->value_count; ++i) {
        if (!strcmp(option->values[i], value)) {
            *index = i;
            return 1;
        }
    }
    return 0;
}

/* Fills one option from what a core declared; NULL labels fall back to the
 * values, an unknown default to the first value. Returns 0 when out of memory. */
static int fill_option(raven_option *option, const char *key, const char *label,
                       const char *info, const char *category,
                       const char *const *values, const char *const *labels,
                       size_t count, const char *default_value) {
    option->key = copy_string(key);
    option->label = copy_string(label ? label : key);
    option->info = copy_string(info);
    option->category = copy_string(category);
    option->values = (char **)calloc(count, sizeof(char *));
    option->labels = (char **)calloc(count, sizeof(char *));
    if (!option->key || !option->label || !option->info || !option->category ||
        !option->values || !option->labels) return 0;
    option->value_count = count;
    for (size_t i = 0; i < count; ++i) {
        option->values[i] = copy_string(values[i]);
        option->labels[i] = copy_string(labels && labels[i] ? labels[i] : values[i]);
        if (!option->values[i] || !option->labels[i]) return 0;
    }
    if (!find_value(option, default_value, &option->selected)) option->selected = 0;
    return 1;
}

/* Replaces the declared options; a value chosen before stays chosen when the
 * new declaration still offers it. */
static void replace_options(raven_frontend *host, raven_option *options, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        const raven_option *previous = find_option(host, options[i].key);
        size_t index;
        if (previous && find_value(&options[i], previous->values[previous->selected], &index))
            options[i].selected = index;
    }
    free_options(host->options, host->option_count);
    host->options = options;
    host->option_count = count;
}

/* Options v2 (or v1, without categories): definitions end with a NULL key,
 * values with a NULL value. */
static bool declare_v2(raven_frontend *host, const struct retro_core_options_v2 *declared) {
    if (!declared || !declared->definitions) return false;
    raven_option *options = (raven_option *)calloc(RAVEN_MAX_OPTIONS, sizeof(raven_option));
    if (!options) return false;
    size_t count = 0;
    for (const struct retro_core_option_v2_definition *definition = declared->definitions;
         definition->key && count < RAVEN_MAX_OPTIONS; ++definition) {
        const char *values[RETRO_NUM_CORE_OPTION_VALUES_MAX];
        const char *labels[RETRO_NUM_CORE_OPTION_VALUES_MAX];
        size_t value_count = 0;
        while (value_count < RETRO_NUM_CORE_OPTION_VALUES_MAX && definition->values[value_count].value) {
            values[value_count] = definition->values[value_count].value;
            labels[value_count] = definition->values[value_count].label;
            ++value_count;
        }
        if (!value_count) continue;
        if (!fill_option(&options[count++], definition->key, definition->desc, definition->info,
                         definition->category_key, values, labels, value_count,
                         definition->default_value)) {
            free_options(options, count);
            return false;
        }
    }
    replace_options(host, options, count);
    return true;
}

static bool declare_v1(raven_frontend *host, const struct retro_core_option_definition *definitions) {
    if (!definitions) return false;
    raven_option *options = (raven_option *)calloc(RAVEN_MAX_OPTIONS, sizeof(raven_option));
    if (!options) return false;
    size_t count = 0;
    for (const struct retro_core_option_definition *definition = definitions;
         definition->key && count < RAVEN_MAX_OPTIONS; ++definition) {
        const char *values[RETRO_NUM_CORE_OPTION_VALUES_MAX];
        const char *labels[RETRO_NUM_CORE_OPTION_VALUES_MAX];
        size_t value_count = 0;
        while (value_count < RETRO_NUM_CORE_OPTION_VALUES_MAX && definition->values[value_count].value) {
            values[value_count] = definition->values[value_count].value;
            labels[value_count] = definition->values[value_count].label;
            ++value_count;
        }
        if (!value_count) continue;
        if (!fill_option(&options[count++], definition->key, definition->desc, definition->info,
                         NULL, values, labels, value_count, definition->default_value)) {
            free_options(options, count);
            return false;
        }
    }
    replace_options(host, options, count);
    return true;
}

/* Legacy variables: « Label; first|second|third », the first being the default. */
static bool declare_variables(raven_frontend *host, const struct retro_variable *variables) {
    if (!variables) return false;
    raven_option *options = (raven_option *)calloc(RAVEN_MAX_OPTIONS, sizeof(raven_option));
    if (!options) return false;
    size_t count = 0;
    for (const struct retro_variable *variable = variables;
         variable->key && count < RAVEN_MAX_OPTIONS; ++variable) {
        const char *text = variable->value ? variable->value : "";
        const char *separator = strchr(text, ';');
        if (!separator) continue;
        char *label = copy_range(text, (size_t)(separator - text));
        const char *list = separator + 1;
        while (*list == ' ') ++list;
        char *pieces[RETRO_NUM_CORE_OPTION_VALUES_MAX];
        size_t value_count = 0;
        int failed = !label;
        while (!failed && *list && value_count < RETRO_NUM_CORE_OPTION_VALUES_MAX) {
            const char *end = strchr(list, '|');
            const size_t length = end ? (size_t)(end - list) : strlen(list);
            if (length) {
                pieces[value_count] = copy_range(list, length);
                if (!pieces[value_count]) failed = 1; else ++value_count;
            }
            list = end ? end + 1 : list + length;
        }
        if (!failed && value_count &&
            !fill_option(&options[count++], variable->key, label, NULL, NULL,
                         (const char *const *)pieces, NULL, value_count, pieces[0]))
            failed = 1;
        for (size_t i = 0; i < value_count; ++i) free(pieces[i]);
        free(label);
        if (failed) {
            free_options(options, count);
            return false;
        }
    }
    replace_options(host, options, count);
    return true;
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

static bool host_rumble(unsigned port, enum retro_rumble_effect effect, uint16_t strength) {
    if (!active || !active->callbacks.rumble) return false;
    return active->callbacks.rumble(port, (unsigned)effect, strength, active->callbacks.userdata) != 0;
}

static bool host_sensor_state(unsigned port, enum retro_sensor_action action, unsigned rate) {
    if (!active || !active->callbacks.sensor_state) return false;
    return active->callbacks.sensor_state(port, (unsigned)action, rate, active->callbacks.userdata) != 0;
}

static float host_sensor_input(unsigned port, unsigned id) {
    if (!active || !active->callbacks.sensor_input) return 0.0f;
    return active->callbacks.sensor_input(port, id, active->callbacks.userdata);
}

/* Keep a message as valid UTF-8 of one to three bytes per character: a
 * malformed byte or a 4-byte character becomes '?', and the end never cuts
 * a character in two. */
static void keep_message(raven_frontend *host, const char *text) {
    if (!text || !*text) return;
    const unsigned char *in = (const unsigned char *)text;
    size_t out = 0;
    while (*in && out + 1 < sizeof(host->message)) {
        size_t length = *in < 0x80 ? 1 : (*in & 0xe0) == 0xc0 ? 2 : (*in & 0xf0) == 0xe0 ? 3 : 0;
        for (size_t i = 1; i < length; ++i)
            if ((in[i] & 0xc0) != 0x80) length = 0;
        if (length == 2 && *in < 0xc2) length = 0;  /* overlong */
        if (length == 3 && ((*in == 0xe0 && in[1] < 0xa0) || (*in == 0xed && in[1] >= 0xa0)))
            length = 0;  /* overlong, or a UTF-16 surrogate */
        if (!length) {
            host->message[out++] = '?';
            ++in;
            while ((*in & 0xc0) == 0x80) ++in;  /* the rest of a bad character */
            continue;
        }
        if (out + length >= sizeof(host->message)) break;
        memcpy(host->message + out, in, length);
        out += length;
        in += length;
    }
    host->message[out] = 0;
    host->message_pending = out > 0;
}

/* Preemptive frames, RetroArch's idea written for RavenEmu: the history is
 * dropped whenever the core's state jumps (a load, a restored state or
 * SRAM, a pause) so a replay never goes back across the jump. */
static void preempt_forget(raven_frontend *host) {
    host->preempt_valid = 0;
    host->preempt_head = 0;
}

static void preempt_free(raven_frontend *host) {
    free(host->preempt_states);
    host->preempt_states = NULL;
    host->preempt_state_size = 0;
    preempt_forget(host);
}

/* The ring for the current state size, allocated on first use; 0 when the
 * core cannot save its state whole, or before its first frame if it says so. */
static int preempt_prepare(raven_frontend *host) {
    if (!host->preempt_frames ||
        (host->serialization_quirks & RETRO_SERIALIZATION_QUIRK_INCOMPLETE)) return 0;
    if ((host->serialization_quirks & RETRO_SERIALIZATION_QUIRK_MUST_INITIALIZE) && !host->preempt_ran)
        return 0;
    const size_t size = host->api.serialize_size();
    if (!size || size > SIZE_MAX / host->preempt_frames) return 0;
    if (host->preempt_states && size == host->preempt_state_size) return 1;
    preempt_free(host);
    host->preempt_states = (unsigned char *)malloc(size * host->preempt_frames);
    if (!host->preempt_states) return 0;
    host->preempt_state_size = size;
    return 1;
}

/* The slot of the state before the frame `back` frames ago; 0 is the coming
 * frame's, which also held the oldest state until now. */
static unsigned char *preempt_slot(const raven_frontend *host, unsigned back) {
    const unsigned slot = (host->preempt_head + host->preempt_frames - back) % host->preempt_frames;
    return host->preempt_states + (size_t)slot * host->preempt_state_size;
}

/* Port 0's joypad as the core will read it this frame. */
static uint16_t preempt_joypad(const raven_frontend *host) {
    uint16_t buttons = 0;
    if (!host->callbacks.input) return 0;
    for (unsigned id = 0; id < 16; ++id)
        if (host->callbacks.input(0, RETRO_DEVICE_JOYPAD, 0, id, host->callbacks.userdata))
            buttons |= (uint16_t)(1u << id);
    return buttons;
}

/* The buttons changed: back `depth` frames, and run them again with the
 * buttons of now, silently, keeping the new timeline's states. */
static void preempt_replay(raven_frontend *host, unsigned depth) {
    if (!host->api.unserialize(preempt_slot(host, depth), host->preempt_state_size)) {
        preempt_forget(host);
        return;
    }
    int kept = 1;
    host->preempt_replaying = 1;
    for (unsigned back = depth; back > 0; --back) {
        if (back < depth && kept)
            kept = host->api.serialize(preempt_slot(host, back), host->preempt_state_size);
        host->api.run();
    }
    host->preempt_replaying = 0;
    host->preempt_replayed = depth;
    if (!kept) preempt_forget(host);
}

static void forget_memory_map(raven_frontend *host) {
    free(host->memory_descriptors);
    host->memory_descriptors = NULL;
    host->memory_map.descriptors = NULL;
    host->memory_map.num_descriptors = 0;
}

/* RavenCore declares its achievement memory as it loads a game; RetroArch
 * keeps the descriptors, and so do we, whole. */
static bool keep_memory_map(raven_frontend *host, const struct retro_memory_map *map) {
    if (!map || (map->num_descriptors && !map->descriptors) || map->num_descriptors > 4096) return false;
    struct retro_memory_descriptor *copy = NULL;
    if (map->num_descriptors) {
        copy = (struct retro_memory_descriptor *)malloc(sizeof(*copy) * map->num_descriptors);
        if (!copy) return false;
        memcpy(copy, map->descriptors, sizeof(*copy) * map->num_descriptors);
    }
    forget_memory_map(host);
    host->memory_descriptors = copy;
    host->memory_map.descriptors = copy;
    host->memory_map.num_descriptors = map->num_descriptors;
    return true;
}

const struct retro_memory_map *raven_frontend_memory_map(const raven_frontend *host) {
    return host && host->loaded && host->memory_map.num_descriptors ? &host->memory_map : NULL;
}

void *raven_frontend_core_memory(const raven_frontend *host, unsigned id, size_t *size) {
    if (size) *size = 0;
    if (!host || !host->loaded || active != host) return NULL;
    void *data = host->api.get_memory_data(id);
    const size_t length = data ? host->api.get_memory_size(id) : 0;
    if (size) *size = length;
    return length ? data : NULL;
}

static bool environment(unsigned command, void *data) {
    if (!active) return false;
    switch (command) {
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
            return keep_memory_map(active, (const struct retro_memory_map *)data);
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
            return data != NULL;
        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
            if (!data || !active->callbacks.rumble) return false;
            ((struct retro_rumble_interface *)data)->set_rumble_state = host_rumble;
            return true;
        case RETRO_ENVIRONMENT_GET_SENSOR_INTERFACE: {
            if (!data || !active->callbacks.sensor_state || !active->callbacks.sensor_input) return false;
            struct retro_sensor_interface *sensors = (struct retro_sensor_interface *)data;
            sensors->set_sensor_state = host_sensor_state;
            sensors->get_sensor_input = host_sensor_input;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
            if (!data) return false;
            *(unsigned *)data = 1;
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            const struct retro_message *message = (const struct retro_message *)data;
            if (!message) return false;
            keep_message(active, message->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            const struct retro_message_ext *message = (const struct retro_message_ext *)data;
            if (!message) return false;
            if (message->target != RETRO_MESSAGE_TARGET_LOG && message->level != RETRO_LOG_DEBUG)
                keep_message(active, message->msg);
            return true;
        }
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
            *(unsigned *)data = 2;
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
            return declare_v2(active, (const struct retro_core_options_v2 *)data);
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
            return data && declare_v2(active, ((const struct retro_core_options_v2_intl *)data)->us);
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
            return declare_v1(active, (const struct retro_core_option_definition *)data);
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
            return data && declare_v1(active, ((const struct retro_core_options_intl *)data)->us);
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            return declare_variables(active, (const struct retro_variable *)data);
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            if (!data) return false;
            *(bool *)data = active->options_updated != 0;
            active->options_updated = 0;
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable *variable = (struct retro_variable *)data;
            if (!variable) return false;
            const raven_option *option = find_option(active, variable->key);
            variable->value = option ? option->values[option->selected] : NULL;
            return option != NULL;
        }
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (!data) return false;
            ((struct retro_log_callback *)data)->log = core_log;
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (!data) return false;
            /* Replayed frames are neither shown nor heard: the core may skip
             * drawing them. */
            *(int *)data = active->preempt_replaying ? 0 : RETRO_AV_ENABLE_VIDEO | RETRO_AV_ENABLE_AUDIO;
            return true;
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
            if (!data) return false;
            /* Honoured: an incomplete state turns preemptive frames off, one
             * valid only after a frame waits for it, and the size is read
             * again before each save. */
            *(uint64_t *)data &= RETRO_SERIALIZATION_QUIRK_INCOMPLETE |
                                 RETRO_SERIALIZATION_QUIRK_MUST_INITIALIZE |
                                 RETRO_SERIALIZATION_QUIRK_CORE_VARIABLE_SIZE;
            active->serialization_quirks = *(uint64_t *)data;
            return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            if (!data) return false;
            *(unsigned *)data = RETRO_LANGUAGE_FRENCH;
            return true;
        /* Features not yet exposed: sensors, achievements, rumble, hardware
         * rendering, option visibility, memory descriptors, and subsystems. */
        default:
            return false;
    }
}

static void on_video(const void *pixels, unsigned width, unsigned height, size_t pitch) {
    if (!active || !active->callbacks.video || active->preempt_replaying) return;
    raven_video_frame frame = {pixels, width, height, pitch, active->pixel_format};
    active->callbacks.video(&frame, active->callbacks.userdata);
}

static void on_audio_sample(int16_t left, int16_t right) {
    if (!active || !active->callbacks.audio || active->preempt_replaying) return;
    int16_t sample[] = {left, right};
    active->callbacks.audio(sample, 1, active->callbacks.userdata);
}

static size_t on_audio_batch(const int16_t *samples, size_t frames) {
    if (active && active->callbacks.audio && samples && !active->preempt_replaying)
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
    host->preempt_ran = 0;
    preempt_free(host);
    forget_memory_map(host);
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
    free_options(host->options, host->option_count);
    host->options = NULL;
    host->option_count = 0;
    host->options_updated = 0;
    host->message_pending = 0;
    host->serialization_quirks = 0;
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
    {
        void *symbol = dlsym(host->library, "retro_reset");
        if (symbol) memcpy(&host->api.reset, &symbol, sizeof(host->api.reset));
    }
    RESOLVE(get_memory_data);
    RESOLVE(get_memory_size);
    RESOLVE(serialize_size);
    RESOLVE(serialize);
    RESOLVE(unserialize);
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
    host->options_updated = 0;  /* The core reads every option as the game loads. */
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
    host->preempt_replayed = 0;
    if (preempt_prepare(host)) {
        const uint16_t joypad = preempt_joypad(host);
        if (host->preempt_valid && joypad != host->preempt_joypad)
            preempt_replay(host, host->preempt_valid);
        host->preempt_joypad = joypad;
        if (host->api.serialize(preempt_slot(host, 0), host->preempt_state_size)) {
            host->preempt_head = (host->preempt_head + 1) % host->preempt_frames;
            if (host->preempt_valid < host->preempt_frames) ++host->preempt_valid;
        } else {
            preempt_forget(host);
        }
    }
    host->api.run();
    host->preempt_ran = 1;
    return 1;
}

int raven_frontend_reset(raven_frontend *host) {
    if (!host || !host->loaded || active != host || !host->api.reset) return 0;
    /* The frames kept behind belong to the game before the restart. */
    preempt_forget(host);
    host->api.reset();
    return 1;
}

int raven_frontend_set_preemptive_frames(raven_frontend *host, unsigned frames) {
    if (!host || frames > RAVEN_FRONTEND_PREEMPT_MAX) return 0;
    if (frames != host->preempt_frames) {
        preempt_free(host);
        host->preempt_frames = frames;
    }
    return 1;
}

unsigned raven_frontend_preemptive_frames(const raven_frontend *host) {
    return host ? host->preempt_frames : 0;
}

unsigned raven_frontend_replayed_frames(const raven_frontend *host) {
    return host ? host->preempt_replayed : 0;
}

void raven_frontend_forget_preemption(raven_frontend *host) {
    if (host) preempt_forget(host);
}

size_t raven_frontend_battery_size(const raven_frontend *host) {
    if (!host || !host->loaded || active != host) return 0;
    return host->api.get_memory_size(RETRO_MEMORY_SAVE_RAM);
}

int raven_frontend_read_battery(const raven_frontend *host,
                                void *dst, size_t size) {
    size_t expected = raven_frontend_battery_size(host);
    if (!expected || !dst || expected != size) return 0;
    const void *data = host->api.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!data) return 0;
    memcpy(dst, data, expected);
    return 1;
}

int raven_frontend_restore_battery(raven_frontend *host,
                                   const void *src, size_t size) {
    const size_t expected = raven_frontend_battery_size(host);
    if (!expected || !src || expected != size) {
        set_error(host, "Battery SRAM size mismatch");
        return 0;
    }
    void *data = host->api.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!data) {
        set_error(host, "Core does not expose writable battery SRAM");
        return 0;
    }
    memcpy(data, src, expected);
    preempt_forget(host);
    return 1;
}

size_t raven_frontend_state_size(const raven_frontend *host) {
    if (!host || !host->loaded || active != host) return 0;
    return host->api.serialize_size();
}

int raven_frontend_save_state(raven_frontend *host, void *dst, size_t size) {
    size_t expected = raven_frontend_state_size(host);
    if (!expected || !dst || size != expected) return 0;
    return host->api.serialize(dst, size) ? 1 : 0;
}

int raven_frontend_load_state(raven_frontend *host, const void *src, size_t size) {
    if (!host || !host->loaded || active != host || !src || !size) return 0;
    if (!host->api.unserialize(src, size)) {
        set_error(host, "Core rejected save-state data");
        return 0;
    }
    preempt_forget(host);
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

size_t raven_frontend_option_count(const raven_frontend *host) {
    return host ? host->option_count : 0;
}

int raven_frontend_get_option(const raven_frontend *host, size_t index,
                              raven_core_option *out) {
    if (!host || !out || index >= host->option_count) return 0;
    const raven_option *option = &host->options[index];
    out->key = option->key;
    out->label = option->label;
    out->info = option->info;
    out->category = option->category;
    out->value_count = option->value_count;
    out->values = (const char *const *)option->values;
    out->labels = (const char *const *)option->labels;
    out->value = option->values[option->selected];
    return 1;
}

const char *raven_frontend_option_value(const raven_frontend *host, const char *key) {
    const raven_option *option = find_option(host, key);
    return option ? option->values[option->selected] : NULL;
}

int raven_frontend_set_option(raven_frontend *host, const char *key, const char *value) {
    raven_option *option = find_option(host, key);
    size_t index;
    if (!option || !find_value(option, value, &index)) {
        set_error(host, option ? "Value not offered by this core option" : "Unknown core option");
        return 0;
    }
    if (option->selected != index) {
        option->selected = index;
        host->options_updated = 1;
    }
    return 1;
}

const char *raven_frontend_take_message(raven_frontend *host) {
    if (!host || !host->message_pending) return NULL;
    host->message_pending = 0;
    return host->message;
}

const char *raven_frontend_last_error(const raven_frontend *host) {
    return host ? host->error : "NULL frontend";
}
