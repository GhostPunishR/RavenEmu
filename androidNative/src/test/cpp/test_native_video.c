#include "native_video.h"
#include <GLES2/gl2.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)
#define UNUSED(x) (void)(x)

static int image_calls, sub_calls, draw_calls, clear_calls;
static int delete_shader_calls, delete_program_calls, delete_texture_calls;
static int compile_ok = 1, link_ok = 1, locations_ok = 1;
static int fail_image, fail_sub, texture_limit = 2048;
static GLenum error_code;
static GLuint next_name = 1;
static GLint viewport[4], unpack_alignment, mag_filter, min_filter, wrap_s, wrap_t;
static GLfloat uv[8];
static uint8_t rgba[240 * 160 * 4];

GLuint glCreateShader(GLenum type) { UNUSED(type); return next_name++; }
void glShaderSource(GLuint shader, GLsizei count, const GLchar *const *source, const GLint *length) {
    UNUSED(shader); UNUSED(count); UNUSED(source); UNUSED(length);
}
void glCompileShader(GLuint shader) { UNUSED(shader); }
void glGetShaderiv(GLuint shader, GLenum pname, GLint *params) { UNUSED(shader); UNUSED(pname); *params = compile_ok; }
GLuint glCreateProgram(void) { return next_name++; }
void glAttachShader(GLuint program, GLuint shader) { UNUSED(program); UNUSED(shader); }
void glLinkProgram(GLuint program) { UNUSED(program); }
void glGetProgramiv(GLuint program, GLenum pname, GLint *params) { UNUSED(program); UNUSED(pname); *params = link_ok; }
GLint glGetAttribLocation(GLuint program, const GLchar *name) {
    UNUSED(program); return locations_ok ? (!strcmp(name, "VertexCoord") ? 0 : 1) : -1;
}
GLint glGetUniformLocation(GLuint program, const GLchar *name) { UNUSED(program); UNUSED(name); return locations_ok ? 2 : -1; }
void glGetIntegerv(GLenum pname, GLint *data) { UNUSED(pname); *data = texture_limit; }
void glGenTextures(GLsizei n, GLuint *textures) { for (int i = 0; i < n; i++) textures[i] = next_name++; }
void glDeleteShader(GLuint shader) { UNUSED(shader); delete_shader_calls++; }
void glDeleteProgram(GLuint program) { UNUSED(program); delete_program_calls++; }
void glDeleteTextures(GLsizei n, const GLuint *textures) { UNUSED(textures); delete_texture_calls += n; }
void glTexParameteri(GLenum target, GLenum pname, GLint param) {
    UNUSED(target);
    if (pname == GL_TEXTURE_MAG_FILTER) mag_filter = param;
    else if (pname == GL_TEXTURE_MIN_FILTER) min_filter = param;
    else if (pname == GL_TEXTURE_WRAP_S) wrap_s = param;
    else if (pname == GL_TEXTURE_WRAP_T) wrap_t = param;
}
void glPixelStorei(GLenum pname, GLint param) { UNUSED(pname); unpack_alignment = param; }
void glTexImage2D(GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height,
                  GLint border, GLenum format, GLenum type, const void *pixels) {
    UNUSED(target); UNUSED(level); UNUSED(internalformat); UNUSED(width); UNUSED(height);
    UNUSED(border); UNUSED(format); UNUSED(type); UNUSED(pixels);
    image_calls++; if (fail_image) { fail_image = 0; error_code = GL_OUT_OF_MEMORY; }
}
void glTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width,
                     GLsizei height, GLenum format, GLenum type, const void *pixels) {
    UNUSED(target); UNUSED(level); UNUSED(xoffset); UNUSED(yoffset); UNUSED(width); UNUSED(height);
    UNUSED(format); UNUSED(type); UNUSED(pixels);
    sub_calls++; if (fail_sub) { fail_sub = 0; error_code = GL_OUT_OF_MEMORY; }
}
void glActiveTexture(GLenum texture) { UNUSED(texture); }
void glBindTexture(GLenum target, GLuint texture) { UNUSED(target); UNUSED(texture); }
GLenum glGetError(void) { GLenum error = error_code; error_code = GL_NO_ERROR; return error; }
void glClearColor(GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha) { UNUSED(red); UNUSED(green); UNUSED(blue); UNUSED(alpha); }
void glClear(GLbitfield mask) { UNUSED(mask); clear_calls++; }
void glViewport(GLint x, GLint y, GLsizei width, GLsizei height) { viewport[0]=x; viewport[1]=y; viewport[2]=width; viewport[3]=height; }
void glUseProgram(GLuint program) { UNUSED(program); }
void glUniform1i(GLint location, GLint value) { UNUSED(location); UNUSED(value); }
void glUniformMatrix4fv(GLint location, GLsizei count, GLboolean transpose, const GLfloat *value) {
    UNUSED(location); UNUSED(count); UNUSED(transpose); UNUSED(value);
}
void glBindBuffer(GLenum target, GLuint buffer) { UNUSED(target); UNUSED(buffer); }
void glEnableVertexAttribArray(GLuint index) { UNUSED(index); }
void glVertexAttribPointer(GLuint index, GLint size, GLenum type, GLboolean normalized,
                           GLsizei stride, const void *pointer) {
    UNUSED(size); UNUSED(type); UNUSED(normalized); UNUSED(stride);
    if (index == 1) memcpy(uv, pointer, sizeof(uv));
}
void glDrawArrays(GLenum mode, GLint first, GLsizei count) { UNUSED(mode); UNUSED(first); UNUSED(count); draw_calls++; }
void glDisableVertexAttribArray(GLuint index) { UNUSED(index); }

