package com.ravenemu.app.data.library;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Real images through the import and the loading: a chosen cover is kept
 * large enough for a sharp tile, and a tile gets it at its own size, never a
 * small thumbnail stretched over it (the owner found the covers blurred). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class CoverImageQualityTest {
    private static final String KEY = "b".repeat(64);

    private static byte[] png(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff336699);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        bitmap.recycle();
        return out.toByteArray();
    }

    private static File files() { return RuntimeEnvironment.getApplication().getFilesDir(); }

    @Test public void aLargePictureIsKeptAt768PixelsNotAQuarterOfThem() throws IOException {
        CoverRepository.importImage(files(), KEY, png(1000, 1400));
        Bitmap stored = CoverRepository.load(files(), KEY, 2000, 2000);
        // Before: decoded at a quarter (250 × 350) and kept so.
        assertEquals(549, stored.getWidth());
        assertEquals(768, stored.getHeight());
    }

    @Test public void aTileGetsTheCoverAtItsOwnSize() throws IOException {
        CoverRepository.importImage(files(), KEY, png(1200, 1600));
        // A 321 × 372 pixel tile: the cover fills its width, its height is cropped.
        Bitmap tile = CoverRepository.load(files(), KEY, 321, 372);
        assertEquals(321, tile.getWidth());
        assertEquals(428, tile.getHeight());
        Bitmap small = CoverRepository.load(files(), KEY, 44, 52);
        assertEquals("A list's small cover stays small", 44, small.getWidth());
    }

    @Test public void aSmallPictureIsNeverEnlarged() throws IOException {
        CoverRepository.importImage(files(), KEY, png(120, 160));
        Bitmap tile = CoverRepository.load(files(), KEY, 321, 372);
        assertEquals(120, tile.getWidth());
        assertEquals(160, tile.getHeight());
    }

    @Test public void noCoverIsNull() throws IOException {
        assertNull(CoverRepository.load(files(), "e".repeat(64), 321, 372));
    }
}
