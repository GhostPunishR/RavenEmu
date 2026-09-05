package com.ravenemu.romlibrary

import com.ravenemu.core.gb.cartridge.GameBoyCartridgeMode
import com.ravenemu.emulation.api.ConsoleType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Filtres de bibliothèque : la distinction Game Boy / Game Boy Color y est une
 * propriété de la cartouche, pas une console à part. Filtrer sur « Game Boy »
 * doit donc montrer la gamme entière, et non les seules cartouches monochromes.
 */
class LibraryFilterTest {

    private fun entree(
        nom: String,
        console: ConsoleType = ConsoleType.GAME_BOY,
        mode: GameBoyCartridgeMode? = null,
    ) = RomEntry(
        uri = "content://$nom",
        fileName = nom,
        sizeBytes = 32768,
        lastModified = 0,
        console = console,
        title = nom,
        fingerprints = Fingerprints("0", "0", "0"),
        cartridgeMode = mode,
    )

    private val monochrome = entree("tetris.gb", mode = GameBoyCartridgeMode.DMG_ONLY)
    private val compatible = entree("zelda.gbc", mode = GameBoyCartridgeMode.DMG_AND_CGB)
    private val couleurSeule = entree("cristal.gbc", mode = GameBoyCartridgeMode.CGB_ONLY)
    private val advance = entree("emeraude.gba", console = ConsoleType.GAME_BOY_ADVANCE)
    private val toutes = listOf(monochrome, compatible, couleurSeule, advance)

    private fun noms(filtre: String) = LibraryFilter.apply(toutes, filtre).map { it.fileName }

    @Test
    fun `sans filtre la bibliotheque est entiere`() {
        assertEquals(toutes.map { it.fileName }, noms(LibraryFilter.ALL))
    }

    @Test
    fun `le filtre Game Boy couvre la gamme entiere`() {
        assertEquals(
            listOf("tetris.gb", "zelda.gbc", "cristal.gbc"),
            noms(ConsoleType.GAME_BOY.name),
        )
    }

    @Test
    fun `le filtre couleur retient les cartouches a fonctions couleur`() {
        assertEquals(
            listOf("zelda.gbc", "cristal.gbc"),
            noms(LibraryFilter.GAME_BOY_COLOR_CARTRIDGES),
            "Une cartouche 0x80 est compatible couleur : elle appartient au filtre",
        )
    }

    @Test
    fun `le filtre monochrome exclut les cartouches couleur`() {
        assertEquals(
            listOf("tetris.gb"),
            noms(LibraryFilter.GAME_BOY_MONOCHROME_CARTRIDGES),
        )
    }

    @Test
    fun `le filtre Game Boy Advance ignore la gamme Game Boy`() {
        assertEquals(listOf("emeraude.gba"), noms(ConsoleType.GAME_BOY_ADVANCE.name))
    }

    @Test
    fun `l'ancien filtre GAME_BOY_COLOR devient un filtre de cartouche`() {
        // Enregistré par une version où la Game Boy Color était une console.
        assertEquals(
            noms(LibraryFilter.GAME_BOY_COLOR_CARTRIDGES),
            noms("GAME_BOY_COLOR"),
        )
    }

    @Test
    fun `un filtre inconnu affiche tout plutot qu'une liste vide`() {
        // Un réglage abîmé ou venu d'une version future ne doit pas donner une
        // bibliothèque vide, que l'utilisateur croirait perdue.
        assertEquals(toutes.map { it.fileName }, noms("SUPER_NINTENDO"))
        assertEquals(toutes.map { it.fileName }, noms(""))
    }

    @Test
    fun `une entree sans mode connu n'est ni couleur ni monochrome declaree`() {
        // Entrée d'un index antérieur : le mode sera relu au prochain balayage.
        val ancienne = entree("inconnu.gb", mode = null)
        val liste = listOf(ancienne)
        assertEquals(
            listOf("inconnu.gb"),
            LibraryFilter.apply(liste, ConsoleType.GAME_BOY.name).map { it.fileName },
            "Elle reste visible dans la gamme Game Boy",
        )
        assertEquals(
            emptyList(),
            LibraryFilter.apply(liste, LibraryFilter.GAME_BOY_COLOR_CARTRIDGES).map { it.fileName },
            "Faute d'information, elle n'est pas annoncée comme couleur",
        )
    }
}
