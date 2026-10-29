package com.ravenemu.app.data.library;


import org.junit.Test;
import java.util.Arrays;
import java.util.List;
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

    @Test public void findsByFilenameAndCartridgeTitleWithoutAccents() {
        RomLibrary.Entry alpha = new RomLibrary.Entry("a", "École.gbc", "gbc", 512, "GHOST");
        RomLibrary.Entry beta = new RomLibrary.Entry("b", "Zelda.gb", "gb", 256, "ADVENTURE");
        RomLibrary.Entry gamma = new RomLibrary.Entry("c", "Puzzle.gba", "gba", 1024);
        List<RomLibrary.Entry> entries = Arrays.asList(alpha, beta, gamma);

        assertEquals(alpha, RomLibrary.query(entries, "ecole", "",
                RomLibrary.SortOrder.TITLE_ASC).get(0));
        assertEquals(alpha, RomLibrary.query(entries, "ghost", "gbc",
                RomLibrary.SortOrder.TITLE_ASC).get(0));
        assertTrue(RomLibrary.query(entries, "ghost", "gb",
                RomLibrary.SortOrder.TITLE_ASC).isEmpty());
        assertTrue(alpha.label().contains("En-tête"));
    }

    @Test public void sortsByTitleAndPlatformAndKeepsSourceIntact() {
        RomLibrary.Entry gba = new RomLibrary.Entry("c", "A game", "gba", 5);
        RomLibrary.Entry gbc = new RomLibrary.Entry("a", "Z game", "gbc", 6);
        RomLibrary.Entry gb = new RomLibrary.Entry("b", "M game", "gb", 7);
        List<RomLibrary.Entry> entries = Arrays.asList(gbc, gb, gba);
        List<RomLibrary.Entry> ascending = RomLibrary.query(entries, "", "",
                RomLibrary.SortOrder.TITLE_ASC);
        assertEquals(Arrays.asList(gba, gb, gbc), ascending);
        List<RomLibrary.Entry> descending = RomLibrary.query(entries, "", "",
                RomLibrary.SortOrder.TITLE_DESC);
        assertEquals(Arrays.asList(gbc, gb, gba), descending);
        List<RomLibrary.Entry> bySystem = RomLibrary.query(entries, "", "",
                RomLibrary.SortOrder.SYSTEM);
        assertEquals(Arrays.asList(gb, gba, gbc), bySystem);
        assertEquals(Arrays.asList(gbc, gb, gba), entries);
    }
}
