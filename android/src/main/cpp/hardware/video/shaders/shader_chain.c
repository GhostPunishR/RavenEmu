/* The passes of a RetroArch GLSL preset on GLES2, for RavenEmu (AGPLv3).
 * Written for RavenEmu from the documented format; see shader_chain.h. */
#include "shader_chain.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* PrevTexture, then Prev1Texture to Prev6Texture: the frames before. */
#define HISTORY 7
#define MAX_TEXTURE 4096

/* A texture a pass may read besides its input, its sizes and coordinates. */
struct sampler {
    GLint texture, texture_size, input_size, texcoord;
};

struct pass {
    GLuint program;
    GLint vertex, texcoord, color;
    GLint mvp, frame_count, frame_direction, output_size, texture_size, input_size, texture;
    int frame_count_int, frame_direction_int;
    struct sampler orig, prev[HISTORY];
    /* PassN: the output of pass N, from 1; PassPrevN: N passes back. */
    struct sampler output[RAVEN_SHADER_MAX_PASSES], back[RAVEN_SHADER_MAX_PASSES + 1];
    struct sampler alias[RAVEN_SHADER_MAX_PASSES];
    GLint lut[RAVEN_SHADER_MAX_LUTS];
    GLint parameter[RAVEN_SHADER_MAX_PARAMETERS];
    GLuint framebuffer, target;
    unsigned width, height;
};

struct raven_shader_chain {
    struct raven_shader_preset preset;
    struct pass pass[RAVEN_SHADER_MAX_PASSES];
    struct raven_shader_parameter parameter[RAVEN_SHADER_MAX_PARAMETERS];
    unsigned parameters;
    /* The last pass has its own scale: drawn to a framebuffer, then copied. */
    int last_to_framebuffer;
    GLuint copy_program;
    GLint copy_vertex, copy_texcoord, copy_texture;
    GLuint lut[RAVEN_SHADER_MAX_LUTS];
    unsigned lut_width[RAVEN_SHADER_MAX_LUTS], lut_height[RAVEN_SHADER_MAX_LUTS];
    GLuint history[HISTORY];
    unsigned history_width, history_height, history_head, history_filled, history_used;
    unsigned frame_count;
    int npot_wrap;
    GLint max_units;
};

static const GLfloat ortho[16] = {2.f, 0.f, 0.f, 0.f, 0.f, 2.f, 0.f, 0.f,
                                  0.f, 0.f, -1.f, 0.f, -1.f, -1.f, 0.f, 1.f};
/* Into a framebuffer, its row 0 holds the picture's first row; the screen's
 * pass is drawn upside down so that the picture stands upright. */
static const GLfloat quad[8] = {0.f, 0.f, 1.f, 0.f, 0.f, 1.f, 1.f, 1.f};
static const GLfloat flipped[8] = {0.f, 1.f, 1.f, 1.f, 0.f, 0.f, 1.f, 0.f};

static void fail(char *error, size_t size, unsigned pass, const char *file, const char *what, const char *log) {
    if (!error || !size) return;
    char first[160] = "";
    if (log) {
        size_t n = strcspn(log, "\r\n");
        if (n >= sizeof(first)) n = sizeof(first) - 1;
        memcpy(first, log, n);
        first[n] = '\0';
    }
    snprintf(error, size, "Passe %u (%s) : %s%s%s", pass + 1, file, what, *first ? " — " : "", first);
}

static GLuint compile(GLenum type, const char *source, char *log, size_t log_size) {
    GLuint shader = glCreateShader(type);
    if (!shader) return 0;
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    GLint status = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status == GL_TRUE) return shader;
    log[0] = '\0';
    glGetShaderInfoLog(shader, (GLsizei)log_size, NULL, log);
    glDeleteShader(shader);
    return 0;
}

static GLuint link(const char *vertex_source, const char *fragment_source, char *log, size_t log_size,
                   const char **what) {
    GLuint vertex = compile(GL_VERTEX_SHADER, vertex_source, log, log_size), fragment = 0, program = 0;
    *what = "le vertex shader ne compile pas";
    if (!vertex) return 0;
    fragment = compile(GL_FRAGMENT_SHADER, fragment_source, log, log_size);
    *what = "le fragment shader ne compile pas";
    if (fragment) {
        program = glCreateProgram();
        if (program) {
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            glLinkProgram(program);
            GLint status = GL_FALSE;
            glGetProgramiv(program, GL_LINK_STATUS, &status);
            if (status != GL_TRUE) {
                log[0] = '\0';
                glGetProgramInfoLog(program, (GLsizei)log_size, NULL, log);
                glDeleteProgram(program);
                program = 0;
                *what = "l'édition de liens échoue";
            }
        }
        glDeleteShader(fragment);
    }
    glDeleteShader(vertex);
    return program;
}

