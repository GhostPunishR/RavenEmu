package com.ravenemu.emulation.api.display

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MonochromeDisplayProfilesTest {

    private fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    @Test
    fun `cinq profils attendus dans l'ordre`() {
        assertEquals(
            listOf("dmg", "pocket", "light_off", "light_on", "black_white"),
            MonochromeDisplayProfiles.all.map { it.id },
        )
    }

    @Test
    fun `chaque profil contient exactement quatre couleurs`() {
        for (profile in MonochromeDisplayProfiles.all) {
            assertEquals(4, profile.colors.size, profile.id)
        }
    }

    @Test
    fun `chaque profil va du plus clair au plus sombre`() {
        for (profile in MonochromeDisplayProfiles.all) {
            val lums = profile.colors.map(::luminance)
            for (i in 1 until lums.size) {
                assertTrue(
                    lums[i] < lums[i - 1],
                    "${profile.id} : niveau $i (${lums[i]}) pas plus sombre que ${i - 1} (${lums[i - 1]})",
                )
            }
        }
    }

    @Test
    fun `toutes les couleurs sont opaques`() {
        for (profile in MonochromeDisplayProfiles.all) {
            for (c in profile.colors) {
                assertEquals(0xFF, (c ushr 24) and 0xFF, profile.id)
            }
        }
    }

    @Test
    fun `le profil par defaut est la Game Boy DMG`() {
        assertEquals("dmg", MonochromeDisplayProfiles.default.id)
        assertSame(MonochromeDisplayProfiles.DMG, MonochromeDisplayProfiles.default)
    }

    @Test
    fun `byId retrouve un profil et retombe sur le defaut sinon`() {
        assertEquals("pocket", MonochromeDisplayProfiles.byId("pocket").id)
        assertEquals("dmg", MonochromeDisplayProfiles.byId("inconnu").id)
        assertEquals("dmg", MonochromeDisplayProfiles.byId(null).id)
    }

    @Test
    fun `colors retourne une copie non mutable`() {
        val profile = MonochromeDisplayProfiles.DMG
        val a = profile.colors
        a[0] = 0
        assertTrue(profile.colors[0] != 0, "la palette interne a été modifiée")
    }

    @Test
    fun `DMG evite le blanc et le noir purs`() {
        val colors = MonochromeDisplayProfiles.DMG.colors
        assertTrue((colors.first() and 0xFFFFFF) != 0xFFFFFF)
        assertTrue((colors.last() and 0xFFFFFF) != 0x000000)
    }

    @Test
    fun `le profil noir et blanc utilise blanc et noir purs`() {
        val colors = MonochromeDisplayProfiles.BLACK_WHITE.colors
        assertEquals(0xFFFFFF, colors.first() and 0xFFFFFF)
        assertEquals(0x000000, colors.last() and 0xFFFFFF)
    }
}
