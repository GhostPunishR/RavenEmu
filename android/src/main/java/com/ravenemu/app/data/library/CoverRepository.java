package com.ravenemu.app.data.library;


import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Private, user-provided cover art, never downloaded from the internet.
 * The filename is a verified SHA-256 ROM identity, not a user-controlled path.
 * Copies are normalized to PNG images of at most 768 pixels a side before
 * atomic publication: sharp in a library tile or the game's sheet, even on
 * the densest screens. Decoding by powers of two never goes below the size
 * wanted; one filtered scaling then gives it exactly. */
public final class CoverRepository {
    public static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_STORED_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SOURCE_DIMENSION = 4096;
    /** The longest side kept. Earlier versions kept 512 and lost more on
     * import: their covers stay readable, sharper once chosen again. */
    static final int MAX_COVER_EDGE = 768;

    private CoverRepository() {}

    public static boolean validKey(String key) {
        return key != null && key.matches("[a-f0-9]{64}");
    }

    public static File coverFile(File files, String key) {
        if (!validKey(key)) throw new IllegalArgumentException("Invalid cover identity");
        return new File(new File(files, "native-library/covers"), key + ".png");
    }

    public static boolean exists(File files, String key) {
        File file = coverFile(files, key);
        return file.isFile() || new File(file.getPath() + ".bak").isFile();
    }

    /** Called off the UI thread; images are decoded only after size checks. */
    public static void importImage(File files, String key, byte[] raw) throws IOException {
        if (!validKey(key)) throw new IOException("Identifiant de jeu invalide");
        if (raw == null || raw.length == 0 || raw.length > MAX_SOURCE_BYTES)
            throw new IOException("Image vide ou supérieure à 8 Mio");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (bounds.outWidth < 16 || bounds.outHeight < 16 ||
            bounds.outWidth > MAX_SOURCE_DIMENSION ||
            bounds.outHeight > MAX_SOURCE_DIMENSION ||
            (long)bounds.outWidth * bounds.outHeight > 16_000_000L)
            throw new IOException("Dimensions de jaquette non acceptées");

        int[] kept = fitSize(bounds.outWidth, bounds.outHeight, MAX_COVER_EDGE);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = keepingSample(bounds.outWidth, bounds.outHeight, kept[0], kept[1]);
        Bitmap decoded = null, scaled = null;
        try {
            decoded = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
            if (decoded == null) throw new IOException("Image non décodable");
            scaled = scaledTo(decoded, fitSize(decoded.getWidth(), decoded.getHeight(), MAX_COVER_EDGE));

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output))
                throw new IOException("Échec d'encodage de la jaquette");
            byte[] png = output.toByteArray();
            if (png.length == 0 || png.length > MAX_STORED_BYTES)
                throw new IOException("Jaquette encodée trop volumineuse");