/* Whether an active uniform is an integer: FrameCount is int in most
 * shaders, float in some. */
static int is_int(GLuint program, const char *wanted) {
    GLint count = 0;
    glGetProgramiv(program, GL_ACTIVE_UNIFORMS, &count);
    for (GLint i = 0; i < count; ++i) {
        char name[96];
        GLsizei length = 0;
        GLint size = 0;
        GLenum type = 0;
        glGetActiveUniform(program, (GLuint)i, sizeof(name), &length, &size, &type, name);
        if (!strcmp(name, wanted)) return type == GL_INT;
    }
    return 0;
}

static void find_sampler(GLuint program, struct sampler *sampler, const char *base) {
    char name[RAVEN_SHADER_NAME + 16];
    snprintf(name, sizeof(name), "%sTexture", base);
    sampler->texture = glGetUniformLocation(program, name);
    snprintf(name, sizeof(name), "%sTextureSize", base);
    sampler->texture_size = glGetUniformLocation(program, name);
    snprintf(name, sizeof(name), "%sInputSize", base);
    sampler->input_size = glGetUniformLocation(program, name);
    snprintf(name, sizeof(name), "%sTexCoord", base);
    sampler->texcoord = glGetAttribLocation(program, name);
}

static void find_uniforms(struct raven_shader_chain *chain, unsigned index) {
    struct pass *pass = &chain->pass[index];
    GLuint program = pass->program;
    pass->vertex = glGetAttribLocation(program, "VertexCoord");
    pass->texcoord = glGetAttribLocation(program, "TexCoord");
    pass->color = glGetAttribLocation(program, "COLOR");
    pass->mvp = glGetUniformLocation(program, "MVPMatrix");
    pass->frame_count = glGetUniformLocation(program, "FrameCount");
    pass->frame_direction = glGetUniformLocation(program, "FrameDirection");
    pass->frame_count_int = is_int(program, "FrameCount");
    pass->frame_direction_int = is_int(program, "FrameDirection");
    pass->output_size = glGetUniformLocation(program, "OutputSize");
    pass->texture_size = glGetUniformLocation(program, "TextureSize");
    pass->input_size = glGetUniformLocation(program, "InputSize");
    pass->texture = glGetUniformLocation(program, "Texture");
    find_sampler(program, &pass->orig, "Orig");
    char base[RAVEN_SHADER_NAME + 16];
    for (unsigned k = 0; k < HISTORY; ++k) {
        if (k == 0) snprintf(base, sizeof(base), "Prev");
        else snprintf(base, sizeof(base), "Prev%u", k);
        find_sampler(program, &pass->prev[k], base);
        if (pass->prev[k].texture >= 0 && k + 1 > chain->history_used) chain->history_used = k + 1;
    }
    for (unsigned k = 0; k < index; ++k) {
        snprintf(base, sizeof(base), "Pass%u", k + 1);
        find_sampler(program, &pass->output[k], base);
        const char *alias = chain->preset.pass[k].alias;
        if (*alias) find_sampler(program, &pass->alias[k], alias);
        else pass->alias[k].texture = pass->alias[k].texture_size = pass->alias[k].input_size
                = pass->alias[k].texcoord = -1;
    }
    for (unsigned k = 1; k <= index + 1; ++k) {
        snprintf(base, sizeof(base), "PassPrev%u", k);
        find_sampler(program, &pass->back[k], base);
    }
    for (unsigned k = 0; k < chain->preset.luts; ++k)
        pass->lut[k] = glGetUniformLocation(program, chain->preset.lut[k].name);
    for (unsigned k = 0; k < chain->parameters; ++k)
        pass->parameter[k] = glGetUniformLocation(program, chain->parameter[k].name);
}

static GLint wrap_value(enum raven_shader_wrap wrap, int npot) {
    if (!npot) return GL_CLAMP_TO_EDGE;
    if (wrap == RAVEN_WRAP_REPEAT) return GL_REPEAT;
    if (wrap == RAVEN_WRAP_MIRRORED) return GL_MIRRORED_REPEAT;
    return GL_CLAMP_TO_EDGE;
}

