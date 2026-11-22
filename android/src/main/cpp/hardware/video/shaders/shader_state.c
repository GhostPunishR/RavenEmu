/* The shader preset of the video driver, for RavenEmu (AGPLv3); see
 * shader_state.h. One prepared preset (under the lock), one shown (the GL
 * thread's own), and the parameter changes waiting for the next frame. */
#include "shader_state.h"
#include "shader_chain.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_FILE (4u << 20)
#define MAX_CHANGES 64

struct shader_data {
    char folder[RAVEN_SHADER_PATH];
    struct raven_shader_preset preset;
    char *source[RAVEN_SHADER_MAX_PASSES];
    struct raven_shader_lut_image lut[RAVEN_SHADER_MAX_LUTS];
    struct raven_shader_parameter parameter[RAVEN_SHADER_MAX_PARAMETERS];
    unsigned parameters;
};

struct change {
    char name[RAVEN_SHADER_NAME];
    float value;
};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct shader_data *prepared;          /* under the lock */
static struct change changes[MAX_CHANGES];    /* under the lock */
static unsigned change_count;
static struct shader_data *shown;             /* GL thread */
static struct raven_shader_chain *chain;      /* GL thread */
static int chain_failed;                      /* GL thread: compiling it again failed */
static atomic_int reset_pending;

/* A file of the folder, `path` already checked by the preset reader. */
static char *read_file(void *user, const char *path, size_t *size) {
    const char *folder = user;
    char full[RAVEN_SHADER_PATH * 2];
    if (snprintf(full, sizeof(full), "%s/%s", folder, path) >= (int)sizeof(full)) return NULL;
    FILE *file = fopen(full, "rb");
    if (!file) return NULL;
    char *text = NULL;
    if (fseek(file, 0, SEEK_END) == 0) {
        long length = ftell(file);
        if (length >= 0 && (unsigned long)length <= MAX_FILE && fseek(file, 0, SEEK_SET) == 0) {
            text = malloc((size_t)length + 1);
            if (text && fread(text, 1, (size_t)length, file) == (size_t)length) {
                text[length] = '\0';
                if (size) *size = (size_t)length;
            } else {
                free(text);
                text = NULL;
            }
        }
    }
    fclose(file);
    return text;
}

static void free_data(struct shader_data *data) {
    if (!data) return;
    for (unsigned i = 0; i < RAVEN_SHADER_MAX_PASSES; ++i) free(data->source[i]);
    for (unsigned k = 0; k < RAVEN_SHADER_MAX_LUTS; ++k) free(data->lut[k].rgba);
    free(data);
}

/* The preset, its shaders and their parameters, the preset's values over
 * the shaders' own. NULL with a message. */
static struct shader_data *load(const char *folder, const char *preset, char *error, size_t error_size) {
    if (!folder || !preset || strlen(folder) >= RAVEN_SHADER_PATH) {
        snprintf(error, error_size, "Dossier des shaders invalide");
        return NULL;
    }
    struct shader_data *data = calloc(1, sizeof(*data));
    if (!data) {
        snprintf(error, error_size, "Mémoire insuffisante");
        return NULL;
    }
    snprintf(data->folder, sizeof(data->folder), "%s", folder);
    if (raven_shader_preset_load(&data->preset, preset, read_file, data->folder, error, error_size) != 0) {
        free_data(data);
        return NULL;
    }
    for (unsigned i = 0; i < data->preset.passes; ++i) {
        data->source[i] = read_file(data->folder, data->preset.pass[i].path, NULL);
        if (!data->source[i]) {
            snprintf(error, error_size, "Shader introuvable ou trop grand : %s", data->preset.pass[i].path);
            free_data(data);
            return NULL;
        }
        data->parameters = raven_shader_parameters(data->source[i], data->parameter, data->parameters,
                                                   RAVEN_SHADER_MAX_PARAMETERS);
    }
    for (unsigned v = 0; v < data->preset.values; ++v)
        for (unsigned k = 0; k < data->parameters; ++k)
            if (!strcmp(data->parameter[k].name, data->preset.value[v].name))
                data->parameter[k].initial = data->preset.value[v].value;
    return data;
}

int raven_shaders_prepare(const char *folder, const char *preset, char *error, size_t error_size) {
    if (error && error_size) error[0] = '\0';
    struct shader_data *data = load(folder, preset, error, error_size);
    if (!data) return -1;
    pthread_mutex_lock(&lock);
    struct shader_data *old = prepared;
    prepared = data;
    pthread_mutex_unlock(&lock);
    free_data(old);
    return 0;
}

unsigned raven_shaders_lut_count(void) {
    pthread_mutex_lock(&lock);
    unsigned count = prepared ? prepared->preset.luts : 0;
    pthread_mutex_unlock(&lock);
    return count;
}

int raven_shaders_lut_path(unsigned index, char *out, size_t size) {
    int found = 0;
    pthread_mutex_lock(&lock);
    if (prepared && index < prepared->preset.luts && out && size) {
        snprintf(out, size, "%s", prepared->preset.lut[index].path);
        found = 1;
    }
    pthread_mutex_unlock(&lock);
    return found;
}

