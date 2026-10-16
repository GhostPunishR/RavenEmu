package com.ravenemu.android

import com.ravenemu.shared.GameEntry
import com.ravenemu.shared.GamePlatform
import com.ravenemu.shared.LinkCable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCableTest {
    private val library = listOf(
        GameEntry("ruby", "Pokémon Rubis", GamePlatform.GBA),
        GameEntry("red", "Pokémon Rouge", GamePlatform.GB),
        GameEntry("sapphire", "Pokémon Saphir", GamePlatform.GBA),
        GameEntry("crystal", "Pokémon Cristal", GamePlatform.GBC),
    )

    @Test
    fun gameBoyAndGameBoyColorLinkTogetherButNotWithAdvance() {
        assertTrue(LinkCable.compatible(GamePlatform.GB, GamePlatform.GBC))
        assertTrue(LinkCable.compatible(GamePlatform.GBC, GamePlatform.GBC))
        assertTrue(LinkCable.compatible(GamePlatform.GBA, GamePlatform.GBA))
        assertFalse(LinkCable.compatible(GamePlatform.GB, GamePlatform.GBA))
        assertFalse(LinkCable.compatible(GamePlatform.GBA, GamePlatform.GBC))
    }

    @Test
    fun partnersExcludeTheGameItselfAndOtherFamilies() {
        assertEquals(listOf("sapphire"), LinkCable.partners(library[0], library).map { it.id })
        assertEquals(listOf("crystal"), LinkCable.partners(library[1], library).map { it.id })
        assertEquals(emptyList<String>(), LinkCable.partners(library[0], listOf(library[0])).map { it.id })
    }
}
