/* The .glslp reader and the .glsl stages, on presets written here: no GL,
 * no file system. */
#include "shader_preset.h"
#include "shader_source.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

/* A folder in memory: path, then contents. */
static const char *files[][2] = {
    {"crt/crt-test.glslp",
     "# A comment line\n"
     "shaders = \"2\"\n"
     "shader0 = shaders/first.glsl\n"
     "filter_linear0 = false\n"
     "scale_type0 = source\n"
     "scale0 = 2.0\n"
     "alias0 = FirstPass\n"
     "wrap_mode0 = repeat\n"
     "frame_count_mod0 = 2\n"
     "shader1 = \"../shared/second.glsl\" // after a quote, ignored\n"
     "filter_linear1 = true\n"
     "scale_type_x1 = viewport\n"
     "scale_x1 = 1.0\n"
     "scale_type_y1 = absolute\n"
     "scale_y1 = 240\n"
     "filter_linear2 = true\n"
     "textures = \"MASK;Noise\"\n"
     "MASK = luts/mask.png\n"
     "MASK_linear = true\n"
     "MASK_wrap_mode = mirrored_repeat\n"
     "Noise = ../shared/noise.png\n"
     "parameters = \"CURVATURE;GLOW\"\n"
     "CURVATURE = 0.25\n"
     "GLOW = 1.5\n"},
    {"crt/derived.glslp",
     "#reference \"crt-test.glslp\"\n"
     "CURVATURE = 0.75\n"
     "filter_linear1 = false\n"},
    {"crt/loop.glslp", "#reference \"loop.glslp\"\nshaders = 1\nshader0 = a.glsl\n"},
    {"crt/escape.glslp", "shaders = 1\nshader0 = ../../../etc/passwd\n"},
    {"crt/absolute.glslp", "shaders = 1\nshader0 = /system/x.glsl\n"},
    {"crt/scheme.glslp", "shaders = 1\nshader0 = :/shaders/x.glsl\n"},
    {"crt/none.glslp", "shader0 = a.glsl\n"},
    {"crt/hole.glslp", "shaders = 2\nshader0 = a.glsl\n"},
    {"crt/many.glslp", "shaders = 17\n"},
};

static char *reader(void *user, const char *path, size_t *size) {
    (void)user;
    for (size_t i = 0; i < sizeof(files) / sizeof(files[0]); ++i) {
        if (strcmp(files[i][0], path)) continue;
        size_t n = strlen(files[i][1]);
        char *copy = malloc(n + 1);
        memcpy(copy, files[i][1], n + 1);
        if (size) *size = n;
        return copy;
    }
    return NULL;
}

static int resolving(void) {
    char out[RAVEN_SHADER_PATH];
    CHECK(raven_shader_resolve("crt", "shaders/a.glsl", out, sizeof(out)) == 0 && !strcmp(out, "crt/shaders/a.glsl"));
    CHECK(raven_shader_resolve("crt/x", "../y/./b.glsl", out, sizeof(out)) == 0 && !strcmp(out, "crt/y/b.glsl"));
    CHECK(raven_shader_resolve("", "a\\b.glsl", out, sizeof(out)) == 0 && !strcmp(out, "a/b.glsl"));
    CHECK(raven_shader_resolve("crt", "../a.glsl", out, sizeof(out)) == 0 && !strcmp(out, "a.glsl"));
    CHECK(raven_shader_resolve("crt", "../../a.glsl", out, sizeof(out)) == -1);
    CHECK(raven_shader_resolve("", "/abs.glsl", out, sizeof(out)) == -1);
    CHECK(raven_shader_resolve("", "C:/abs.glsl", out, sizeof(out)) == -1);
    CHECK(raven_shader_resolve("", ":/shaders/a.glsl", out, sizeof(out)) == -1);
    CHECK(raven_shader_resolve("", "..", out, sizeof(out)) == -1);
    CHECK(raven_shader_resolve("", "", out, sizeof(out)) == -1);
    return 0;
}

