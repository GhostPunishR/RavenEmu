package com.ravenemu.nativepreview;

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

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidSampleDimensions() {
        CoverRepository.sampleSize(0, 100, 512);
    }
}
