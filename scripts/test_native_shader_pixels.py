#!/usr/bin/env python3
"""Draw through RetroArch GLSL presets written here, with software EGL.

The host build of the video driver (gles2_renderer.c and shaders/) reads the
presets from a temporary folder, compiles them with Mesa's GLES2 and draws:
the pixels read back check the passes, their sizes, the uniforms, the lookup
textures, the parameters, the frame history and the errors. Not phone
performance: run with the host raven_native_video_pixels library only.
"""

import argparse
import ctypes as c
import os
import tempfile
import unittest

VERTEX = """
#if defined(VERTEX)
#if __VERSION__ >= 130
#define COMPAT_VARYING out
#define COMPAT_ATTRIBUTE in
#else
#define COMPAT_VARYING varying
#define COMPAT_ATTRIBUTE attribute
#endif
COMPAT_ATTRIBUTE vec4 VertexCoord;
COMPAT_ATTRIBUTE vec4 COLOR;
COMPAT_ATTRIBUTE vec4 TexCoord;
COMPAT_VARYING vec4 COL0;
COMPAT_VARYING vec4 TEX0;
uniform mat4 MVPMatrix;
void main() {
    gl_Position = MVPMatrix * VertexCoord;
    COL0 = COLOR;
    TEX0.xy = TexCoord.xy;
}
"""


def shader(declarations, body, pragmas=""):
    return (pragmas + VERTEX + "#elif defined(FRAGMENT)\n#ifdef GL_ES\nprecision highp float;\n#endif\n"
            "varying vec4 TEX0;\nuniform sampler2D Texture;\n" + declarations
            + "\nvoid main() {\n" + body + "\n}\n#endif\n")


FILES = {
    "invert.glsl": shader("", "vec4 c = texture2D(Texture, TEX0.xy); gl_FragColor = vec4(1.0 - c.rgb, 1.0);"),
    "copy.glsl": shader("", "gl_FragColor = texture2D(Texture, TEX0.xy);"),
    "sizes.glsl": shader(
        "uniform vec2 InputSize;\nuniform vec2 OutputSize;\nuniform vec2 TextureSize;",
        "gl_FragColor = vec4(InputSize.x / 255.0, InputSize.y / 255.0, OutputSize.x / 255.0, 1.0);"),
    "alias.glsl": shader(
        "uniform sampler2D FirstTexture;\nuniform sampler2D OrigTexture;",
        "gl_FragColor = vec4(texture2D(FirstTexture, TEX0.xy).r, texture2D(OrigTexture, TEX0.xy).g, 0.0, 1.0);"),
    "level.glsl": shader(
        "#ifdef PARAMETER_UNIFORM\nuniform float LEVEL;\n#else\n#define LEVEL 0.5\n#endif",
        "gl_FragColor = vec4(LEVEL, LEVEL, LEVEL, 1.0);",
        '#pragma parameter LEVEL "Niveau" 0.5 0.0 1.0 0.05\n'),
    "lut.glsl": shader("uniform sampler2D MASK;", "gl_FragColor = texture2D(MASK, vec2(0.5, 0.5));"),
    "frames.glsl": shader("uniform int FrameCount;",
                          "gl_FragColor = vec4(mod(float(FrameCount), 2.0), 0.0, 0.0, 1.0);"),
    "previous.glsl": shader("uniform sampler2D PrevTexture;", "gl_FragColor = texture2D(PrevTexture, TEX0.xy);"),
    "broken.glsl": shader("", "gl_FragColor = vec4(1.0) +;"),
    "invert.glslp": "shaders = 1\nshader0 = invert.glsl\n",
    "sizes.glslp": ("shaders = 2\nshader0 = copy.glsl\nscale_type0 = source\nscale0 = 2.0\n"
                    "shader1 = sizes.glsl\n"),
    "alias.glslp": ("shaders = 2\nshader0 = invert.glsl\nalias0 = First\nshader1 = alias.glsl\n"),
    "level.glslp": "shaders = 1\nshader0 = level.glsl\nLEVEL = 0.25\n",
    "lut.glslp": "shaders = 1\nshader0 = lut.glsl\ntextures = MASK\nMASK = luts/mask.png\n",
    "frames.glslp": "shaders = 1\nshader0 = frames.glsl\n",
    "previous.glslp": "shaders = 1\nshader0 = previous.glsl\n",
    "broken.glslp": "shaders = 1\nshader0 = broken.glsl\n",
    "upright.glslp": "shaders = 2\nshader0 = copy.glsl\nshader1 = copy.glsl\n",
    "final.glslp": ("shaders = 1\nshader0 = copy.glsl\nscale_type0 = absolute\nscale_x0 = 1\nscale_y0 = 2\n"),
}