static void sample(GLuint texture, int linear, enum raven_shader_wrap wrap, int npot) {
    glBindTexture(GL_TEXTURE_2D, texture);
    GLint filter = linear == 1 ? GL_LINEAR : GL_NEAREST;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap_value(wrap, npot));
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap_value(wrap, npot));
}

static int upload_luts(struct raven_shader_chain *chain, const struct raven_shader_lut_image *luts,
                       char *error, size_t error_size) {
    for (unsigned k = 0; k < chain->preset.luts; ++k) {
        const struct raven_shader_lut_image *image = &luts[k];
        if (!image->rgba || !image->width || !image->height || image->width > MAX_TEXTURE
                || image->height > MAX_TEXTURE) {
            snprintf(error, error_size, "Texture %s illisible", chain->preset.lut[k].name);
            return 0;
        }
        glGenTextures(1, &chain->lut[k]);
        glActiveTexture(GL_TEXTURE0);
        sample(chain->lut[k], chain->preset.lut[k].linear, chain->preset.lut[k].wrap, chain->npot_wrap);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei)image->width, (GLsizei)image->height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, image->rgba);
        chain->lut_width[k] = image->width;
        chain->lut_height[k] = image->height;
        if (glGetError() != GL_NO_ERROR) {
            snprintf(error, error_size, "Texture %s trop grande pour cet appareil", chain->preset.lut[k].name);
            return 0;
        }
    }
    return 1;
}

static int make_copy_program(struct raven_shader_chain *chain) {
    static const char *vertex =
        "attribute vec2 VertexCoord;\nattribute vec2 TexCoord;\nvarying vec2 coord;\n"
        "void main() { gl_Position = vec4(VertexCoord * 2.0 - 1.0, 0.0, 1.0); coord = TexCoord; }\n";
    static const char *fragment =
        "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
        "uniform sampler2D Texture;\nvarying vec2 coord;\n"
        "void main() { gl_FragColor = vec4(texture2D(Texture, coord).rgb, 1.0); }\n";
    char log[256];
    const char *what;
    chain->copy_program = link(vertex, fragment, log, sizeof(log), &what);
    if (!chain->copy_program) return 0;
    chain->copy_vertex = glGetAttribLocation(chain->copy_program, "VertexCoord");
    chain->copy_texcoord = glGetAttribLocation(chain->copy_program, "TexCoord");
    chain->copy_texture = glGetUniformLocation(chain->copy_program, "Texture");
    return 1;
}

struct raven_shader_chain *raven_shader_chain_create(const struct raven_shader_preset *preset,
        char *const *sources, const struct raven_shader_lut_image *luts,
        const struct raven_shader_parameter *parameters, unsigned parameter_count,
        char *error, size_t error_size) {
    if (error && error_size) error[0] = '\0';
    if (!preset || !sources || !preset->passes || preset->passes > RAVEN_SHADER_MAX_PASSES) return NULL;
    struct raven_shader_chain *chain = calloc(1, sizeof(*chain));
    if (!chain) {
        snprintf(error, error_size, "Mémoire insuffisante");
        return NULL;
    }
    chain->preset = *preset;
    chain->parameters = parameter_count > RAVEN_SHADER_MAX_PARAMETERS ? RAVEN_SHADER_MAX_PARAMETERS : parameter_count;
    if (chain->parameters) memcpy(chain->parameter, parameters, chain->parameters * sizeof(*parameters));
    const char *extensions = (const char *)glGetString(GL_EXTENSIONS);
    const char *version = (const char *)glGetString(GL_VERSION);
    chain->npot_wrap = (extensions && strstr(extensions, "GL_OES_texture_npot"))
            || (version && strstr(version, "OpenGL ES 3"));
    glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &chain->max_units);
    if (chain->max_units < 8) chain->max_units = 8;
    const struct raven_shader_pass *last = &preset->pass[preset->passes - 1];
    chain->last_to_framebuffer = last->scale_x != RAVEN_SCALE_DEFAULT || last->scale_y != RAVEN_SCALE_DEFAULT;
    char log[512];
    for (unsigned i = 0; i < preset->passes; ++i) {
        char *vertex = raven_shader_stage(sources[i], 0), *fragment = raven_shader_stage(sources[i], 1);
        const char *what = "mémoire insuffisante";
        log[0] = '\0';
        chain->pass[i].program = vertex && fragment ? link(vertex, fragment, log, sizeof(log), &what) : 0;
        free(vertex);
        free(fragment);
        if (!chain->pass[i].program) {
            const char *file = strrchr(preset->pass[i].path, '/');
            fail(error, error_size, i, file ? file + 1 : preset->pass[i].path, what, log);
            raven_shader_chain_destroy(chain, 1);
            return NULL;
        }
        find_uniforms(chain, i);
        if (chain->pass[i].vertex < 0) {
            const char *file = strrchr(preset->pass[i].path, '/');
            fail(error, error_size, i, file ? file + 1 : preset->pass[i].path,
                 "pas d'attribut VertexCoord : ce n'est pas un shader GLSL de RetroArch", NULL);
            raven_shader_chain_destroy(chain, 1);
            return NULL;
        }
    }
    if (!upload_luts(chain, luts, error, error_size)
            || (chain->last_to_framebuffer && !make_copy_program(chain))) {
        if (error && !*error) snprintf(error, error_size, "Copie finale impossible");
        raven_shader_chain_destroy(chain, 1);
        return NULL;
    }
    return chain;
}

