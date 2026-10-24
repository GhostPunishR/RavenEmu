package com.ravenemu.android.player

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class GbScreenshotTest {
    private val at = LocalDateTime.of(2026, 10, 24, 22, 10, 5)

    @Test fun namesTheCaptureAfterTheGameAndTheTime() {
        assertEquals("Pokémon Émeraude 2026-10-24 22.10.05.png", GbScreenshot.fileName("Pokémon Émeraude.gba", at))
        assertEquals("Tetris 2026-10-24 22.10.05.png", GbScreenshot.fileName("Tetris.gb", at))
    }

    @Test fun dropsWhatAFileSystemRefuses() {
        assertEquals("Zelda  Oracle 2026-10-24 22.10.05.png", GbScreenshot.fileName("Zelda: Oracle.gbc", at))
        assertEquals("a b 2026-10-24 22.10.05.png", GbScreenshot.fileName("a/b.gb", at))
        assertEquals("RavenEmu 2026-10-24 22.10.05.png", GbScreenshot.fileName("???.gba", at))
        assertEquals(".gba 2026-10-24 22.10.05.png", GbScreenshot.fileName(".gba", at))
        assertEquals(80 + " 2026-10-24 22.10.05.png".length, GbScreenshot.fileName("x".repeat(200) + ".gb", at).length)
    }
}