def function(library, name, result, *arguments):
    call = getattr(library, name)
    call.restype, call.argtypes = result, arguments
    return call


class ShaderPixelsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.environ.setdefault("EGL_PLATFORM", "surfaceless")
        os.environ.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
        os.environ.setdefault("MESA_GLES_VERSION_OVERRIDE", "2.0")
        os.environ.setdefault("MESA_SHADER_CACHE_DISABLE", "true")
        cls.folder = tempfile.TemporaryDirectory()
        for name, text in FILES.items():
            with open(os.path.join(cls.folder.name, name), "w", encoding="utf-8") as out:
                out.write(text)
        cls.egl = c.CDLL(OPTIONS.egl)
        cls.gl = c.CDLL(OPTIONS.gl, mode=c.RTLD_GLOBAL)
        cls.driver = c.CDLL(OPTIONS.driver)
        pointer, integer = c.c_void_p, c.c_int
        cls.display = function(cls.egl, "eglGetDisplay", pointer, pointer)(None)
        major, minor = integer(), integer()
        if not function(cls.egl, "eglInitialize", integer, pointer, c.POINTER(integer), c.POINTER(integer))(
                cls.display, c.byref(major), c.byref(minor)):
            raise RuntimeError("Software EGL initialization failed")
        if not function(cls.egl, "eglBindAPI", integer, c.c_uint)(0x30A0):
            raise RuntimeError("EGL cannot bind OpenGL ES")
        attrs = (integer * 13)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
        cls.config, count = pointer(), integer()
        if not function(cls.egl, "eglChooseConfig", integer, pointer, c.POINTER(integer), c.POINTER(pointer),
                        integer, c.POINTER(integer))(cls.display, attrs, c.byref(cls.config), 1, c.byref(count)) \
                or not count.value:
            raise RuntimeError("EGL has no RGBA8 GLES2 pbuffer configuration")
        surface_attrs = (integer * 5)(0x3057, 64, 0x3056, 64, 0x3038)
        cls.surface = function(cls.egl, "eglCreatePbufferSurface", pointer, pointer, pointer,
                               c.POINTER(integer))(cls.display, cls.config, surface_attrs)
        cls.create_context = function(cls.egl, "eglCreateContext", pointer, pointer, pointer, pointer,
                                      c.POINTER(integer))
        cls.make_current = function(cls.egl, "eglMakeCurrent", integer, pointer, pointer, pointer, pointer)
        cls.destroy_context = function(cls.egl, "eglDestroyContext", integer, pointer, pointer)
        cls.context = None
        cls.init = function(cls.driver, "raven_video_init_gl", None)
        cls.reset = function(cls.driver, "raven_video_reset", None)
        cls.options = function(cls.driver, "raven_video_set_options", None, integer, integer, integer)
        cls.draw = function(cls.driver, "raven_video_draw", None, pointer, c.c_uint, c.c_uint, integer, integer,
                            integer)
        cls.prepare = function(cls.driver, "raven_shaders_prepare", integer, c.c_char_p, c.c_char_p, c.c_char_p,
                               c.c_size_t)
        cls.apply_preset = function(cls.driver, "raven_shaders_apply", integer, c.c_char_p, c.c_size_t)
        cls.clear = function(cls.driver, "raven_shaders_clear", None)
        cls.set_lut = function(cls.driver, "raven_shaders_set_lut", integer, c.c_uint, c.c_uint, c.c_uint, pointer)
        cls.lut_count = function(cls.driver, "raven_shaders_lut_count", c.c_uint)
        cls.set_parameter = function(cls.driver, "raven_shaders_set_parameter", None, c.c_char_p, c.c_float)
        cls.read = function(cls.gl, "glReadPixels", None, integer, integer, integer, integer, c.c_uint, c.c_uint,
                            pointer)
        cls.gl_error = function(cls.gl, "glGetError", c.c_uint)
        cls.disable = function(cls.gl, "glDisable", None, c.c_uint)
        cls.new_context()

    @classmethod
    def new_context(cls):
        if cls.context:
            cls.make_current(cls.display, None, None, None)
            cls.destroy_context(cls.display, cls.context)
        attrs = (c.c_int * 3)(0x3098, 2, 0x3038)
        cls.context = cls.create_context(cls.display, cls.config, None, attrs)
        if not cls.context or not cls.make_current(cls.display, cls.surface, cls.surface, cls.context):
            raise RuntimeError("Cannot make GLES2 pbuffer current")
        cls.disable(0x0BD0)
        cls.init()

    @classmethod
    def tearDownClass(cls):
        cls.clear()
        cls.make_current(cls.display, None, None, None)
        cls.destroy_context(cls.display, cls.context)
        function(cls.egl, "eglDestroySurface", c.c_int, c.c_void_p, c.c_void_p)(cls.display, cls.surface)
        function(cls.egl, "eglTerminate", c.c_int, c.c_void_p)(cls.display)
        cls.folder.cleanup()

    def setUp(self):
        self.clear()
        self.options(0, 0, 100)
        self.reset()

    def use(self, preset, luts=()):
        error = c.create_string_buffer(512)
        self.assertEqual(0, self.prepare(self.folder.name.encode(), preset.encode(), error, 512),
                         error.value.decode())
        for index, (width, height, pixels) in enumerate(luts):
            data = (c.c_ubyte * len(pixels))(*pixels)
            self.assertEqual(1, self.set_lut(index, width, height, data))
        self.assertEqual(0, self.apply_preset(error, 512), error.value.decode())

    def render(self, pixels, width, height, surface_width, surface_height, dirty=1):
        rgba = (c.c_ubyte * len(pixels))(*pixels)
        self.draw(rgba, width, height, dirty, surface_width, surface_height)
        result = (c.c_ubyte * (surface_width * surface_height * 4))()
        self.read(0, 0, surface_width, surface_height, 0x1908, 0x1401, result)
        self.assertEqual(0, self.gl_error(), "Actual GLES draw/read failed")
        return list(result)

    RED_GREEN = [255, 0, 0, 255, 0, 255, 0, 255]

    def test_one_pass_draws_the_picture_through_the_shader(self):
        self.use("invert.glslp")
        self.assertEqual([0, 255, 255, 255, 255, 0, 255, 255], self.render(self.RED_GREEN, 2, 1, 2, 1))
        # Back to the built-in program: the colours as they are.
        self.clear()
        self.assertEqual(self.RED_GREEN, self.render(self.RED_GREEN, 2, 1, 2, 1, 0))

    def test_a_pass_scaled_from_its_source_feeds_the_next_its_size(self):
        self.use("sizes.glslp")
        pixel = self.render(self.RED_GREEN, 2, 1, 4, 2)[:4]
        # Pass 0 doubled the 2x1 picture; the last pass draws the 4x2 viewport.
        self.assertEqual([4, 2, 4, 255], pixel)

    def test_a_pass_reads_an_earlier_one_by_its_alias_and_the_original(self):
        self.use("alias.glslp")
        # Red then green, inverted by pass 0 (cyan, magenta): its red with the original's green.
        self.assertEqual([0, 0, 0, 255, 255, 255, 0, 255], self.render(self.RED_GREEN, 2, 1, 2, 1))

    def test_parameters_take_the_presets_value_then_the_players(self):
        self.use("level.glslp")
        self.assertEqual(64, self.render(self.RED_GREEN, 2, 1, 2, 1)[0])
        self.set_parameter(b"LEVEL", 1.0)
        self.assertEqual(255, self.render(self.RED_GREEN, 2, 1, 2, 1, 0)[0])

    def test_a_lookup_texture_is_sampled_by_its_name(self):
        error = c.create_string_buffer(512)
        self.assertEqual(0, self.prepare(self.folder.name.encode(), b"lut.glslp", error, 512))
        self.assertEqual(1, self.lut_count())
        # Without its texture the preset is refused and the picture stays.
        self.assertEqual(-1, self.apply_preset(error, 512))
        self.assertIn("Texture", error.value.decode())
        self.assertEqual(self.RED_GREEN, self.render(self.RED_GREEN, 2, 1, 2, 1))
        self.use("lut.glslp", [(1, 1, [0, 0, 255, 255])])
        self.assertEqual([0, 0, 255, 255] * 2, self.render(self.RED_GREEN, 2, 1, 2, 1, 0))

    def test_frame_count_moves_only_with_new_frames(self):
        self.use("frames.glslp")
        first = self.render(self.RED_GREEN, 2, 1, 2, 1, 1)[0]
        repeated = self.render(self.RED_GREEN, 2, 1, 2, 1, 0)[0]
        second = self.render(self.RED_GREEN, 2, 1, 2, 1, 1)[0]
        self.assertEqual(first, repeated)
        self.assertNotEqual(first, second)

    def test_the_previous_frame_is_kept_for_the_next(self):
        self.use("previous.glslp")
        green_red = [0, 255, 0, 255, 255, 0, 0, 255]
        self.render(self.RED_GREEN, 2, 1, 2, 1, 1)
        self.assertEqual(self.RED_GREEN, self.render(green_red, 2, 1, 2, 1, 1))
        # A new game starts a new history: the current frame stands in.
        self.reset()
        self.assertEqual(green_red, self.render(green_red, 2, 1, 2, 1, 1))

    def test_a_shader_that_does_not_compile_is_reported_and_the_previous_stays(self):
        self.use("invert.glslp")
        error = c.create_string_buffer(512)
        self.assertEqual(0, self.prepare(self.folder.name.encode(), b"broken.glslp", error, 512))
        self.assertEqual(-1, self.apply_preset(error, 512))
        self.assertTrue(error.value.decode().startswith("Passe 1 (broken.glsl)"), error.value.decode())
        self.assertEqual([0, 255, 255, 255, 255, 0, 255, 255], self.render(self.RED_GREEN, 2, 1, 2, 1))

    def test_the_picture_stays_upright_through_framebuffers(self):
        top_red_bottom_green = [255, 0, 0, 255, 0, 255, 0, 255]
        self.use("upright.glslp")
        through = self.render(top_red_bottom_green, 1, 2, 1, 2)
        self.clear()
        stock = self.render(top_red_bottom_green, 1, 2, 1, 2, 0)
        self.assertEqual(stock, through)
        self.use("final.glslp")
        self.assertEqual(stock, self.render(top_red_bottom_green, 1, 2, 1, 2, 0),
                         "A last pass of its own size is copied to the picture, upright")

    def test_a_lost_context_compiles_the_preset_again(self):
        self.use("invert.glslp")
        self.new_context()
        self.assertEqual([0, 255, 255, 255, 255, 0, 255, 255], self.render(self.RED_GREEN, 2, 1, 2, 1))

    def test_a_preset_outside_its_folder_is_refused(self):
        error = c.create_string_buffer(512)
        self.assertEqual(-1, self.prepare(self.folder.name.encode(), b"../invert.glslp", error, 512))
        self.assertEqual(-1, self.prepare(self.folder.name.encode(), b"missing.glslp", error, 512))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("driver")
    parser.add_argument("--egl", required=True)
    parser.add_argument("--gl", required=True)
    OPTIONS, rest = parser.parse_known_args()
    unittest.main(argv=[__file__] + rest, verbosity=2)
