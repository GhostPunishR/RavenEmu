package com.ravenemu.app.ui.common;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class RavenIconTest {
    private static final int ACCENT = 0xffa78bfa;

    /** Draws `glyph` in a 48 px box at (8, 8) of a 64 px bitmap. */
    private static Bitmap draw(RavenIcon.Glyph glyph, int tint) {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        RavenIcon icon = new RavenIcon(glyph, tint, 48);
        icon.setBounds(8, 8, 56, 56);
        icon.draw(new Canvas(bitmap));
        return bitmap;
    }

    @Test public void everyGlyphDrawsItsShapeInItsColourAndStaysInItsBox() {
        for (RavenIcon.Glyph glyph : RavenIcon.Glyph.values()) {
            Bitmap bitmap = draw(glyph, ACCENT);
            int painted = 0, solid = 0;
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    int pixel = bitmap.getPixel(x, y);
                    if (Color.alpha(pixel) == 0) continue;
                    assertTrue(glyph + " outside its box at " + x + "," + y, x >= 8 && x < 56 && y >= 8 && y < 56);
                    painted++;
                    if (pixel == ACCENT) solid++;
                }
            }
            assertTrue(glyph + " draws a shape", painted > 60);
            assertTrue(glyph + " in its colour", solid > 20);
            bitmap.recycle();
        }
    }

    @Test public void theGbcScreenIsInColourAndDimsOnAnInactivePage() {
        Bitmap bitmap = draw(RavenIcon.Glyph.GBC, 0xffaaa2bc);
        // The screen's left side: the red end of the beta's colour screen.
        int left = bitmap.getPixel(8 + 21, 8 + 15);
        assertTrue(Integer.toHexString(left), Color.red(left) > Color.blue(left) + 40);
        RavenIcon dim = new RavenIcon(RavenIcon.Glyph.GBC, 0xffaaa2bc, 48);
        dim.setScreenAlpha(128);
        Bitmap dimmed = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        dim.setBounds(8, 8, 56, 56);
        dim.draw(new Canvas(dimmed));
        assertTrue(Color.alpha(dimmed.getPixel(8 + 21, 8 + 15)) < 200);
        bitmap.recycle();
        dimmed.recycle();
    }

    @Test public void aRadioMarkIsFilledOnlyWhenChosenAndTheTintFollows() {
        Bitmap on = draw(RavenIcon.Glyph.RADIO_ON, ACCENT), off = draw(RavenIcon.Glyph.RADIO_OFF, ACCENT);
        assertEquals(ACCENT, on.getPixel(32, 32));
        assertEquals(0, Color.alpha(off.getPixel(32, 32)));
        RavenIcon star = new RavenIcon(RavenIcon.Glyph.STAR, ACCENT, 24);
        star.setTint(0xffffd76a);
        assertEquals(0xffffd76a, star.tint());
        assertEquals(24, star.getIntrinsicWidth());
        assertEquals(RavenIcon.Glyph.STAR, star.glyph());
        on.recycle();
        off.recycle();
    }
}
