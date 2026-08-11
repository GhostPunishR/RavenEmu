package com.ravenemu.app.emulation

import com.ravenemu.emulation.cheats.CheatFormat
import com.ravenemu.emulation.cheats.CheatSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmulatorMenuPolicyTest {
    @Test
    fun `cheats est absent sans capability`() {
        assertFalse(EmulatorMenuAction.CHEATS in EmulatorMenuPolicy.actions(null))
        assertFalse(
            EmulatorMenuAction.CHEATS in EmulatorMenuPolicy.actions(CheatSupport(emptySet()))
        )
    }

    @Test
    fun `cheats apparait quand le coeur annonce un format`() {
        val support = CheatSupport(setOf(CheatFormat.GAMESHARK_GB_GBC))
        assertTrue(EmulatorMenuAction.CHEATS in EmulatorMenuPolicy.actions(support))

        val gbaSupport = CheatSupport(setOf(CheatFormat.GAMESHARK_GBA_V1_V2))
        assertTrue(EmulatorMenuAction.CHEATS in EmulatorMenuPolicy.actions(gbaSupport))
    }

    @Test
    fun `le spinner ne melange pas les formats annonces par les coeurs`() {
        assertEquals(
            listOf(CheatFormat.GAMESHARK_GB_GBC),
            EmulatorMenuPolicy.cheatFormats(
                CheatSupport(setOf(CheatFormat.GAMESHARK_GB_GBC))
            ),
        )
        assertEquals(
            listOf(CheatFormat.GAMESHARK_GBA_V1_V2),
            EmulatorMenuPolicy.cheatFormats(
                CheatSupport(setOf(CheatFormat.GAMESHARK_GBA_V1_V2))
            ),
        )
    }
}