static int reading(void) {
    struct raven_shader_preset *preset = calloc(1, sizeof(*preset));
    char error[256];
    CHECK(raven_shader_preset_load(preset, "crt/crt-test.glslp", reader, NULL, error, sizeof(error)) == 0);
    CHECK(preset->passes == 2);
    CHECK(!strcmp(preset->pass[0].path, "crt/shaders/first.glsl"));
    CHECK(!strcmp(preset->pass[1].path, "shared/second.glsl"));
    CHECK(preset->pass[0].filter == 0 && preset->pass[1].filter == 1);
    CHECK(preset->pass[0].scale_x == RAVEN_SCALE_SOURCE && preset->pass[0].scale_y == RAVEN_SCALE_SOURCE);
    CHECK(fabsf(preset->pass[0].factor_x - 2.0f) < 1e-6f && fabsf(preset->pass[0].factor_y - 2.0f) < 1e-6f);
    CHECK(!strcmp(preset->pass[0].alias, "FirstPass") && preset->pass[1].alias[0] == '\0');
    CHECK(preset->pass[0].wrap == RAVEN_WRAP_REPEAT && preset->pass[1].wrap == RAVEN_WRAP_EDGE);
    CHECK(preset->pass[0].frame_count_mod == 2 && preset->pass[1].frame_count_mod == 0);
    CHECK(preset->pass[1].scale_x == RAVEN_SCALE_VIEWPORT && preset->pass[1].scale_y == RAVEN_SCALE_ABSOLUTE);
    CHECK(fabsf(preset->pass[1].factor_y - 240.0f) < 1e-6f);
    CHECK(preset->final_filter == 1);
    CHECK(preset->luts == 2);
    CHECK(!strcmp(preset->lut[0].name, "MASK") && !strcmp(preset->lut[0].path, "crt/luts/mask.png"));
    CHECK(preset->lut[0].linear == 1 && preset->lut[0].wrap == RAVEN_WRAP_MIRRORED);
    CHECK(!strcmp(preset->lut[1].name, "Noise") && !strcmp(preset->lut[1].path, "shared/noise.png"));
    int curvature = 0, glow = 0;
    for (unsigned i = 0; i < preset->values; ++i) {
        if (!strcmp(preset->value[i].name, "CURVATURE")) curvature = fabsf(preset->value[i].value - 0.25f) < 1e-6f;
        if (!strcmp(preset->value[i].name, "GLOW")) glow = fabsf(preset->value[i].value - 1.5f) < 1e-6f;
        CHECK(strcmp(preset->value[i].name, "shaders") && strcmp(preset->value[i].name, "scale0"));
    }
    CHECK(curvature && glow);

    /* A reference: its keys first, the referencing file's over them. */
    CHECK(raven_shader_preset_load(preset, "crt/derived.glslp", reader, NULL, error, sizeof(error)) == 0);
    CHECK(preset->passes == 2 && !strcmp(preset->pass[1].path, "shared/second.glsl"));
    CHECK(preset->pass[1].filter == 0);
    for (unsigned i = 0; i < preset->values; ++i)
        if (!strcmp(preset->value[i].name, "CURVATURE")) CHECK(fabsf(preset->value[i].value - 0.75f) < 1e-6f);
    free(preset);
    return 0;
}

static int refusing(void) {
    struct raven_shader_preset *preset = calloc(1, sizeof(*preset));
    char error[256];
    const char *bad[] = {"crt/loop.glslp", "crt/escape.glslp", "crt/absolute.glslp", "crt/scheme.glslp",
                         "crt/none.glslp", "crt/hole.glslp", "crt/many.glslp", "crt/missing.glslp",
                         "../crt/crt-test.glslp"};
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); ++i) {
        error[0] = '\0';
        CHECK(raven_shader_preset_load(preset, bad[i], reader, NULL, error, sizeof(error)) == -1);
        CHECK(error[0] != '\0');
    }
    free(preset);
    return 0;
}

static int sources(void) {
    const char *glsl =
        "#version 130\n"
        "#pragma parameter CURVATURE \"Courbure\" 0.1 0.0 1.0 0.05\n"
        "  #pragma parameter GLOW \"Glow\" 1.0 0.0 3.0\n"
        "#pragma parameter BROKEN \"no numbers\"\n"
        "#pragma parameter CURVATURE \"Twice\" 0.9 0.0 1.0 0.1\n"
        "#extension GL_OES_standard_derivatives : enable\n"
        "#if defined(VERTEX)\nvoid main() {}\n#elif defined(FRAGMENT)\nvoid main() {}\n#endif\n";
    struct raven_shader_parameter found[8];
    unsigned count = raven_shader_parameters(glsl, found, 0, 8);
    CHECK(count == 2);
    CHECK(!strcmp(found[0].name, "CURVATURE") && !strcmp(found[0].description, "Courbure"));
    CHECK(fabsf(found[0].initial - 0.1f) < 1e-6f && fabsf(found[0].maximum - 1.0f) < 1e-6f
          && fabsf(found[0].step - 0.05f) < 1e-6f);
    CHECK(!strcmp(found[1].name, "GLOW") && found[1].step == 0.0f);
    /* A second pass's declaration of the same name adds nothing. */
    CHECK(raven_shader_parameters(glsl, found, count, 8) == 2);

    char *vertex = raven_shader_stage(glsl, 0), *fragment = raven_shader_stage(glsl, 1);
    CHECK(vertex && fragment);
    CHECK(!strncmp(vertex, "#define VERTEX\n#define PARAMETER_UNIFORM\n", 41));
    CHECK(!strstr(vertex, "#version") && !strstr(fragment, "#version"));
    CHECK(!strstr(vertex, "precision"));
    /* The fragment's #extension comes before the precision it was given. */
    const char *extension = strstr(fragment, "#extension"), *precision = strstr(fragment, "precision");
    CHECK(extension && precision && extension < precision);
    CHECK(strstr(fragment, "#elif defined(FRAGMENT)"));
    free(vertex);
    free(fragment);
    /* A fragment with its own precision is left as it is. */
    fragment = raven_shader_stage("precision mediump float;\nvoid main() {}\n", 1);
    CHECK(fragment && !strstr(fragment, "GL_FRAGMENT_PRECISION_HIGH"));
    free(fragment);
    return 0;
}

int main(void) {
    if (resolving() || reading() || refusing() || sources()) return 1;
    puts("shader presets: ok");
    return 0;
}
