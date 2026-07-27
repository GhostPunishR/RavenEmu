package com.ravenemu.emulation.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Stabilité des identifiants persistés.
 *
 * Les états instantanés Game Boy et Game Boy Advance écrivent la console sur un
 * octet. Tant que cet octet était `ordinal`, insérer une console dans
 * l'énumération — ou en retirer une — décalait silencieusement la valeur de
 * toutes les consoles suivantes : les états déjà enregistrés par les
 * utilisateurs auraient alors désigné une autre console, et le contrôle
 * « État issu d'une autre console » aurait laissé passer ce qu'il est censé
 * arrêter.
 *
 * Ces tests figent les valeurs. Toute modification de l'énumération qui
 * changerait un identifiant déjà attribué les fait échouer — c'est le but : la
 * réponse est d'attribuer une valeur neuve, jamais de réutiliser une ancienne.
 */
class ConsoleTypeTest {

    @Test
    fun `les identifiants persistes sont figes`() {
        assertEquals(0, ConsoleType.GAME_BOY.storageId)
        assertEquals(2, ConsoleType.GAME_BOY_ADVANCE.storageId)
    }

    @Test
    fun `un identifiant retire n'est jamais reattribue`() {
        // `1` a désigné `GAME_BOY_COLOR`, seconde entrée du même cœur Game Boy,
        // avant que la compatibilité de cartouche ne devienne une métadonnée.
        // Le réattribuer ferait accepter un ancien état pour une autre console.
        for (retire in ConsoleType.RETIRED_STORAGE_IDS) {
            assertNull(
                ConsoleType.fromStorageId(retire),
                "L'identifiant $retire a été retiré et ne doit désigner aucune console",
            )
        }
    }

    @Test
    fun `chaque console a un identifiant qui lui est propre`() {
        val identifiants = ConsoleType.entries.map { it.storageId }
        assertEquals(
            identifiants.size,
            identifiants.toSet().size,
            "Deux consoles partagent un identifiant : $identifiants",
        )
    }

    @Test
    fun `un identifiant retrouve sa console`() {
        for (console in ConsoleType.entries) {
            assertSame(console, ConsoleType.fromStorageId(console.storageId))
        }
    }

    @Test
    fun `un identifiant inconnu ne designe aucune console`() {
        assertNull(ConsoleType.fromStorageId(-1))
        assertNull(ConsoleType.fromStorageId(0xFF))
        assertNull(ConsoleType.fromStorageId(ConsoleType.entries.maxOf { it.storageId } + 1))
    }

    @Test
    fun `un identifiant tient sur l'octet ecrit dans les etats`() {
        for (console in ConsoleType.entries) {
            assertTrue(
                console.storageId in 0..255,
                "${console.name} : identifiant hors de l'octet du format d'état",
            )
        }
    }
}
