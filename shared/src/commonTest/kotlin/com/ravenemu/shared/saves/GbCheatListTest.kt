package com.ravenemu.shared.saves

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GbCheatListTest {
    @Test
    fun aCodeTypedOverSeveralLinesComesOutOnOne() {
        assertEquals("82002A4C 0063 82002A4E 0001", GbCheatList.oneLine("  82002A4C 0063\r\n\t82002A4E   0001 \n"))
        assertTrue(GbCheatList.oneLine(" \n\t ").isEmpty())
    }

    @Test
    fun aListComesBackAsWritten() {
        val cheats = listOf(
            GbCheat("Argent infini", "82002A4C 0063\n82002A4E 0001", true),
            GbCheat("Nom\tavec tabulation", "010238CD", false),
            GbCheat("Vide", "  ", true),
        )
        val back = GbCheatList.parse(GbCheatList.format(cheats))
        assertEquals(
            listOf(
                GbCheat("Argent infini", "82002A4C 0063 82002A4E 0001", true),
                GbCheat("Nom avec tabulation", "010238CD", false),
            ),
            back,
            "An empty code is dropped; a tab in a name cannot split its line",
        )
        assertEquals(listOf("82002A4C 0063 82002A4E 0001"), GbCheatList.active(back))
    }

    @Test
    fun readsRavenEmuPcFilesAndSkipsJunk() {
        val text = "1\tArgent\t82002A4C 0063\ngarbage\r\n1\tCRLF\tABC-DEF-GHI\r\n0\tOff\t010238CD\n"
        assertEquals(
            listOf(
                GbCheat("Argent", "82002A4C 0063", true),
                GbCheat("CRLF", "ABC-DEF-GHI", true),
                GbCheat("Off", "010238CD", false),
            ),
            GbCheatList.parse(text),
        )
        assertTrue(GbCheatList.parse("").isEmpty())
        assertEquals("", GbCheatList.format(emptyList()))
    }
}
