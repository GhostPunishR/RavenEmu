package com.ravenemu.app.data.saves;


import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Bounded conversion of native ARGB snapshots to private PNG previews.
 * Encoding and decoding belong to the storage executor, never the GL/UI loop. */
public final class StatePreview {
    static final int MAX_EDGE = 160;
    static final int MAX_BYTES = 256 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte)137, 80, 78, 71, 13, 10, 26, 10};

    private StatePreview() {}

    /** Frame layout: width, height, then exactly width*height ARGB pixels. */
    public static byte[] encode(int[] frame) throws IOException {
        if (frame == null) return null;
        if (frame.length < 2 || frame[0] <= 0 || frame[1] <= 0 ||
                frame[0] > MAX_EDGE || frame[1] > MAX_EDGE ||
                frame.length != 2 + frame[0] * frame[1])
            throw new IOException("Dimensions d'aperçu invalides");
        Bitmap bitmap = Bitmap.createBitmap(frame, 2, frame[0], frame[0], frame[1], Bitmap.Config.ARGB_8888);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) || output.size() > MAX_BYTES)
                throw new IOException("Aperçu indisponible ou trop volumineux");
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }

    /** A bad optional image must never make an otherwise valid state unloadable. */
    public static Bitmap decode(byte[] png) {
        if (png == null || png.length < PNG_SIGNATURE.length || png.length > MAX_BYTES) return null;
        for (int i = 0; i < PNG_SIGNATURE.length; i++) if (png[i] != PNG_SIGNATURE[i]) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > MAX_EDGE || bounds.outHeight > MAX_EDGE ||
                !"image/png".equals(bounds.outMimeType)) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(png, 0, png.length, options);
    }
}
