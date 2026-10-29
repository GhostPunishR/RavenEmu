package com.ravenemu.nativepreview;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RomLibraryTest {
    @Test public void systemSelectionOnlySupportsKnownExtensions() {
        assertEquals("gb", RomLibrary.detectSystem("Tetris.GB"));
        assertEquals("gb", RomLibrary.detectSystem("demo.sgb"));
        assertEquals("gbc", RomLibrary.detectSystem("Pokemon.GBC"));
        assertEquals("gba", RomLibrary.detectSystem("Demo.Gba"));
        assertNull(RomLibrary.detectSystem("danger.zip"));
        assertNull(RomLibrary.detectSystem("../not-a-game.gb.exe"));
        assertNull(RomLibrary.detectSystem(null));
    }

    @Test public void titlesDoNotContainPathsOrControls() {
        assertEquals("Game.gb", RomLibrary.safeTitle("../folder/Game.gb"));
        assertEquals("Test.gba", RomLibrary.safeTitle("dir\\Test.gba"));
        assertEquals("Jeu", RomLibrary.safeTitle(""));
        assertEquals("AB", RomLibrary.safeTitle("A\nB"));
        assertEquals(128, RomLibrary.safeTitle("a".repeat(150)).length());
    }
}