int raven_shaders_set_lut(unsigned index, unsigned width, unsigned height, const uint8_t *rgba) {
    if (!rgba || !width || !height || width > 4096 || height > 4096) return 0;
    size_t bytes = (size_t)width * height * 4;
    uint8_t *copy = malloc(bytes);
    if (!copy) return 0;
    memcpy(copy, rgba, bytes);
    int stored = 0;
    pthread_mutex_lock(&lock);
    if (prepared && index < prepared->preset.luts) {
        free(prepared->lut[index].rgba);
        prepared->lut[index].rgba = copy;
        prepared->lut[index].width = width;
        prepared->lut[index].height = height;
        stored = 1;
    }
    pthread_mutex_unlock(&lock);
    if (!stored) free(copy);
    return stored;
}

int raven_shaders_describe(const char *folder, const char *preset, struct raven_shader_parameter *out,
                           unsigned max, char *error, size_t error_size) {
    if (error && error_size) error[0] = '\0';
    struct shader_data *data = load(folder, preset, error, error_size);
    if (!data) return -1;
    unsigned count = data->parameters < max ? data->parameters : max;
    if (count) memcpy(out, data->parameter, count * sizeof(*out));
    free_data(data);
    return (int)count;
}

void raven_shaders_set_parameter(const char *name, float value) {
    if (!name || strlen(name) >= RAVEN_SHADER_NAME) return;
    pthread_mutex_lock(&lock);
    if (prepared)
        for (unsigned k = 0; k < prepared->parameters; ++k)
            if (!strcmp(prepared->parameter[k].name, name)) prepared->parameter[k].initial = value;
    unsigned slot = 0;
    while (slot < change_count && strcmp(changes[slot].name, name)) ++slot;
    if (slot < MAX_CHANGES) {
        snprintf(changes[slot].name, sizeof(changes[slot].name), "%s", name);
        changes[slot].value = value;
        if (slot == change_count) change_count++;
    }
    pthread_mutex_unlock(&lock);
}

static struct raven_shader_chain *build(const struct shader_data *data, char *error, size_t error_size) {
    for (unsigned k = 0; k < data->preset.luts; ++k)
        if (!data->lut[k].rgba) {
            snprintf(error, error_size, "Texture absente ou illisible : %s", data->preset.lut[k].path);
            return NULL;
        }
    return raven_shader_chain_create(&data->preset, data->source, data->lut, data->parameter,
                                     data->parameters, error, error_size);
}

int raven_shaders_apply(char *error, size_t error_size) {
    if (error && error_size) error[0] = '\0';
    pthread_mutex_lock(&lock);
    struct shader_data *data = prepared;
    prepared = NULL;
    pthread_mutex_unlock(&lock);
    if (!data) {
        snprintf(error, error_size, "Aucun preset prêt");
        return -1;
    }
    struct raven_shader_chain *next = build(data, error, error_size);
    if (!next) {
        free_data(data);
        return -1;
    }
    raven_shader_chain_destroy(chain, 1);
    free_data(shown);
    chain = next;
    shown = data;
    chain_failed = 0;
    return 0;
}

void raven_shaders_clear(void) {
    raven_shader_chain_destroy(chain, 1);
    chain = NULL;
    free_data(shown);
    shown = NULL;
    chain_failed = 0;
    pthread_mutex_lock(&lock);
    struct shader_data *old = prepared;
    prepared = NULL;
    change_count = 0;
    pthread_mutex_unlock(&lock);
    free_data(old);
}

void raven_shaders_context_lost(void) {
    raven_shader_chain_destroy(chain, 0);
    chain = NULL;
    chain_failed = 0;
}

void raven_shaders_reset(void) {
    atomic_store(&reset_pending, 1);
}

int raven_shaders_draw(GLuint input, const uint8_t *rgba, int fresh, unsigned width, unsigned height,
                       int vx, int vy, int vw, int vh) {
    struct change local[MAX_CHANGES];
    unsigned count;
    pthread_mutex_lock(&lock);
    count = change_count;
    memcpy(local, changes, count * sizeof(*local));
    change_count = 0;
    pthread_mutex_unlock(&lock);
    if (!shown) return 0;
    for (unsigned c = 0; c < count; ++c) {
        for (unsigned k = 0; k < shown->parameters; ++k)
            if (!strcmp(shown->parameter[k].name, local[c].name)) shown->parameter[k].initial = local[c].value;
        raven_shader_chain_set_parameter(chain, local[c].name, local[c].value);
    }
    if (!chain && !chain_failed) {
        /* After a lost context: the same preset, compiled again. */
        char ignored[256];
        chain = build(shown, ignored, sizeof(ignored));
        chain_failed = chain == NULL;
    }
    if (!chain) return 0;
    if (atomic_exchange(&reset_pending, 0)) raven_shader_chain_reset(chain);
    return raven_shader_chain_draw(chain, input, rgba, fresh, width, height, vx, vy, vw, vh);
}
