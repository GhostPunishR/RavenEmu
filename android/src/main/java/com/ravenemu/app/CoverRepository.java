package com.ravenemu.app;

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
 * Copies are normalized to small PNG images before atomic publication. */
final class CoverRepository {
    static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_STORED_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SOURCE_DIMENSION = 4096;
    private static final int MAX_COVER_EDGE = 512;

    private CoverRepository() {}

    static boolean validKey(String key) {
        return key != null && key.matches("[a-f0-9]{64}");
    }

    static File coverFile(File files, String key) {
        if (!validKey(key)) throw new IllegalArgumentException("Invalid cover identity");
        return new File(new File(files, "native-library/covers"), key + ".png");
    }

    static boolean exists(File files, String key) {
        File file = coverFile(files, key);
        return file.isFile() || new File(file.getPath() + ".bak").isFile();
    }

    /** Called off the UI thread; images are decoded only after size checks. */
    static void importImage(File files, String key, byte[] raw) throws IOException {
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

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_COVER_EDGE);
        Bitmap decoded = null, scaled = null;
        try {
            decoded = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
            if (decoded == null) throw new IOException("Image non décodable");
            float scale = Math.min(1f, (float)MAX_COVER_EDGE /
                    Math.max(decoded.getWidth(), decoded.getHeight()));
            int w = Math.max(1, Math.round(decoded.getWidth() * scale));
            int h = Math.max(1, Math.round(decoded.getHeight() * scale));
            scaled = Bitmap.createScaledBitmap(decoded, w, h, true);

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

    static int sampleSize(int width, int height, int maxEdge) {
        if (width <= 0 || height <= 0 || maxEdge <= 0)
            throw new IllegalArgumentException("Invalid image dimensions");
        int sample = 1;
        while (Math.max(width / sample, height / sample) > maxEdge && sample < 128)
            sample *= 2;
        return sample;
    }

    /** Returns null if no image exists. On corrupt files, throws IOException. */
    static Bitmap load(File files, String key, int maxEdge) throws IOException {
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

    static boolean remove(File files, String key) {
        File path = coverFile(files, key);
        if (!exists(files, key)) return false;
        // AtomicFile removes the current file, an interrupted write and backup.
        new AtomicFile(path).delete();
        return true;
    }
}
