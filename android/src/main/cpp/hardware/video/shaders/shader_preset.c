/* RetroArch GLSL shader presets (.glslp) for RavenEmu (AGPLv3). Written for
 * RavenEmu from the documented preset format; see shader_preset.h. */
#include "shader_preset.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_DEPTH 8
#define MAX_ENTRIES 512
#define KEY_SIZE 80
#define VALUE_SIZE RAVEN_SHADER_PATH

/* A key, its value, and the folder of the file that set it: a path is read
 * from where it is written, whichever preset references that one. */
struct entry {
    char key[KEY_SIZE];
    char value[VALUE_SIZE];
    char base[RAVEN_SHADER_PATH];
};

struct entries {
    struct entry item[MAX_ENTRIES];
    unsigned count;
};

/* Truncates when too long: the names it copies are checked by their users. */
static void copy(char *out, size_t size, const char *text) {
    size_t n = strlen(text);
    if (n >= size) n = size - 1;
    memcpy(out, text, n);
    out[n] = '\0';
}

static void fail(char *error, size_t size, const char *message, const char *detail) {
    if (!error || !size) return;
    snprintf(error, size, "%s%s%s", message, detail && *detail ? " : " : "", detail ? detail : "");
}

int raven_shader_resolve(const char *base, const char *relative, char *out, size_t out_size) {
    char joined[RAVEN_SHADER_PATH * 2];
    if (!relative || !*relative || !out || out_size < 2) return -1;
    if (relative[0] == '/' || relative[0] == '\\' || strchr(relative, ':')) return -1;
    if (snprintf(joined, sizeof(joined), "%s%s%s", base && *base ? base : "", base && *base ? "/" : "",
                 relative) >= (int)sizeof(joined)) return -1;
    for (char *c = joined; *c; ++c) if (*c == '\\') *c = '/';
    /* Segment by segment: "." skipped, ".." pops, never above the folder. */
    size_t starts[RAVEN_SHADER_PATH], depth = 0, length = 0;
    char *cursor = joined;
    out[0] = '\0';
    while (*cursor) {
        char *slash = strchr(cursor, '/');
        size_t n = slash ? (size_t)(slash - cursor) : strlen(cursor);
        if (n == 0 || (n == 1 && cursor[0] == '.')) {
        } else if (n == 2 && cursor[0] == '.' && cursor[1] == '.') {
            if (depth == 0) return -1;
            length = starts[--depth];
            out[length] = '\0';
        } else {
            size_t start = length + (length ? 1 : 0);
            if (start + n + 1 > out_size || depth >= RAVEN_SHADER_PATH) return -1;
            starts[depth++] = length;
            if (length) out[length] = '/';
            memcpy(out + start, cursor, n);
            length = start + n;
            out[length] = '\0';
        }
        if (!slash) break;
        cursor = slash + 1;
    }
    return length ? 0 : -1;
}

static void folder_of(const char *path, char *out, size_t size) {
    const char *slash = strrchr(path, '/');
    size_t n = slash ? (size_t)(slash - path) : 0;
    if (n >= size) n = size - 1;
    memcpy(out, path, n);
    out[n] = '\0';
}

static char *trim(char *text) {
    while (isspace((unsigned char)*text)) ++text;
    char *end = text + strlen(text);
    while (end > text && isspace((unsigned char)end[-1])) *--end = '\0';
    return text;
}

/* A value without its quotes, or up to a comment when unquoted. */
static void unquote(char *value) {
    char *v = trim(value);
    if (*v == '"') {
        char *close = strchr(v + 1, '"');
        if (close) *close = '\0';
        memmove(value, v + 1, strlen(v + 1) + 1);
        return;
    }
    char *comment = strchr(v, '#');
    if (comment) *comment = '\0';
    comment = strstr(v, "//");
    if (comment) *comment = '\0';
    memmove(value, v, strlen(v) + 1);
    trim(value);
    char *end = value + strlen(value);
    while (end > value && isspace((unsigned char)end[-1])) *--end = '\0';
}

static void set_entry(struct entries *entries, const char *key, const char *value, const char *base) {
    struct entry *slot = NULL;
    for (unsigned i = 0; i < entries->count; ++i)
        if (!strcmp(entries->item[i].key, key)) slot = &entries->item[i];
    if (!slot) {
        if (entries->count == MAX_ENTRIES) return;
        slot = &entries->item[entries->count++];
        snprintf(slot->key, sizeof(slot->key), "%s", key);
    }
    snprintf(slot->value, sizeof(slot->value), "%s", value);
    snprintf(slot->base, sizeof(slot->base), "%s", base);
}

