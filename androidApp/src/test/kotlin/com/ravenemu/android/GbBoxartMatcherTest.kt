package com.ravenemu.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GbBoxartMatcherTest {
    private val index = listOf(
        BoxartRecord(
            "Pokemon - Version Bleue (France) (SGB Enhanced)",
            "Pokemon%20-%20Version%20Bleue%20%28France%29%20%28SGB%20Enhanced%29.png",
        ),
        BoxartRecord("Pokemon - Blue Version (USA, Europe) (SGB Enhanced)", "blue.png"),
        BoxartRecord("Pokemon - Version Rouge (France) (SGB Enhanced)", "red.png"),
        BoxartRecord("Tetris (Japan)", "tetris-japan.png"),
        BoxartRecord("Tetris (World)", "tetris-world.png"),
    )

    @Test
    fun matchesPokemonsExactFrenchBoxArt() {
        assertEquals(
            index[0], GbBoxartMatcher.bestMatch("Pokemon - Version Bleue (France).gb", index),
        )
    }

    @Test
    fun neverSubstitutesDifferentRegionOrColor() {
        assertNull(GbBoxartMatcher.bestMatch(
            "Pokemon - Version Bleue (Germany).gb", index,
        ))
        assertNull(GbBoxartMatcher.bestMatch(
            "Pokemon - Version Jaune (France).gb", index,
        ))
    }

    @Test
    fun prefersWorldEditionWhenSourceNameHasNoRegion() {
        assertEquals(index[4], GbBoxartMatcher.bestMatch("Tetris.gb", index))
    }

    @Test
    fun parserKeepsOnlySafeLocalPngLinks() {
        val html = """
            <a href="Pokemon%20-%20Version%20Bleue%20%28France%29.png">Cover</a>
            <a href="../bad.png">Bad</a>
            <a href="https://malicious.example/evil.png">Bad</a>
            <a href="test.jpg">Not a cover</a>
        """.trimIndent()
        val results = GbBoxartMatcher.parseIndex(html)
        assertEquals(1, results.size)
        assertTrue(results.first().title.contains("France"))
    }
}
