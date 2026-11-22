/* One pass of a RetroArch GLSL shader (.glsl) for RavenEmu (AGPLv3). Written
 * for RavenEmu from the documented format; see shader_source.h. */
#include "shader_source.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *skip_spaces(const char *text) {
    while (*text == ' ' || *text == '\t') ++text;
    return text;
}

/* One "#pragma parameter" line, from just after the word "parameter". */
static int parse_parameter(const char *text, struct raven_shader_parameter *out) {
    memset(out, 0, sizeof(*out));
    text = skip_spaces(text);
    size_t n = 0;
    while ((isalnum((unsigned char)text[n]) || text[n] == '_') && n + 1 < sizeof(out->name)) ++n;
    if (!n) return 0;
    memcpy(out->name, text, n);
    text = skip_spaces(text + n);
    if (*text != '"') return 0;
    const char *close = strchr(text + 1, '"');
    if (!close) return 0;
    n = (size_t)(close - text - 1);
    if (n >= sizeof(out->description)) n = sizeof(out->description) - 1;
    memcpy(out->description, text + 1, n);
    float numbers[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    int read = sscanf(close + 1, "%f %f %f %f", &numbers[0], &numbers[1], &numbers[2], &numbers[3]);
    if (read < 3) return 0;
    out->initial = numbers[0];
    out->minimum = numbers[1];
    out->maximum = numbers[2];
    out->step = read == 4 ? numbers[3] : 0.0f;
    if (out->maximum < out->minimum) return 0;
    return 1;
}

unsigned raven_shader_parameters(const char *source, struct raven_shader_parameter *out,
                                 unsigned count, unsigned max) {
    for (const char *line = source; line && *line; ) {
        const char *start = skip_spaces(line);
        if (!strncmp(start, "#pragma", 7)) {
            const char *word = skip_spaces(start + 7);
            if (!strncmp(word, "parameter", 9) && (word[9] == ' ' || word[9] == '\t') && count < max) {
                struct raven_shader_parameter parsed;
                if (parse_parameter(word + 9, &parsed)) {
                    int known = 0;
                    for (unsigned i = 0; i < count; ++i) known |= !strcmp(out[i].name, parsed.name);
                    if (!known) out[count++] = parsed;
                }
            }
        }
        line = strchr(line, '\n');
        if (line) ++line;
    }
    return count;
}

/* Lines of `source` starting with `directive`, copied to `out` (when not
 * NULL) and blanked in `body`; returns their length. */
static size_t move_directives(const char *source, const char *directive, char *out, char *body) {
    size_t total = 0, length = strlen(directive);
    for (const char *line = source; *line; ) {
        const char *end = strchr(line, '\n');
        size_t n = end ? (size_t)(end - line + 1) : strlen(line);
        if (!strncmp(skip_spaces(line), directive, length)) {
            if (out) {
                memcpy(out + total, line, n);
                if (!end) out[total + n++] = '\n';
            }
            if (body) memset(body + (line - source), ' ', end ? n - 1 : n);
            total += end ? n : n + 1;
        }
        line += n;
    }
    return total;
}

char *raven_shader_stage(const char *source, int fragment) {
    static const char *precision =
        "#ifdef GL_ES\n#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n"
        "#else\nprecision mediump float;\n#endif\n#endif\n";
    const char *define = fragment ? "#define FRAGMENT\n" : "#define VERTEX\n";
    const char *parameters = "#define PARAMETER_UNIFORM\n";
    /* A fragment without its own precision gets one; its #extension lines
     * then come first, since GLSL ES wants them before any statement. */
    int add_precision = fragment && !strstr(source, "precision");
    size_t length = strlen(source);
    char *body = malloc(length + 1);
    if (!body) return NULL;
    memcpy(body, source, length + 1);
    /* #version lines blanked: they would have to come first, and GLES2 knows
     * only GLSL ES 1.00. Line numbers stay those of the file. */
    move_directives(source, "#version", NULL, body);
    size_t extensions = add_precision ? move_directives(source, "#extension", NULL, NULL) : 0;
    size_t size = strlen(define) + strlen(parameters) + extensions + (add_precision ? strlen(precision) : 0)
            + length + 1;
    char *out = malloc(size);
    if (!out) {
        free(body);
        return NULL;
    }
    char *cursor = out;
    cursor += sprintf(cursor, "%s%s", define, parameters);
    if (add_precision) {
        cursor += move_directives(source, "#extension", cursor, body);
        cursor += sprintf(cursor, "%s", precision);
    }
    memcpy(cursor, body, length + 1);
    free(body);
    return out;
}
