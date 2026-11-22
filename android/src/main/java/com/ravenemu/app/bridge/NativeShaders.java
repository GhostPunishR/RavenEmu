package com.ravenemu.app.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** JNI API of the video driver's imported shader presets
 * (android/src/main/cpp/hardware/video/shaders/shader_state.h). prepare,
 * lutPaths, setLut and describe run on a worker thread; apply and clear on
 * the GL thread. Texts cross as UTF-8 bytes. */
public final class NativeShaders {
    static {
        System.loadLibrary("raven_android");
    }

    /** A "#pragma parameter" of the preset's shaders, with the preset's value. */
    public static final class Parameter {
        public final String name, description;
        public final float initial, minimum, maximum, step;

        public Parameter(String name, String description, float initial, float minimum, float maximum, float step) {
            this.name = name;
            this.description = description;
            this.initial = initial;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
        }
    }

    private NativeShaders() {}

    /** Reads the preset (relative to `folder`) and its shaders; null, or why not. */
    public static String prepare(String folder, String preset) {
        return text(nativePrepare(utf8(folder), utf8(preset)));
    }

    /** The prepared preset's lookup textures, relative to its folder, in order. */
    public static List<String> lutPaths() {
        String list = text(nativeLutPaths());
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<String> paths = new ArrayList<>();
        for (String path : list.split("\n")) paths.add(path);
        return paths;
    }

    /** A lookup texture's pixels as Bitmap.getPixels gives them (ARGB). */
    public static native boolean setLut(int index, int width, int height, int[] argb);

    /** The parameters of a preset's shaders; no state is kept. */
    public static List<Parameter> describe(String folder, String preset) throws IOException {
        String list = text(nativeDescribe(utf8(folder), utf8(preset)));
        if (list == null) throw new IOException("Mémoire insuffisante");
        if (list.startsWith("!")) throw new IOException(list.substring(1));
        List<Parameter> parameters = new ArrayList<>();
        for (String line : list.split("\n")) {
            String[] field = line.split("\t", -1);
            if (field.length != 6) continue;
            try {
                parameters.add(new Parameter(field[0], field[1], Float.parseFloat(field[2]),
                        Float.parseFloat(field[3]), Float.parseFloat(field[4]), Float.parseFloat(field[5])));
            } catch (NumberFormatException unreadable) {
                // A value C printed is always a number; skipped otherwise.
            }
        }
        return parameters;
    }

    /** A parameter's value, for the prepared and the shown preset. */
    public static void setParameter(String name, float value) { nativeSetParameter(utf8(name), value); }

    /** GL thread: compiles and shows the prepared preset; null, or why not. */
    public static String apply() { return text(nativeApply()); }

    /** GL thread: the built-in effects again. */
    public static native void clear();

    private static byte[] utf8(String text) { return text == null ? null : text.getBytes(StandardCharsets.UTF_8); }

    private static String text(byte[] bytes) { return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8); }

    private static native byte[] nativePrepare(byte[] folder, byte[] preset);
    private static native byte[] nativeLutPaths();
    private static native byte[] nativeDescribe(byte[] folder, byte[] preset);
    private static native void nativeSetParameter(byte[] name, float value);
    private static native byte[] nativeApply();
}