static int read_preset(struct entries *entries, const char *path, int depth, raven_shader_reader read,
                       void *user, char *error, size_t error_size) {
    if (depth > MAX_DEPTH) {
        fail(error, error_size, "Trop de presets en cascade (#reference)", path);
        return -1;
    }
    size_t size = 0;
    char *text = read(user, path, &size);
    if (!text) {
        fail(error, error_size, "Fichier introuvable dans le dossier des shaders", path);
        return -1;
    }
    char base[RAVEN_SHADER_PATH];
    folder_of(path, base, sizeof(base));
    int result = 0;
    char *line = text;
    while (line && *line && result == 0) {
        char *next = strpbrk(line, "\r\n");
        if (next) *next++ = '\0';
        char *content = trim(line);
        if (!strncmp(content, "#reference", 10) && isspace((unsigned char)content[10])) {
            char target[VALUE_SIZE], resolved[RAVEN_SHADER_PATH];
            snprintf(target, sizeof(target), "%s", content + 10);
            unquote(target);
            if (raven_shader_resolve(base, target, resolved, sizeof(resolved)) != 0) {
                fail(error, error_size, "Chemin refusé dans #reference", target);
                result = -1;
            } else {
                result = read_preset(entries, resolved, depth + 1, read, user, error, error_size);
            }
        } else if (*content && *content != '#' && strncmp(content, "//", 2)) {
            char *equal = strchr(content, '=');
            if (equal) {
                *equal = '\0';
                char *key = trim(content);
                char value[VALUE_SIZE];
                snprintf(value, sizeof(value), "%s", equal + 1);
                unquote(value);
                if (*key && strlen(key) < KEY_SIZE) set_entry(entries, key, value, base);
            }
        }
        while (next && (*next == '\n' || *next == '\r')) ++next;
        line = next;
    }
    free(text);
    return result;
}

static const struct entry *find(const struct entries *entries, const char *key) {
    for (unsigned i = 0; i < entries->count; ++i)
        if (!strcmp(entries->item[i].key, key)) return &entries->item[i];
    return NULL;
}

static const struct entry *findf(const struct entries *entries, const char *format, unsigned index) {
    char key[KEY_SIZE];
    snprintf(key, sizeof(key), format, index);
    return find(entries, key);
}

static int boolean(const struct entry *entry, int fallback) {
    if (!entry) return fallback;
    return !strcmp(entry->value, "true") || !strcmp(entry->value, "1");
}

static enum raven_shader_wrap wrap_mode(const struct entry *entry) {
    if (!entry) return RAVEN_WRAP_EDGE;
    if (!strcmp(entry->value, "repeat")) return RAVEN_WRAP_REPEAT;
    if (!strcmp(entry->value, "mirrored_repeat")) return RAVEN_WRAP_MIRRORED;
    return RAVEN_WRAP_EDGE;
}

static enum raven_shader_scale scale_type(const struct entry *entry) {
    if (!entry) return RAVEN_SCALE_DEFAULT;
    if (!strcmp(entry->value, "source")) return RAVEN_SCALE_SOURCE;
    if (!strcmp(entry->value, "viewport")) return RAVEN_SCALE_VIEWPORT;
    if (!strcmp(entry->value, "absolute")) return RAVEN_SCALE_ABSOLUTE;
    return RAVEN_SCALE_DEFAULT;
}

static int number(const char *text, float *out) {
    char *end = NULL;
    float value = strtof(text, &end);
    if (end == text) return 0;
    while (isspace((unsigned char)*end)) ++end;
    if (*end) return 0;
    *out = value;
    return 1;
}

static int is_pass_key(const char *key) {
    static const char *prefixes[] = {"shader", "filter_linear", "wrap_mode", "scale_type_x", "scale_type_y",
        "scale_type", "scale_x", "scale_y", "scale", "mipmap_input", "float_framebuffer",
        "srgb_framebuffer", "frame_count_mod", "alias"};
    for (size_t i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); ++i) {
        size_t n = strlen(prefixes[i]);
        if (!strncmp(key, prefixes[i], n) && key[n] && isdigit((unsigned char)key[n])) {
            const char *digits = key + n;
            while (isdigit((unsigned char)*digits)) ++digits;
            if (!*digits) return 1;
        }
    }
    return 0;
}