void raven_shader_chain_set_parameter(struct raven_shader_chain *chain, const char *name, float value) {
    if (!chain || !name) return;
    for (unsigned k = 0; k < chain->parameters; ++k)
        if (!strcmp(chain->parameter[k].name, name)) chain->parameter[k].initial = value;
}

static unsigned scaled(enum raven_shader_scale type, float factor, unsigned source, unsigned viewport) {
    double size = type == RAVEN_SCALE_ABSOLUTE ? factor
            : type == RAVEN_SCALE_VIEWPORT ? (double)factor * viewport : (double)factor * source;
    if (size < 1.0) size = 1.0;
    if (size > MAX_TEXTURE) size = MAX_TEXTURE;
    return (unsigned)(size + 0.5);
}

/* The pass's framebuffer at this size, made again when it changes. */
static int target(struct pass *pass, unsigned width, unsigned height) {
    if (pass->framebuffer && pass->width == width && pass->height == height) return 1;
    if (!pass->target) glGenTextures(1, &pass->target);
    if (!pass->framebuffer) glGenFramebuffers(1, &pass->framebuffer);
    if (!pass->target || !pass->framebuffer) return 0;
    glActiveTexture(GL_TEXTURE0);
    sample(pass->target, 0, RAVEN_WRAP_EDGE, 0);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei)width, (GLsizei)height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glBindFramebuffer(GL_FRAMEBUFFER, pass->framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, pass->target, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        pass->width = pass->height = 0;
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return 0;
    }
    pass->width = width;
    pass->height = height;
    return 1;
}

/* What a pass enabled, to leave the GL state as it found it. */
struct bound {
    unsigned unit;
    GLint max_units;
    GLint attribute[48];
    unsigned attributes;
};

static void enable_coordinates(struct bound *bound, GLint location, const GLfloat *coordinates) {
    if (location < 0) return;
    glEnableVertexAttribArray((GLuint)location);
    glVertexAttribPointer((GLuint)location, 2, GL_FLOAT, GL_FALSE, 0, coordinates);
    if (bound->attributes < sizeof(bound->attribute) / sizeof(bound->attribute[0]))
        bound->attribute[bound->attributes++] = location;
}

/* One more texture for the pass, on the next free unit. */
static void bind_extra(struct bound *bound, const struct sampler *sampler, GLuint texture,
                       unsigned width, unsigned height) {
    if (sampler->texture_size >= 0) glUniform2f(sampler->texture_size, (GLfloat)width, (GLfloat)height);
    if (sampler->input_size >= 0) glUniform2f(sampler->input_size, (GLfloat)width, (GLfloat)height);
    enable_coordinates(bound, sampler->texcoord, quad);
    unsigned *unit = &bound->unit;
    if (sampler->texture < 0 || (GLint)*unit >= bound->max_units) return;
    glActiveTexture(GL_TEXTURE0 + *unit);
    glBindTexture(GL_TEXTURE_2D, texture);
    glUniform1i(sampler->texture, (GLint)*unit);
    ++*unit;
}

static void set_number(GLint location, int integer, unsigned value) {
    if (location < 0) return;
    if (integer) glUniform1i(location, (GLint)value);
    else glUniform1f(location, (GLfloat)value);
}

/* The history slot of the k-th frame before, or the current one. */
static GLuint previous(const struct raven_shader_chain *chain, unsigned k, GLuint current) {
    if (!chain->history_filled) return current;
    if (k >= chain->history_filled) k = chain->history_filled - 1;
    return chain->history[(chain->history_head + k) % HISTORY];
}