            File path = coverFile(files, key);
            File parent = path.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs())
                throw new IOException("Dossier de jaquettes inaccessible");
            AtomicFile atomic = new AtomicFile(path);
            FileOutputStream stream = atomic.startWrite();
            try {
                stream.write(png);
                atomic.finishWrite(stream);
            } catch (IOException | RuntimeException exception) {
                atomic.failWrite(stream);
                throw exception;
            }
        } catch (OutOfMemoryError error) {
            throw new IOException("Mémoire insuffisante pour la jaquette", error);
        } finally {
            if (scaled != null && scaled != decoded) scaled.recycle();
            if (decoded != null) decoded.recycle();
        }
    }

    /** The size fitting within `maxEdge` a side, never enlarged. */
    static int[] fitSize(int width, int height, int maxEdge) {
        if (width <= 0 || height <= 0 || maxEdge <= 0)
            throw new IllegalArgumentException("Invalid image dimensions");
        double scale = Math.min(1d, (double) maxEdge / Math.max(width, height));
        return new int[]{Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
    }

    /** The size covering a box (cropped to fill it, as the library shows
     * covers), never enlarged. */
    static int[] coverSize(int width, int height, int boxWidth, int boxHeight) {
        if (width <= 0 || height <= 0 || boxWidth <= 0 || boxHeight <= 0)
            throw new IllegalArgumentException("Invalid image dimensions");
        double scale = Math.min(1d, Math.max((double) boxWidth / width, (double) boxHeight / height));
        return new int[]{Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
    }

    /** The largest power-of-two reduction that still leaves at least this size. */
    static int keepingSample(int width, int height, int wantedWidth, int wantedHeight) {
        if (width <= 0 || height <= 0 || wantedWidth <= 0 || wantedHeight <= 0)
            throw new IllegalArgumentException("Invalid image dimensions");
        int sample = 1;
        while (sample < 128 && width / (sample * 2) >= wantedWidth && height / (sample * 2) >= wantedHeight)
            sample *= 2;
        return sample;
    }

    /** One filtered scaling to exactly `size`; the bitmap itself when it already is. */
    private static Bitmap scaledTo(Bitmap bitmap, int[] size) {
        if (bitmap.getWidth() == size[0] && bitmap.getHeight() == size[1]) return bitmap;
        return Bitmap.createScaledBitmap(bitmap, size[0], size[1], true);
    }

    public static int sampleSize(int width, int height, int maxEdge) {
        if (width <= 0 || height <= 0 || maxEdge <= 0)
            throw new IllegalArgumentException("Invalid image dimensions");
        int sample = 1;
        while (Math.max(width / sample, height / sample) > maxEdge && sample < 128)
            sample *= 2;
        return sample;
    }

    /** Returns null if no image exists. On corrupt files, throws IOException. */
    public static Bitmap load(File files, String key, int maxEdge) throws IOException {
        File path = coverFile(files, key);
        if (!exists(files, key)) return null;
        if (maxEdge <= 0 || maxEdge > MAX_COVER_EDGE)
            throw new IllegalArgumentException("Invalid thumbnail size");
        AtomicFile atomic = new AtomicFile(path);
        try (InputStream input = atomic.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = input.read(chunk)) != -1) {
                if (output.size() > MAX_STORED_BYTES - n)
                    throw new IOException("Jaquette corrompue ou trop volumineuse");
                output.write(chunk, 0, n);
            }
            byte[] bytes = output.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > MAX_COVER_EDGE || bounds.outHeight > MAX_COVER_EDGE)
                throw new IOException("Dimensions de jaquette invalides");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) throw new IOException("Jaquette illisible");
            return bitmap;
        } catch (OutOfMemoryError error) {
            throw new IOException("Mémoire insuffisante pour la jaquette", error);
        }
    }

    /** The cover to fill a `width` × `height` pixel box, cropped as the
     * library shows it: decoded by powers of two while it still covers the
     * box, then scaled once to just cover it; never enlarged. Null without a
     * cover; IOException when it is corrupt. Off the UI thread. */
    public static Bitmap load(File files, String key, int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096)
            throw new IllegalArgumentException("Invalid cover size");
        if (!exists(files, key)) return null;
        byte[] bytes = read(files, key);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > MAX_COVER_EDGE || bounds.outHeight > MAX_COVER_EDGE)
            throw new IOException("Dimensions de jaquette invalides");
        int[] wanted = coverSize(bounds.outWidth, bounds.outHeight, width, height);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = keepingSample(bounds.outWidth, bounds.outHeight, wanted[0], wanted[1]);
        Bitmap decoded = null;
        try {
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (decoded == null) throw new IOException("Jaquette illisible");
            Bitmap shown = scaledTo(decoded, coverSize(decoded.getWidth(), decoded.getHeight(), width, height));
            if (shown != decoded) decoded.recycle();
            return shown;
        } catch (OutOfMemoryError error) {
            if (decoded != null) decoded.recycle();
            throw new IOException("Mémoire insuffisante pour la jaquette", error);
        }
    }

    private static byte[] read(File files, String key) throws IOException {
        AtomicFile atomic = new AtomicFile(coverFile(files, key));
        try (InputStream input = atomic.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = input.read(chunk)) != -1) {
                if (output.size() > MAX_STORED_BYTES - n)
                    throw new IOException("Jaquette corrompue ou trop volumineuse");
                output.write(chunk, 0, n);
            }
            return output.toByteArray();
        }
    }

    public static boolean remove(File files, String key) {
        File path = coverFile(files, key);
        if (!exists(files, key)) return false;
        // AtomicFile removes the current file, an interrupted write and backup.
        new AtomicFile(path).delete();
        return true;
    }
}
