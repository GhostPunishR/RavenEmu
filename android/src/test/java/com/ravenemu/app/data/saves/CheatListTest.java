package com.ravenemu.app.data.saves;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CheatListTest {
    @Test public void theFileReadsAsRavenEmuPcWritesIt() {
        List<CheatList.Cheat> cheats = CheatList.parse(
                "1\tVies infinies\t010238CD\r\n0\tArgent\t0199B5D0  0199B6D0\nligne sans tabulation\n1\tVide\t  \n");
        assertEquals(Arrays.asList(new CheatList.Cheat("Vies infinies", "010238CD", true),
                new CheatList.Cheat("Argent", "0199B5D0 0199B6D0", false)), cheats);
        assertEquals("1\tVies infinies\t010238CD\n0\tArgent\t0199B5D0 0199B6D0\n", CheatList.format(cheats));
        assertEquals(cheats, CheatList.parse(CheatList.format(cheats)));
    }

    @Test public void aNameNeverSplitsItsLineAndACodeStaysOnOne() {
        String text = CheatList.format(Arrays.asList(new CheatList.Cheat("A\tB\nC", " 82000000 0063\n82000002 0001 ", true)));
        assertEquals("1\tA B C\t82000000 0063 82000002 0001\n", text);
        assertEquals("ABC-DEF-GHI", CheatList.oneLine("\tABC-DEF-GHI\r\n"));
        assertEquals("", CheatList.oneLine(null));
    }

    @Test public void onlyTheEnabledCodesRunAndTheMenuCountsThem() {
        List<CheatList.Cheat> cheats = Arrays.asList(new CheatList.Cheat("a", "1", true),
                new CheatList.Cheat("b", "2", false), new CheatList.Cheat("c", "3", true));
        assertArrayEquals(new String[]{"1", "3"}, CheatList.active(cheats));
        assertEquals("Codes de triche · 2 actifs", CheatList.menuLabel(cheats));
        assertEquals("Codes de triche · 1 actif", CheatList.menuLabel(cheats.subList(0, 2)));
        assertEquals("Codes de triche", CheatList.menuLabel(cheats.subList(1, 2)));
    }
}