static void draw_pass(struct raven_shader_chain *chain, unsigned p, GLuint input, unsigned in_w, unsigned in_h,
                      unsigned out_w, unsigned out_h, int to_screen, const GLuint *outputs,
                      const unsigned *output_w, const unsigned *output_h, GLuint original,
                      unsigned orig_w, unsigned orig_h) {
    struct pass *pass = &chain->pass[p];
    const struct raven_shader_pass *spec = &chain->preset.pass[p];
    glUseProgram(pass->program);
    glActiveTexture(GL_TEXTURE0);
    sample(input, spec->filter, spec->wrap, chain->npot_wrap);
    if (spec->mipmap_input && chain->npot_wrap) {
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                        spec->filter == 1 ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_NEAREST);
    }
    if (pass->texture >= 0) glUniform1i(pass->texture, 0);
    struct bound bound = {1, chain->max_units, {0}, 0};
    if (pass->mvp >= 0) glUniformMatrix4fv(pass->mvp, 1, GL_FALSE, ortho);
    if (pass->texture_size >= 0) glUniform2f(pass->texture_size, (GLfloat)in_w, (GLfloat)in_h);
    if (pass->input_size >= 0) glUniform2f(pass->input_size, (GLfloat)in_w, (GLfloat)in_h);
    if (pass->output_size >= 0) glUniform2f(pass->output_size, (GLfloat)out_w, (GLfloat)out_h);
    unsigned frame = spec->frame_count_mod ? chain->frame_count % spec->frame_count_mod : chain->frame_count;
    set_number(pass->frame_count, pass->frame_count_int, frame);
    set_number(pass->frame_direction, pass->frame_direction_int, 1);
    bind_extra(&bound, &pass->orig, original, orig_w, orig_h);
    bind_extra(&bound, &pass->back[p + 1], original, orig_w, orig_h);
    for (unsigned k = 0; k < p; ++k) {
        bind_extra(&bound, &pass->output[k], outputs[k], output_w[k], output_h[k]);
        bind_extra(&bound, &pass->back[p - k], outputs[k], output_w[k], output_h[k]);
        bind_extra(&bound, &pass->alias[k], outputs[k], output_w[k], output_h[k]);
    }
    for (unsigned k = 0; k < HISTORY; ++k)
        bind_extra(&bound, &pass->prev[k], previous(chain, k, original), orig_w, orig_h);
    for (unsigned k = 0; k < chain->preset.luts; ++k) {
        struct sampler lut = {pass->lut[k], -1, -1, -1};
        bind_extra(&bound, &lut, chain->lut[k], chain->lut_width[k], chain->lut_height[k]);
    }
    for (unsigned k = 0; k < chain->parameters; ++k)
        if (pass->parameter[k] >= 0) glUniform1f(pass->parameter[k], chain->parameter[k].initial);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    enable_coordinates(&bound, pass->vertex, to_screen ? flipped : quad);
    enable_coordinates(&bound, pass->texcoord, quad);
    if (pass->color >= 0) glVertexAttrib4f((GLuint)pass->color, 1.f, 1.f, 1.f, 1.f);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    for (unsigned k = 0; k < bound.attributes; ++k) glDisableVertexAttribArray((GLuint)bound.attribute[k]);
    for (unsigned k = bound.unit; k-- > 1; ) {
        glActiveTexture(GL_TEXTURE0 + k);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    glActiveTexture(GL_TEXTURE0);
}

static void copy_to_screen(struct raven_shader_chain *chain, GLuint texture, int vx, int vy, int vw, int vh) {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(vx, vy, vw, vh);
    glUseProgram(chain->copy_program);
    glActiveTexture(GL_TEXTURE0);
    sample(texture, chain->preset.final_filter, RAVEN_WRAP_EDGE, 0);
    glUniform1i(chain->copy_texture, 0);
    glEnableVertexAttribArray((GLuint)chain->copy_vertex);
    glVertexAttribPointer((GLuint)chain->copy_vertex, 2, GL_FLOAT, GL_FALSE, 0, flipped);
    glEnableVertexAttribArray((GLuint)chain->copy_texcoord);
    glVertexAttribPointer((GLuint)chain->copy_texcoord, 2, GL_FLOAT, GL_FALSE, 0, quad);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray((GLuint)chain->copy_vertex);
    glDisableVertexAttribArray((GLuint)chain->copy_texcoord);
}

