package com.ravenemu.app.data.library;


import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/** Pure validation rules run on the JVM; decoding/storage will additionally
 * be verified in an Android instrumentation/device pass. */
public final class CoverRepositoryTest {
    private static final String KEY = "a".repeat(64);

    @Test public void coverIdentityIsScopedToSha256NotFilenames() {
        assertTrue(CoverRepository.validKey(KEY));
        assertFalse(CoverRepository.validKey("../other.png"));
        assertFalse(CoverRepository.validKey("A".repeat(64)));
        assertFalse(CoverRepository.validKey("a".repeat(63)));
        assertFalse(CoverRepository.validKey(null));
        File file = CoverRepository.coverFile(new File("/private/files"), KEY);
        assertEquals(KEY + ".png", file.getName());
        assertEquals("covers", file.getParentFile().getName());
        assertEquals("native-library", file.getParentFile().getParentFile().getName());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPathTraversal() {
        CoverRepository.coverFile(new File("."), "../../secret");
    }

    @Test public void samplingIsBoundedToPowersOfTwo() {
        assertEquals(1, CoverRepository.sampleSize(128, 128, 512));
        assertEquals(1, CoverRepository.sampleSize(512, 400, 512));
        assertEquals(2, CoverRepository.sampleSize(1024, 512, 512));
        assertEquals(8, CoverRepository.sampleSize(4096, 2048, 512));
        assertEquals(4, CoverRepository.sampleSize(700, 200, 192));
    }

    @Test public void aCoverKeepsUpTo768PixelsASideNeverEnlarged() {
        assertArrayEquals(new int[]{576, 768}, CoverRepository.fitSize(1200, 1600, 768));
        assertArrayEquals(new int[]{768, 576}, CoverRepository.fitSize(4000, 3000, 768));
        assertArrayEquals("Small images stay as they are", new int[]{300, 400},
                CoverRepository.fitSize(300, 400, 768));
    }

    @Test public void decodingNeverDropsBelowTheSizeWanted() {
        // 1000 × 1400 kept at 549 × 768: halving would give 500 wide, too few.
        assertEquals(1, CoverRepository.keepingSample(1000, 1400, 549, 768));
        assertEquals(4, CoverRepository.keepingSample(4000, 3000, 768, 576));
        assertEquals(2, CoverRepository.keepingSample(1536, 2048, 576, 768));
        // The former rule went below it: 1000 × 1400 for 512 gave a quarter, 250 × 350.
        assertEquals(4, CoverRepository.sampleSize(1000, 1400, 512));
    }

    @Test public void aTileGetsTheCoverCroppedToFillItNeverEnlarged() {
        // A 3:4 cover in a 321 × 372 pixel tile: its width fills it, the rest is cropped.
        assertArrayEquals(new int[]{321, 428}, CoverRepository.coverSize(576, 768, 321, 372));
        // A wide cover: its height fills the tile.
        assertArrayEquals(new int[]{496, 372}, CoverRepository.coverSize(768, 576, 321, 372));
        assertArrayEquals("Smaller than the tile: as it is", new int[]{200, 250},
                CoverRepository.coverSize(200, 250, 321, 372));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnEmptyBox() {
        CoverRepository.coverSize(576, 768, 0, 372);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidSampleDimensions() {
        CoverRepository.sampleSize(0, 100, 512);
    }
}
