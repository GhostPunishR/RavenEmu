package com.ravenemu.app.data.library;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure Java: the beta's LinkCable rules and the core both games run in. */
public final class LinkCableTest {
    private static final RomLibrary.Entry RED = new RomLibrary.Entry("a", "Rouge", "gb", 1);
    private static final RomLibrary.Entry GOLD = new RomLibrary.Entry("b", "Or", "gbc", 1);
    private static final RomLibrary.Entry RUBY = new RomLibrary.Entry("c", "Rubis", "gba", 1);
    private static final RomLibrary.Entry BLUE = new RomLibrary.Entry("d", "Bleu", "gb", 1);
    private static final RomLibrary.Entry EMERALD = new RomLibrary.Entry("e", "Émeraude", "gba", 1);

    @Test public void gameBoyAndColorMixTwoAdvanceGoTogetherAndNoGameMeetsItself() {
        assertTrue(LinkCable.compatible("gb", "gbc"));
        assertTrue(LinkCable.compatible("gbc", "gb"));
        assertTrue(LinkCable.compatible("gba", "gba"));
        assertFalse(LinkCable.compatible("gb", "gba"));
        assertFalse(LinkCable.compatible("gba", "gbc"));
        List<RomLibrary.Entry> library = Arrays.asList(RED, GOLD, RUBY, BLUE, EMERALD);
        assertEquals("In the library's order", Arrays.asList(GOLD, BLUE), LinkCable.partners(RED, library));
        assertEquals(Arrays.asList(EMERALD), LinkCable.partners(RUBY, library));
        assertTrue(LinkCable.partners(RUBY, Arrays.asList(RUBY, RED)).isEmpty());
    }

    @Test public void bothGamesRunInOneCoreOnTheConsoleTheMostDemandingNeeds() {
        assertEquals("gbc", LinkCable.core("gbc", "gbc"));
        assertEquals("gba", LinkCable.core("gba", "gba"));
        assertNull(LinkCable.core("gba", "gbc"));
        assertNull(LinkCable.core("gbc", "gba"));
        assertEquals("gb", LinkCable.hardware("gb", "gb"));
        assertEquals("gbc", LinkCable.hardware("gb", "gbc"));
        assertEquals("gbc", LinkCable.hardware("gbc", "gb"));
        assertEquals("gbc", LinkCable.hardware("gbc", "gbc"));
        assertEquals("The GBC of a GBA when the settings ask for it", "gba", LinkCable.hardware("gb", "gba"));
        assertNull("Two GBA games: no Game Boy console", LinkCable.hardware(null, null));
    }
}