/* After a new frame is drawn, it becomes PrevTexture of the next one. */
static void remember(struct raven_shader_chain *chain, const uint8_t *rgba, unsigned width, unsigned height) {
    if (!chain->history_used || !rgba) return;
    if (chain->history_width != width || chain->history_height != height) {
        for (unsigned k = 0; k < HISTORY; ++k) {
            if (!chain->history[k]) glGenTextures(1, &chain->history[k]);
            glBindTexture(GL_TEXTURE_2D, chain->history[k]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei)width, (GLsizei)height, 0, GL_RGBA,
                         GL_UNSIGNED_BYTE, NULL);
            sample(chain->history[k], 0, RAVEN_WRAP_EDGE, 0);
        }
        chain->history_width = width;
        chain->history_height = height;
        chain->history_filled = 0;
    }
    chain->history_head = (chain->history_head + HISTORY - 1) % HISTORY;
    glBindTexture(GL_TEXTURE_2D, chain->history[chain->history_head]);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, (GLsizei)width, (GLsizei)height, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    if (chain->history_filled < HISTORY) chain->history_filled++;
}

int raven_shader_chain_draw(struct raven_shader_chain *chain, GLuint input, const uint8_t *rgba, int fresh,
                            unsigned width, unsigned height, int vx, int vy, int vw, int vh) {
    if (!chain || !width || !height || vw <= 0 || vh <= 0) return 0;
    if (fresh) chain->frame_count++;
    GLuint outputs[RAVEN_SHADER_MAX_PASSES];
    unsigned output_w[RAVEN_SHADER_MAX_PASSES], output_h[RAVEN_SHADER_MAX_PASSES];
    GLuint source = input;
    unsigned in_w = width, in_h = height;
    const unsigned passes = chain->preset.passes;
    for (unsigned p = 0; p < passes; ++p) {
        const struct raven_shader_pass *spec = &chain->preset.pass[p];
        int last = p + 1 == passes, to_screen = last && !chain->last_to_framebuffer;
        unsigned out_w = (unsigned)vw, out_h = (unsigned)vh;
        if (!to_screen) {
            enum raven_shader_scale sx = spec->scale_x, sy = spec->scale_y;
            if (sx == RAVEN_SCALE_DEFAULT) sx = last ? RAVEN_SCALE_VIEWPORT : RAVEN_SCALE_SOURCE;
            if (sy == RAVEN_SCALE_DEFAULT) sy = last ? RAVEN_SCALE_VIEWPORT : RAVEN_SCALE_SOURCE;
            out_w = scaled(sx, spec->factor_x, in_w, (unsigned)vw);
            out_h = scaled(sy, spec->factor_y, in_h, (unsigned)vh);
            if (!target(&chain->pass[p], out_w, out_h)) return 0;
            glBindFramebuffer(GL_FRAMEBUFFER, chain->pass[p].framebuffer);
            glViewport(0, 0, (GLsizei)out_w, (GLsizei)out_h);
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(vx, vy, vw, vh);
        }
        draw_pass(chain, p, source, in_w, in_h, out_w, out_h, to_screen, outputs, output_w, output_h,
                  input, width, height);
        outputs[p] = chain->pass[p].target;
        output_w[p] = out_w;
        output_h[p] = out_h;
        source = chain->pass[p].target;
        in_w = out_w;
        in_h = out_h;
    }
    if (chain->last_to_framebuffer) copy_to_screen(chain, source, vx, vy, vw, vh);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (fresh) remember(chain, rgba, width, height);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, input);
    return 1;
}

void raven_shader_chain_reset(struct raven_shader_chain *chain) {
    if (chain) chain->history_filled = 0;
}

void raven_shader_chain_destroy(struct raven_shader_chain *chain, int delete_gl) {
    if (!chain) return;
    if (delete_gl) {
        for (unsigned i = 0; i < RAVEN_SHADER_MAX_PASSES; ++i) {
            struct pass *pass = &chain->pass[i];
            if (pass->program) glDeleteProgram(pass->program);
            if (pass->framebuffer) glDeleteFramebuffers(1, &pass->framebuffer);
            if (pass->target) glDeleteTextures(1, &pass->target);
        }
        for (unsigned k = 0; k < RAVEN_SHADER_MAX_LUTS; ++k)
            if (chain->lut[k]) glDeleteTextures(1, &chain->lut[k]);
        for (unsigned k = 0; k < HISTORY; ++k)
            if (chain->history[k]) glDeleteTextures(1, &chain->history[k]);
        if (chain->copy_program) glDeleteProgram(chain->copy_program);
    }
    free(chain);
}
