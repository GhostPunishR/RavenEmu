/*  RetroArch - A frontend for libretro.
 *  Copyright (C) 2010-2014 - Hans-Kristian Arntzen
 *  Copyright (C) 2011-2017 - Daniel De Matteis
 *  Copyright (C) 2012-2015 - Michael Lelli
 *  Copyright (C) 2016-2019 - Brad Parker
 *
 *  RetroArch is free software: you can redistribute it and/or modify it under the terms
 *  of the GNU General Public License as published by the Free Software Found-
 *  ation, either version 3 of the License, or (at your option) any later version.
 *
 *  RetroArch is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE.  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with RetroArch.
 *  If not, see <http://www.gnu.org/licenses/>.
 */

/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 GhostPunishR (RavenEmu adaptations).
 * Modified for RavenEmu by GhostPunishR, 2026-10-29.
 * Source: https://github.com/libretro/RetroArch/tree/7986022baf6822677ad2cec4bc215069e89bd8ce
 * - gfx/drivers/gl2.c: gl_load_texture_data, the GLES software-frame
 *   branch of gl2_renderchain_copy_frame, gl2_update_texture_internal.
 * - gfx/drivers_shader/shader_glsl.c: gl_glsl_compile_shader and
 *   gl_glsl_link_program (copyright Arntzen/De Matteis above).
 * - gfx/drivers/gl_shaders/modern_opaque.glsl.vert.h and .frag.h:
 *   stock opaque shaders included by shader_glsl.c.
 *
 * Adaptation: one tightly packed RGBA texture, fixed nearest sampling,
 * GLES2 stock shaders and an identity MVP. No RetroArch runloop, context
 * driver, settings, menu, shader presets, texture ring or hardware core API.
 * Texture geometry/retry bookkeeping and viewport fitting are new adapter
 * code distributed with this module under GPL-3.0-or-later. RavenEmu's other
 * modules keep their existing licenses; this is not the RetroArch frontend.
 * RavenEmu additions, 2026-10-29: integer-size viewport and shader-only
 * brightness/contrast controls; no new upstream source is imported.
 */

#include "native_video.h"
#include <GLES2/gl2.h>
#include <stddef.h>
#include <stdint.h>

#define MAX_EDGE 2048u

static GLuint program, texture;
static GLint vertex_coord, tex_coord, texture_uniform, mvp_uniform;
static GLint brightness_uniform, contrast_uniform;
static unsigned texture_width, texture_height;
static GLint max_texture_edge;
static int upload_pending = 1;
static int integer_scale, brightness, contrast = 100;

/* Stock opaque shaders from the pinned RetroArch sources above. The GLSL
 * wrapper is replaced with explicit GLES precision, without the unrelated
 * derivatives extension. RavenEmu extends the fragment with colour controls;
 * default values retain the stock opaque output. Color is unused. */
static const char *stock_vertex =
    "attribute vec2 TexCoord;\n"
    "attribute vec2 VertexCoord;\n"
    "attribute vec4 Color;\n"
    "uniform mat4 MVPMatrix;\n"
    "varying vec2 tex_coord;\n"
    "void main() {\n"
    "  gl_Position = MVPMatrix * vec4(VertexCoord, 0.0, 1.0);\n"
    "  tex_coord = TexCoord;\n"
    "}\n";
static const char *stock_fragment =
    "uniform sampler2D Texture;\n"
    "uniform float Brightness;\n"
    "uniform float Contrast;\n"
    "varying vec2 tex_coord;\n"
    "void main() {\n"
    "  vec3 color = texture2D(Texture, tex_coord).rgb;\n"
    "  if (Brightness != 0.0 || Contrast != 100.0) {\n"
    "    vec3 bytes = floor(color * 255.0 + 0.5);\n"
    "    vec3 delta = (bytes - 128.0) * (Contrast / 100.0);\n"
    "    delta = sign(delta) * floor(abs(delta) + 0.0001);\n"
    "    color = clamp(delta + 128.0 + Brightness * 2.0, 0.0, 255.0) / 255.0;\n"
    "  }\n"
    "  gl_FragColor = vec4(color, 1.0);\n"
    "}\n";

/* Adapted from gl_glsl_compile_shader: no aliases, shader-cache or version
 * negotiation because this adapter has a single fixed GLES2 stock shader. */
static int compile_stage(GLuint shader, const char *body) {
    GLint status;
    const char *source[] = {
        "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n"
        "#else\nprecision mediump float;\n#endif\n", body};
    glShaderSource(shader, 2, source, NULL);
    glCompileShader(shader);
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    return status == GL_TRUE;
}

/* Adapted from gl_glsl_link_program and gl_glsl_compile_program. The fixed
 * attributes/uniforms are validated, and every failed object is released. */
