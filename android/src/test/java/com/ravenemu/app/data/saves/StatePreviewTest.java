package com.ravenemu.app.data.saves;


import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class StatePreviewTest {
    @Test public void argbSnapshotRoundTripsAsPngWithoutChangingAspectOrPixels() throws Exception {
        byte[] png = StatePreview.encode(new int[]{3, 2,
                0xffff0000, 0xff00ff00, 0xff0000ff,
                0xff123456, 0xffabcdef, 0xffffffff});
        assertNotNull(png);
        assertTrue(png.length <= StatePreview.MAX_BYTES);
        Bitmap decoded = StatePreview.decode(png);
        assertNotNull(decoded);
        assertEquals(3, decoded.getWidth()); assertEquals(2, decoded.getHeight());
        assertEquals(0xffff0000, decoded.getPixel(0, 0));
        assertEquals(0xff123456, decoded.getPixel(0, 1));
        assertEquals(0xffffffff, decoded.getPixel(2, 1));
        decoded.recycle();
    }

    @Test public void maximumNativeFrameIsAcceptedAndAbsenceRemainsOptional() throws Exception {
        int[] frame = new int[2 + 160 * 160];
        frame[0] = frame[1] = 160;
        Arrays.fill(frame, 2, frame.length, 0xffa78bfa);
        Bitmap bitmap = StatePreview.decode(StatePreview.encode(frame));
        assertNotNull(bitmap); assertEquals(160, bitmap.getWidth());
        assertEquals(0xffa78bfa, bitmap.getPixel(159, 159));
        bitmap.recycle();
        assertNull(StatePreview.encode(null));
        assertNull(StatePreview.decode(null));
    }

    @Test public void invalidNativeDimensionsOrPixelCountsAreRejectedBeforeBitmapAllocation() {
        for (int[] frame : new int[][]{new int[0], {1}, {0, 1}, {-1, 1},
                {161, 1}, {1, 161}, {Integer.MAX_VALUE, Integer.MAX_VALUE}, {2, 2, 0}, {1, 1, 0, 0}}) {
            try { StatePreview.encode(frame); fail("Expected invalid snapshot rejection"); }
            catch (IOException expected) { /* bounded native frame contract */ }
        }
    }

    @Test public void malformedNonPngOversizedBytesAndOversizedDimensionsUseThePlaceholder() {
        assertNull(StatePreview.decode(new byte[0]));
        assertNull(StatePreview.decode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
        assertNull(StatePreview.decode(new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10}));
        assertNull(StatePreview.decode(new byte[StatePreview.MAX_BYTES + 1]));
        Bitmap oversized = Bitmap.createBitmap(161, 1, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        oversized.compress(Bitmap.CompressFormat.PNG, 100, encoded);
        oversized.recycle();
        assertNull(StatePreview.decode(encoded.toByteArray()));
    }
}
