package com.ravenemu.shared

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {
    @Test
    fun defaultsPreserveValidatedBehavior() {
        val defaults = Settings()
        assertTrue(defaults.audioEnabled)
        assertEquals(100, defaults.audioVolume)
        assertEquals("original", defaults.videoPalette)
        assertEquals(0, defaults.videoBrightness)
        assertEquals(100, defaults.videoContrast)
        assertEquals(100, defaults.controlsScale)
        assertEquals(46, defaults.controlsGap)
        assertEquals("classic", defaults.controlsStyle)
        assertTrue(defaults.cartridgeRumble, "La vibration de cartouche est active par défaut")
        assertTrue(defaults.cartridgeSensors, "Les capteurs de cartouche sont actifs par défaut")
        assertTrue(defaults.solarLightAuto)
        assertEquals(50, defaults.solarLightLevel)

        val frame = intArrayOf(0xff102030.toInt(), 0xff8899aa.toInt())
        val reference = frame.copyOf()
        GbDisplayFilter.apply(frame, defaults)
        assertContentEquals(reference, frame)
    }

    /**
     * Chaque jaquette demandée indique à un serveur tiers un jeu de la
     * bibliothèque, avec l'adresse IP de l'appareil. La recherche doit donc
     * être un choix de l'utilisateur, jamais un comportement par défaut, y
     * compris pour des réglages relus ou normalisés.
     */
    @Test
    fun onlineCoversStayOffUnlessChosen() {
        assertFalse(Settings().onlineCovers)
        assertFalse(Settings().normalized().onlineCovers)
        assertTrue(Settings(onlineCovers = true).normalized().onlineCovers)
    }

    @Test
    fun importedPreferencesAreClamped() {
        val settings = Settings(
            audioVolume = 150, videoPalette = "unsupported",
            videoBrightness = -200, videoContrast = 400,
            controlsScale = 900, controlsGap = -5, controlsStyle = "unknown",
        ).normalized()
        assertEquals(100, settings.audioVolume)
        assertEquals("original", settings.videoPalette)
        assertEquals(-50, settings.videoBrightness)
        assertEquals(150, settings.videoContrast)
        assertEquals(115, settings.controlsScale)
        assertEquals(20, settings.controlsGap)
        assertEquals("classic", settings.controlsStyle)
    }

    @Test
    fun pocketPaletteProducesGrayWithoutDroppingAlpha() {
        val frame = intArrayOf(0x7f20a0ff.toInt())
        GbDisplayFilter.apply(frame, Settings(videoPalette = "pocket"))
        val color = frame.single()
        assertEquals(0x7f, (color ushr 24) and 255)
        assertEquals((color ushr 16) and 255, (color ushr 8) and 255)
        assertEquals((color ushr 8) and 255, color and 255)
    }

    @Test
    fun colorGamesKeepTheirColorsButFollowBrightness() {
        val red = 0xffc02010.toInt()
        val untouched = intArrayOf(red)
        GbDisplayFilter.apply(untouched, Settings(videoPalette = "dmg"), GamePlatform.GBC)
        assertEquals(red, untouched.single())

        val brighter = intArrayOf(red)
        GbDisplayFilter.apply(
            brighter, Settings(videoPalette = "pocket", videoBrightness = 10), GamePlatform.GBC,
        )
        assertEquals(0xffd43424.toInt(), brighter.single())
    }

    @Test
    fun lcdCorrectionTonesDownColourGamesOnlyWhenEnabled() {
        val red = 0xffff0000.toInt()
        val off = intArrayOf(red)
        GbDisplayFilter.apply(off, Settings(), GamePlatform.GBC)
        assertEquals(red, off.single(), "Désactivée par défaut")

        val gbc = intArrayOf(red)
        GbDisplayFilter.apply(gbc, Settings(lcdCorrectionGbc = true), GamePlatform.GBC)
        val r = (gbc.single() ushr 16) and 255
        val g = (gbc.single() ushr 8) and 255
        assertTrue(r < 255 && g > 0, "Rouge pur désaturé vers la luminance")
        assertEquals(0xff, (gbc.single() ushr 24) and 255)

        // Each console has its own switch, and Game Boy games never change.
        val gba = intArrayOf(red)
        GbDisplayFilter.apply(gba, Settings(lcdCorrectionGbc = true), GamePlatform.GBA)
        assertEquals(red, gba.single())
        val gb = intArrayOf(red)
        GbDisplayFilter.apply(gb, Settings(lcdCorrectionGbc = true, lcdCorrectionGba = true), GamePlatform.GB)
        assertEquals(red, gb.single())
    }

    @Test
    fun lcdCorrectionKeepsBlackAndWhite() {
        val frame = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt())
        GbDisplayFilter.apply(frame, Settings(lcdCorrectionGba = true), GamePlatform.GBA)
        assertEquals(listOf(0xff000000.toInt(), 0xffffffff.toInt()), frame.toList())
    }
}
