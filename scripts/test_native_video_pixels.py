#!/usr/bin/env python3
"""Render the actual native GLES shader with software EGL, without Android.

This checks pixel math and viewport behavior, not phone performance or latency.
Run with the host raven_native_video_pixels shared library, never the ARM64 APK.
"""

import argparse
import ctypes as c
import os
import unittest


def function(library, name, result, *arguments):
    call = getattr(library, name)
    call.restype, call.argtypes = result, arguments
    return call


class VideoPixelsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.environ.setdefault("EGL_PLATFORM", "surfaceless")
        os.environ.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
        os.environ.setdefault("MESA_GLES_VERSION_OVERRIDE", "2.0")
        os.environ.setdefault("MESA_SHADER_CACHE_DISABLE", "true")
        cls.egl = c.CDLL(OPTIONS.egl)
        cls.gl = c.CDLL(OPTIONS.gl, mode=c.RTLD_GLOBAL)
        cls.driver = c.CDLL(OPTIONS.driver)
        pointer, integer = c.c_void_p, c.c_int
        get_display = function(cls.egl, "eglGetDisplay", pointer, pointer)
        cls.display = get_display(None)
        initialize = function(cls.egl, "eglInitialize", integer, pointer, c.POINTER(integer), c.POINTER(integer))
        major, minor = integer(), integer()
        if not initialize(cls.display, c.byref(major), c.byref(minor)):
            raise RuntimeError("Software EGL initialization failed")
        bind = function(cls.egl, "eglBindAPI", integer, c.c_uint)
        if not bind(0x30A0):
            raise RuntimeError("EGL cannot bind OpenGL ES")
        attrs = (integer * 13)(0x3033, 1, 0x3040, 4, 0x3024, 8,
                               0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
        cls.config, count = pointer(), integer()
        choose = function(cls.egl, "eglChooseConfig", integer, pointer, c.POINTER(integer),
                          c.POINTER(pointer), integer, c.POINTER(integer))
        if not choose(cls.display, attrs, c.byref(cls.config), 1, c.byref(count)) or not count.value:
            raise RuntimeError("EGL has no RGBA8 GLES2 pbuffer configuration")
        create_surface = function(cls.egl, "eglCreatePbufferSurface", pointer, pointer, pointer, c.POINTER(integer))
        surface_attrs = (integer * 5)(0x3057, 256, 0x3056, 16, 0x3038)
        cls.surface = create_surface(cls.display, cls.config, surface_attrs)
        cls.create_context = function(cls.egl, "eglCreateContext", pointer, pointer, pointer,
                                      pointer, c.POINTER(integer))
        cls.make_current = function(cls.egl, "eglMakeCurrent", integer, pointer, pointer, pointer, pointer)
        cls.destroy_context = function(cls.egl, "eglDestroyContext", integer, pointer, pointer)
        cls.context = None
        cls.init = function(cls.driver, "raven_video_init_gl", None)
        cls.reset = function(cls.driver, "raven_video_reset", None)
        cls.options = function(cls.driver, "raven_video_set_options", None, integer, integer, integer)
        cls.draw = function(cls.driver, "raven_video_draw", None, pointer, c.c_uint,
                            c.c_uint, integer, integer, integer)
        cls.read = function(cls.gl, "glReadPixels", None, integer, integer, integer,
                            integer, c.c_uint, c.c_uint, pointer)
        cls.gl_error = function(cls.gl, "glGetError", c.c_uint)
        cls.disable = function(cls.gl, "glDisable", None, c.c_uint)
        cls.new_context()
        version = function(cls.gl, "glGetString", c.c_char_p, c.c_uint)(0x1F02)
        print("Pixel oracle:", version.decode() if version else "unknown GLES", flush=True)

    @classmethod
    def new_context(cls):
        if cls.context:
            cls.make_current(cls.display, None, None, None)
            cls.destroy_context(cls.display, cls.context)
        attrs = (c.c_int * 3)(0x3098, 2, 0x3038)
        cls.context = cls.create_context(cls.display, cls.config, None, attrs)
        if not cls.context or not cls.make_current(cls.display, cls.surface, cls.surface, cls.context):
            raise RuntimeError("Cannot make GLES2 pbuffer current")
        cls.disable(0x0BD0)  # Dithering is unrelated to the colour adjustment.
        cls.init()

    @classmethod
    def tearDownClass(cls):
        cls.make_current(cls.display, None, None, None)
        cls.destroy_context(cls.display, cls.context)
        function(cls.egl, "eglDestroySurface", c.c_int, c.c_void_p, c.c_void_p)(cls.display, cls.surface)
        function(cls.egl, "eglTerminate", c.c_int, c.c_void_p)(cls.display)

    def render(self, pixels, width, height, surface_width, surface_height, dirty=1):
        rgba = (c.c_ubyte * len(pixels))(*pixels)
        self.draw(rgba, width, height, dirty, surface_width, surface_height)
        result = (c.c_ubyte * (surface_width * surface_height * 4))()
        self.read(0, 0, surface_width, surface_height, 0x1908, 0x1401, result)
        self.assertEqual(0, self.gl_error(), "Actual GLES draw/read failed")
        return list(result)

    @staticmethod
    def gradient():
        return [channel for i in range(256) for channel in (i, 255 - i, (i * 37) % 256, 255)]

    def expect_adjustment(self, source, brightness, contrast, dirty):
        output = self.render(source, 256, 1, 256, 1, dirty)
        expected = []
        for index, value in enumerate(source):
            if index % 4 == 3:
                expected.append(255)
            else:
                scaled = (value - 128) * contrast
                truncated = (abs(scaled) // 100) * (-1 if scaled < 0 else 1)
                expected.append(min(255, max(0, truncated + 128 + brightness * 2)))
        tolerance = 0 if brightness == 0 and contrast == 100 else 1
        self.assertLessEqual(max(abs(a - b) for a, b in zip(output, expected)), tolerance)

    def test_defaults_and_colour_controls_match_beta_integer_math(self):
        source = self.gradient()
        self.options(0, 0, 100)
        self.reset()
        self.expect_adjustment(source, 0, 100, 1)
        for brightness, contrast in ((-50, 50), (50, 150), (0, 73), (17, 121), (-13, 97)):
            self.options(0, brightness, contrast)
            self.expect_adjustment(source, brightness, contrast, 0)
        self.options(0, -999, 999)
        self.expect_adjustment(source, -50, 150, 0)
        self.options(0, 0, 100)
        self.expect_adjustment(source, 0, 100, 0)

    def test_integer_scaling_and_small_surface_fallback(self):
        source = [255, 0, 0, 255, 0, 255, 0, 255]
        self.options(1, 0, 100)
        self.reset()
        output = self.render(source, 2, 1, 7, 7)
        # The 6x3 picture sits at the top: rows 4 to 6, GL counting from the bottom.
        row = output[5 * 7 * 4:6 * 7 * 4]
        self.assertEqual([255, 0, 0, 255] * 3 + [0, 255, 0, 255] * 3, row[:24])
        self.assertLess(max(row[24:27]), 25)  # Last column is outside the 6x3 viewport.
        self.options(0, 0, 100)
        output = self.render(source, 2, 1, 7, 7, 0)
        self.assertEqual([0, 255, 0, 255], output[(5 * 7 + 6) * 4:(5 * 7 + 7) * 4])
        self.assertEqual([0, 0, 0, 255], output[:4], "Black below the picture")
        self.options(1, 0, 100)
        self.assertIn(self.render(source, 2, 1, 1, 1, 0),
                      ([255, 0, 0, 255], [0, 255, 0, 255]))

    def test_options_survive_new_context_and_rom_reset(self):
        source = self.gradient()
        self.options(1, 17, 121)
        self.expect_adjustment(source, 17, 121, 1)
        self.new_context()
        self.expect_adjustment(source, 17, 121, 0)
        self.reset()
        self.expect_adjustment(source, 17, 121, 0)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("driver")
    parser.add_argument("--egl", default="libEGL.so.1")
    parser.add_argument("--gl", default="libGL.so.1")
    OPTIONS = parser.parse_args()
    unittest.main(argv=[__file__])