static void counters(void) {
    image_calls = sub_calls = draw_calls = clear_calls = 0;
    delete_shader_calls = delete_program_calls = delete_texture_calls = 0;
    error_code = GL_NO_ERROR;
    memset(viewport, 0, sizeof(viewport));
}

static void fresh(void) {
    compile_ok = link_ok = locations_ok = 1;
    texture_limit = 2048;
    fail_image = fail_sub = 0;
    counters();
    raven_video_init_gl();
    counters();
}

static int allocation_updates_and_duplicate(void) {
    fresh();
    raven_video_draw(rgba, 240, 160, 1, 600, 600);
    CHECK(image_calls == 1 && sub_calls == 0 && draw_calls == 1);
    CHECK(unpack_alignment == 4 && mag_filter == GL_NEAREST && min_filter == GL_NEAREST);
    CHECK(wrap_s == GL_CLAMP_TO_EDGE && wrap_t == GL_CLAMP_TO_EDGE);
    CHECK(viewport[0] == 0 && viewport[1] == 100 && viewport[2] == 600 && viewport[3] == 400);
    CHECK(uv[0] == 0.f && uv[1] == 1.f && uv[4] == 0.f && uv[5] == 0.f);
    raven_video_draw(rgba, 240, 160, 1, 600, 600);
    CHECK(image_calls == 1 && sub_calls == 1 && draw_calls == 2);
    raven_video_draw(rgba, 240, 160, 0, 600, 600);
    CHECK(image_calls == 1 && sub_calls == 1 && draw_calls == 3 && clear_calls == 3);
    return 0;
}

static int resize_and_context_lifecycle(void) {
    fresh();
    raven_video_draw(rgba, 240, 160, 1, 600, 600);
    raven_video_draw(rgba, 160, 144, 0, 320, 480);
    CHECK(image_calls == 2 && sub_calls == 0);
    CHECK(viewport[0] == 0 && viewport[1] == 96 && viewport[2] == 320 && viewport[3] == 288);
    raven_video_reset();
    CHECK(delete_texture_calls == 0);
    raven_video_draw(rgba, 160, 144, 0, 320, 480);
    CHECK(image_calls == 3);
    raven_video_init_gl();
    CHECK(delete_texture_calls == 0); /* Old names belong to the old context. */
    raven_video_draw(rgba, 160, 144, 0, 320, 480);
    CHECK(image_calls == 4 && draw_calls == 4);
    return 0;
}

static int invalid_frames_and_limits_clear_without_old_image(void) {
    fresh();
    raven_video_draw(rgba, 240, 160, 1, 600, 600);
    counters();
    raven_video_draw(NULL, 240, 160, 1, 600, 600);
    raven_video_draw(rgba, 0, 160, 1, 600, 600);
    raven_video_draw(rgba, UINT_MAX, 160, 1, 600, 600);
    raven_video_draw(rgba, 240, 160, 1, 0, 600);
    CHECK(clear_calls == 4 && image_calls == 0 && sub_calls == 0 && draw_calls == 0);
    texture_limit = 128;
    raven_video_init_gl();
    raven_video_draw(rgba, 240, 160, 1, 600, 600);
    CHECK(image_calls == 0 && draw_calls == 0);
    return 0;
}

static int shader_failures_and_upload_failures_retry(void) {
    fresh();
    compile_ok = 0;
    raven_video_init_gl();
    raven_video_draw(rgba, 240, 160, 1, 600, 600);
    CHECK(image_calls == 0 && draw_calls == 0 && delete_shader_calls >= 2);
    compile_ok = 1;
    link_ok = 0;
    raven_video_draw(rgba, 240, 160, 0, 600, 600);
    CHECK(image_calls == 0 && delete_program_calls >= 1);
    link_ok = 1;
    locations_ok = 0;
    raven_video_draw(rgba, 240, 160, 0, 600, 600);
    CHECK(image_calls == 0);
    locations_ok = 1;
    fail_image = 1;
    raven_video_draw(rgba, 240, 160, 0, 600, 600);
    CHECK(image_calls == 1 && draw_calls == 0);
    raven_video_draw(rgba, 240, 160, 0, 600, 600);
    CHECK(image_calls == 2 && draw_calls == 1);
    fail_sub = 1;
    raven_video_draw(rgba, 240, 160, 1, 600, 600);
    CHECK(sub_calls == 1 && draw_calls == 1);
    raven_video_draw(rgba, 240, 160, 0, 600, 600);
    CHECK(sub_calls == 2 && draw_calls == 2);
    return 0;
}

static int viewport_extremes_do_not_overflow(void) {
    fresh();
    raven_video_draw(rgba, 160, 144, 1, INT_MAX, 1);
    CHECK(viewport[0] == (INT_MAX - 1) / 2 && viewport[1] == 0);
    CHECK(viewport[2] == 1 && viewport[3] == 1);
    raven_video_draw(rgba, 160, 144, 0, 1, INT_MAX);
    CHECK(viewport[0] == 0 && viewport[1] == (INT_MAX - 1) / 2);
    CHECK(viewport[2] == 1 && viewport[3] == 1);
    return 0;
}

int main(void) {
    if (allocation_updates_and_duplicate() || resize_and_context_lifecycle() ||
            invalid_frames_and_limits_clear_without_old_image() ||
            shader_failures_and_upload_failures_retry() || viewport_extremes_do_not_overflow()) return 1;
    puts("RetroArch GLES subset: upload reuse, context/reset, bounds, errors and aspect passed");
    return 0;
}