static int create_pipeline(void) {
    GLuint vertex = 0, fragment = 0, next_program = 0, next_texture = 0;
    GLint status;
    vertex = glCreateShader(GL_VERTEX_SHADER);
    if (!vertex || !compile_stage(vertex, stock_vertex)) goto error;
    fragment = glCreateShader(GL_FRAGMENT_SHADER);
    if (!fragment || !compile_stage(fragment, stock_fragment)) goto error;
    next_program = glCreateProgram();
    if (!next_program) goto error;
    glAttachShader(next_program, vertex);
    glAttachShader(next_program, fragment);
    glLinkProgram(next_program);
    glGetProgramiv(next_program, GL_LINK_STATUS, &status);
    if (status != GL_TRUE) goto error;
    vertex_coord = glGetAttribLocation(next_program, "VertexCoord");
    tex_coord = glGetAttribLocation(next_program, "TexCoord");
    texture_uniform = glGetUniformLocation(next_program, "Texture");
    mvp_uniform = glGetUniformLocation(next_program, "MVPMatrix");
    brightness_uniform = glGetUniformLocation(next_program, "Brightness");
    contrast_uniform = glGetUniformLocation(next_program, "Contrast");
    if (vertex_coord < 0 || tex_coord < 0 || texture_uniform < 0 || mvp_uniform < 0 ||
            brightness_uniform < 0 || contrast_uniform < 0) goto error;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &max_texture_edge);
    if (max_texture_edge <= 0) goto error;
    glGenTextures(1, &next_texture);
    if (!next_texture) goto error;
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    program = next_program;
    texture = next_texture;
    return 1;

error:
    if (vertex) glDeleteShader(vertex);
    if (fragment) glDeleteShader(fragment);
    if (next_program) glDeleteProgram(next_program);
    if (next_texture) glDeleteTextures(1, &next_texture);
    return 0;
}

void raven_video_reset(void) {
    texture_width = texture_height = 0;
    upload_pending = 1;
}

void raven_video_set_options(int next_integer_scale, int next_brightness, int next_contrast) {
    integer_scale = !!next_integer_scale;
    brightness = next_brightness < -50 ? -50 : next_brightness > 50 ? 50 : next_brightness;
    contrast = next_contrast < 50 ? 50 : next_contrast > 150 ? 150 : next_contrast;
}

void raven_video_init_gl(void) {
    /* Old names belong to the lost context. Do not delete them here. */
    program = texture = 0;
    max_texture_edge = 0;
    raven_video_reset();
    create_pipeline();
}

/* RGBA-only adaptation of gl_load_texture_data: the pinned GLES branch and
 * nearest filter settings, without RetroArch's packed formats/mipmap paths. */
static void allocate_texture(unsigned width, unsigned height, const uint8_t *rgba) {
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei)width, (GLsizei)height,
                0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
}

void raven_video_draw(const uint8_t *rgba, unsigned width, unsigned height,
                      int dirty, int surface_width, int surface_height) {
    if (dirty) upload_pending = 1;
    glClearColor(0.05f, 0.06f, 0.08f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!rgba || !width || !height || width > MAX_EDGE || height > MAX_EDGE ||
            surface_width <= 0 || surface_height <= 0) return;
    if (!program && !create_pipeline()) return;
    if (width > (unsigned)max_texture_edge || height > (unsigned)max_texture_edge) return;

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    if (width != texture_width || height != texture_height) {
        allocate_texture(width, height, rgba);
        if (glGetError() != GL_NO_ERROR) return;
        texture_width = width;
        texture_height = height;
        upload_pending = 0;
    } else if (upload_pending) {
        /* Adapted from gl2_update_texture_internal and the tightly packed
         * GLES branch of gl2_renderchain_copy_frame. Storage is retained. */
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, (GLsizei)width, (GLsizei)height,
                       GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        if (glGetError() != GL_NO_ERROR) return;
        upload_pending = 0;
    }

    int w = surface_width, h = surface_height;
    if ((int64_t)surface_width * height > (int64_t)surface_height * width)
        w = (int)((int64_t)h * width / height);
    else
        h = (int)((int64_t)w * height / width);
    if (integer_scale) {
        unsigned scale = (unsigned)surface_width / width;
        const unsigned vertical_scale = (unsigned)surface_height / height;
        if (vertical_scale < scale) scale = vertical_scale;
        if (scale) { w = (int)(width * scale); h = (int)(height * scale); }
    }
    if (w < 1) w = 1;
    if (h < 1) h = 1;
    glViewport((surface_width - w) / 2, (surface_height - h) / 2, w, h);
    const GLfloat vertex[] = {-1.f,-1.f, 1.f,-1.f, -1.f,1.f, 1.f,1.f};
    const GLfloat coords[] = {0.f,1.f, 1.f,1.f, 0.f,0.f, 1.f,0.f};
    const GLfloat identity[] = {1.f,0.f,0.f,0.f, 0.f,1.f,0.f,0.f,
                                0.f,0.f,1.f,0.f, 0.f,0.f,0.f,1.f};
    glUseProgram(program);
    glUniform1i(texture_uniform, 0);
    glUniform1f(brightness_uniform, (GLfloat)brightness);
    glUniform1f(contrast_uniform, (GLfloat)contrast);
    glUniformMatrix4fv(mvp_uniform, 1, GL_FALSE, identity);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glEnableVertexAttribArray((GLuint)vertex_coord);
    glEnableVertexAttribArray((GLuint)tex_coord);
    glVertexAttribPointer((GLuint)vertex_coord, 2, GL_FLOAT, GL_FALSE, 0, vertex);
    glVertexAttribPointer((GLuint)tex_coord, 2, GL_FLOAT, GL_FALSE, 0, coords);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray((GLuint)vertex_coord);
    glDisableVertexAttribArray((GLuint)tex_coord);
}