static int build_pass(struct raven_shader_pass *pass, const struct entries *entries, unsigned i,
                      char *error, size_t error_size) {
    const struct entry *shader = findf(entries, "shader%u", i);
    if (!shader || !*shader->value) {
        char detail[32];
        snprintf(detail, sizeof(detail), "shader%u", i);
        fail(error, error_size, "Passe sans fichier de shader", detail);
        return -1;
    }
    if (raven_shader_resolve(shader->base, shader->value, pass->path, sizeof(pass->path)) != 0) {
        fail(error, error_size, "Chemin de shader refusé", shader->value);
        return -1;
    }
    const struct entry *alias = findf(entries, "alias%u", i);
    copy(pass->alias, sizeof(pass->alias), alias ? alias->value : "");
    const struct entry *filter = findf(entries, "filter_linear%u", i);
    pass->filter = filter ? boolean(filter, 0) : -1;
    pass->wrap = wrap_mode(findf(entries, "wrap_mode%u", i));
    enum raven_shader_scale both = scale_type(findf(entries, "scale_type%u", i));
    const struct entry *x = findf(entries, "scale_type_x%u", i), *y = findf(entries, "scale_type_y%u", i);
    pass->scale_x = x ? scale_type(x) : both;
    pass->scale_y = y ? scale_type(y) : both;
    float scale = 1.0f, value;
    const struct entry *factor = findf(entries, "scale%u", i);
    if (factor && number(factor->value, &value)) scale = value;
    pass->factor_x = pass->factor_y = scale;
    factor = findf(entries, "scale_x%u", i);
    if (factor && number(factor->value, &value)) pass->factor_x = value;
    factor = findf(entries, "scale_y%u", i);
    if (factor && number(factor->value, &value)) pass->factor_y = value;
    if (pass->factor_x <= 0.0f || pass->factor_y <= 0.0f || pass->factor_x > 8192.0f || pass->factor_y > 8192.0f) {
        fail(error, error_size, "Échelle de passe invalide", shader->value);
        return -1;
    }
    pass->mipmap_input = boolean(findf(entries, "mipmap_input%u", i), 0);
    pass->float_framebuffer = boolean(findf(entries, "float_framebuffer%u", i), 0);
    pass->srgb_framebuffer = boolean(findf(entries, "srgb_framebuffer%u", i), 0);
    const struct entry *mod = findf(entries, "frame_count_mod%u", i);
    pass->frame_count_mod = mod && number(mod->value, &value) && value >= 1.0f ? (unsigned)value : 0;
    return 0;
}

static int build_luts(struct raven_shader_preset *preset, const struct entries *entries,
                      char *error, size_t error_size) {
    const struct entry *textures = find(entries, "textures");
    if (!textures || !*textures->value) return 0;
    char list[VALUE_SIZE];
    snprintf(list, sizeof(list), "%s", textures->value);
    for (char *name = strtok(list, ";"); name; name = strtok(NULL, ";")) {
        name = trim(name);
        if (!*name) continue;
        if (preset->luts == RAVEN_SHADER_MAX_LUTS) {
            fail(error, error_size, "Trop de textures dans le preset", name);
            return -1;
        }
        struct raven_shader_lut *lut = &preset->lut[preset->luts];
        const struct entry *path = find(entries, name);
        if (!path || raven_shader_resolve(path->base, path->value, lut->path, sizeof(lut->path)) != 0) {
            fail(error, error_size, "Texture introuvable ou chemin refusé", name);
            return -1;
        }
        char key[KEY_SIZE];
        copy(lut->name, sizeof(lut->name), name);
        snprintf(key, sizeof(key), "%s_linear", name);
        lut->linear = boolean(find(entries, key), 0);
        snprintf(key, sizeof(key), "%s_mipmap", name);
        lut->mipmap = boolean(find(entries, key), 0);
        snprintf(key, sizeof(key), "%s_wrap_mode", name);
        lut->wrap = wrap_mode(find(entries, key));
        preset->luts++;
    }
    return 0;
}

int raven_shader_preset_load(struct raven_shader_preset *preset, const char *path,
                             raven_shader_reader read, void *user, char *error, size_t error_size) {
    if (error && error_size) error[0] = '\0';
    if (!preset || !path || !read) return -1;
    memset(preset, 0, sizeof(*preset));
    preset->final_filter = -1;
    char root[RAVEN_SHADER_PATH];
    if (raven_shader_resolve("", path, root, sizeof(root)) != 0) {
        fail(error, error_size, "Chemin de preset refusé", path);
        return -1;
    }
    struct entries *entries = calloc(1, sizeof(*entries));
    if (!entries) {
        fail(error, error_size, "Mémoire insuffisante", NULL);
        return -1;
    }
    int result = read_preset(entries, root, 0, read, user, error, error_size);
    const struct entry *count = find(entries, "shaders");
    float passes = 0.0f;
    if (result == 0 && (!count || !number(count->value, &passes) || passes < 1.0f
            || passes > RAVEN_SHADER_MAX_PASSES)) {
        fail(error, error_size, "Nombre de passes absent ou trop grand (16 au plus)", root);
        result = -1;
    }
    if (result == 0) preset->passes = (unsigned)passes;
    for (unsigned i = 0; result == 0 && i < preset->passes; ++i)
        result = build_pass(&preset->pass[i], entries, i, error, error_size);
    if (result == 0) {
        const struct entry *final = findf(entries, "filter_linear%u", preset->passes);
        preset->final_filter = final ? boolean(final, 0) : -1;
        result = build_luts(preset, entries, error, error_size);
    }
    /* What remains with a number may be a parameter of the shaders. */
    for (unsigned i = 0; result == 0 && i < entries->count; ++i) {
        const struct entry *entry = &entries->item[i];
        float value;
        if (is_pass_key(entry->key) || !strcmp(entry->key, "shaders") || !number(entry->value, &value)) continue;
        if (preset->values == RAVEN_SHADER_MAX_VALUES || strlen(entry->key) >= RAVEN_SHADER_NAME) continue;
        copy(preset->value[preset->values].name, RAVEN_SHADER_NAME, entry->key);
        preset->value[preset->values++].value = value;
    }
    free(entries);
    return result;
}
