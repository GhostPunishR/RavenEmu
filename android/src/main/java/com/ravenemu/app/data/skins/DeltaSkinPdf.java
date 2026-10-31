package com.ravenemu.app.data.skins;

import com.ravenemu.app.data.skins.DeltaSkin.Kind;
import com.ravenemu.app.data.skins.DeltaSkinException.Code;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import java.io.File;
import java.io.IOException;

/** A skin's PDF through the platform's PdfRenderer, as the beta's
 * DeltaSkinPdfRenderer: its page count and size when importing, and its page
 * drawn at the panel's size for the player, kept in a bounded cache. Disk and
 * rendering: off the UI thread. */
public final class DeltaSkinPdf {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(cacheBytes()) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };

    private DeltaSkinPdf() {}

    public static DeltaSkinArchive.PdfInfo inspect(File pdf) throws IOException {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            if (renderer.getPageCount() <= 0) throw new IOException("PDF sans page");
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                return new DeltaSkinArchive.PdfInfo(renderer.getPageCount(), page.getWidth(), page.getHeight());
            }
        } catch (SecurityException | IllegalStateException unreadable) {
            throw new IOException("PDF illisible", unreadable);
        }
    }

    /** The page at this size, transparent where the skin is. */
    public static Bitmap render(File pdf, String sha256, Kind kind, int width, int height) throws DeltaSkinException {
        if (width <= 0 || height <= 0) throw new DeltaSkinException(Code.INVALID_MAPPING_SIZE, "Taille du skin nulle");
        String key = sha256 + "|" + kind.name() + "|portrait|" + width + "|" + height;
        synchronized (CACHE) {
            Bitmap cached = CACHE.get(key);
            if (cached != null && !cached.isRecycled()) return cached;
        }
        Bitmap bitmap;
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            if (renderer.getPageCount() != 1)
                throw new DeltaSkinException(Code.PDF_MULTIPLE_PAGES, "Le PDF doit contenir exactement une page");
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.TRANSPARENT);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            }
        } catch (DeltaSkinException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new DeltaSkinException(Code.PDF_INVALID, "Le PDF du skin est illisible", error);
        }
        synchronized (CACHE) {
            CACHE.put(key, bitmap);
        }
        return bitmap;
    }

    /** A replaced or removed skin's pages. */
    public static void invalidate(String sha256) {
        if (sha256 == null) return;
        synchronized (CACHE) {
            for (String key : CACHE.snapshot().keySet()) if (key.startsWith(sha256 + "|")) CACHE.remove(key);
        }
    }

    private static int cacheBytes() {
        long suggested = Runtime.getRuntime().maxMemory() / 16;
        return (int) Math.max(8L * 1024 * 1024, Math.min(32L * 1024 * 1024, suggested));
    }
}
