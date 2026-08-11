package com.ravenemu.cheats

import com.ravenemu.emulation.cheats.CheatDefinition
import com.ravenemu.emulation.cheats.CheatFormat
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheatStoreTest {
    private val directory = Files.createTempDirectory("ravenemu-cheats-test")
    private val store = CheatStore(directory.toFile())
    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)

    @AfterTest
    fun cleanup() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `un fichier absent donne une liste vide`() {
        assertTrue(store.load(shaA).isEmpty())
    }

    @Test
    fun `nom codes et etat active sont conserves`() {
        val expected = listOf(
            CheatDefinition(
                id = "stable-id",
                name = "Argent infini",
                codes = listOf("010100C0", "01-02-01-C0"),
                format = CheatFormat.GAMESHARK_GB_GBC,
                enabled = true,
            )
        )
        assertTrue(store.save(shaA, expected))

        assertEquals(
            expected.map { it.copy(codes = listOf("010100C0", "010201C0")) },
            store.load(shaA),
        )
    }

    @Test
    fun `les cheats sont isoles par SHA-256`() {
        val first = cheat("jeu A", enabled = true)
        val second = CheatDefinition(
            id = "id-jeu-B",
            name = "jeu B",
            codes = listOf("cd93194f 089ce0b4", "raw 02000000 0000002a"),
            format = CheatFormat.GAMESHARK_GBA_V1_V2,
            enabled = false,
        )
        assertTrue(store.save(shaA, listOf(first)))
        assertTrue(store.save(shaB, listOf(second)))

        assertEquals(listOf(first), store.load(shaA))
        assertEquals(
            listOf(
                second.copy(
                    codes = listOf("CD93194F 089CE0B4\nRAW 02000000 0000002A")
                )
            ),
            store.load(shaB),
        )
    }

    @Test
    fun `un fichier corrompu est ignore proprement`() {
        directory.resolve("$shaA.json").toFile().writeText("{ ceci n'est pas du JSON")
        assertTrue(store.load(shaA).isEmpty())
    }

    @Test
    fun `un code invalide ne peut pas etre persiste`() {
        val invalid = cheat("invalide").copy(codes = listOf("01010XC0"))
        assertFalse(store.save(shaA, listOf(invalid)))
        assertTrue(store.load(shaA).isEmpty())
    }

    private fun cheat(name: String, enabled: Boolean = true) = CheatDefinition(
        id = "id-$name",
        name = name,
        codes = listOf("010100C0"),
        format = CheatFormat.GAMESHARK_GB_GBC,
        enabled = enabled,
    )
}
