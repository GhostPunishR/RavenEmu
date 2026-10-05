package com.ravenemu.core.nds.cartridge

import com.ravenemu.emulation.api.RomLoadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lecture de l'en-tête de cartouche Nintendo DS.
 *
 * Cette lecture double celle du cœur natif, et c'est le risque qu'elle porte :
 * deux décodages du même en-tête peuvent diverger sans que rien ne le dise, et
 * la bibliothèque rangerait alors une cartouche que le moteur refuse — ou
 * l'inverse. Ce qui est éprouvé ici est donc ce que les deux doivent partager :
 * les mêmes champs, les mêmes refus, la même somme de contrôle.
 */
class NdsHeaderTest {

    @Test
    fun `les champs annonces par l'en-tete sont relus`() {
        val header = NdsHeader.parse(
            NdsCartridge.image(
                title = "RAVENEMU",
                gameCode = "ARVE",
                makerCode = "01",
                romVersion = 3,
                deviceCapacityCode = 5,
            )
        )
        assertEquals("RAVENEMU", header.title)
        assertEquals("ARVE", header.gameCode)
        assertEquals("01", header.makerCode)
        assertEquals(NdsUnitCode.NINTENDO_DS, header.unitCode)
        assertEquals(3, header.romVersion)
        assertEquals(5, header.deviceCapacityCode)
    }

    @Test
    fun `la somme de controle de l'en-tete est verifiee`() {
        assertTrue(NdsHeader.parse(NdsCartridge.image()).headerChecksumValid)
        // Une somme fausse est **rapportée**, non refusée : des ROM amateur
        // démarrent sur console sans somme correcte, et les rejeter
        // confondrait « en-tête inhabituel » et « fichier inexploitable ».
        val douteuse = NdsHeader.parse(NdsCartridge.image(validChecksum = false))
        assertFalse(douteuse.headerChecksumValid)
        assertEquals("RAVENEMU", douteuse.title)
    }

    @Test
    fun `la somme est celle du materiel`() {
        // Valeurs de référence du CRC-16 réfléchi de polynôme 0xA001 initialisé
        // à 0xFFFF, celui que la console applique à son en-tête. Deux octets
        // voisins donnent deux sommes sans rapport : c'est ce qui distingue un
        // vrai CRC d'une somme cumulée, et un octet nul ne le montrerait pas.
        assertEquals(0x807E, NdsHeader.crc16(byteArrayOf(0x01), 1))
        assertEquals(0x813E, NdsHeader.crc16(byteArrayOf(0x02), 1))
        // Une somme sur zéro octet reste la valeur initiale.
        assertEquals(0xFFFF, NdsHeader.crc16(ByteArray(0), 0))
        // La longueur est celle demandée, non celle du tableau : l'en-tête
        // couvre 0x15E octets d'une image bien plus longue.
        assertEquals(0x807E, NdsHeader.crc16(byteArrayOf(0x01, 0x02, 0x03), 1))
    }

    @Test
    fun `la capacite annoncee decrit la puce et non le fichier`() {
        val header = NdsHeader.parse(
            NdsCartridge.image(deviceCapacityCode = 7, sizeBytes = 0x8000)
        )
        // 128 Kio décalés de sept, soit 16 Mio, pour un fichier de 32 Kio :
        // les deux n'ont aucune raison de coïncider.
        assertEquals(16L * 1024L * 1024L, header.declaredCapacityBytes)
        assertEquals(0x8000, header.totalUsedRomSize)
    }

    @Test
    fun `une image trop courte est refusee`() {
        val erreur = assertFailsWith<RomLoadException> {
            NdsHeader.parse(ByteArray(NdsHeader.HEADER_SIZE - 1))
        }
        assertTrue("courte" in (erreur.message ?: ""), erreur.message ?: "")
    }

    @Test
    fun `une cartouche hybride DS et DSi est acceptee`() {
        assertEquals(
            NdsUnitCode.NINTENDO_DS_AND_DSI,
            NdsHeader.parse(NdsCartridge.image(unitCode = 0x02)).unitCode,
        )
    }

    @Test
    fun `une cartouche exclusivement DSi est refusee avec sa raison`() {
        // Elle suppose un autre processeur, une autre carte mémoire et des
        // périphériques que ce cœur ne fournit pas : démarrer à moitié serait
        // pire qu'un refus net.
        val erreur = assertFailsWith<RomLoadException> {
            NdsHeader.parse(NdsCartridge.image(unitCode = 0x03))
        }
        assertTrue("DSi" in (erreur.message ?: ""), erreur.message ?: "")
    }

    @Test
    fun `un code unite inconnu est refuse`() {
        assertFailsWith<RomLoadException> { NdsHeader.parse(NdsCartridge.image(unitCode = 0x7F)) }
    }

    @Test
    fun `un bloc de code vide est refuse`() {
        assertFailsWith<RomLoadException> { NdsHeader.parse(NdsCartridge.image(arm9Size = 0)) }
        assertFailsWith<RomLoadException> { NdsHeader.parse(NdsCartridge.image(arm7Size = 0)) }
    }

    @Test
    fun `un bloc de code hors du fichier est refuse`() {
        val erreur = assertFailsWith<RomLoadException> {
            NdsHeader.parse(NdsCartridge.image(arm9Size = 0x8000, sizeBytes = 0x8000))
        }
        assertTrue("hors de la ROM" in (erreur.message ?: ""), erreur.message ?: "")
    }

    @Test
    fun `un bloc de code recouvrant l'en-tete est refuse`() {
        val erreur = assertFailsWith<RomLoadException> {
            NdsHeader.parse(NdsCartridge.image(arm7Offset = 0x100))
        }
        assertTrue("recouvrant" in (erreur.message ?: ""), erreur.message ?: "")
    }

    @Test
    fun `une taille de bloc enorme ne passe pas pour une taille negative`() {
        // Les tailles sont lues sur trente-deux bits non signés : comparées en
        // entier signé, celle-ci vaudrait -16 et le bloc semblerait tenir.
        val erreur = assertFailsWith<RomLoadException> {
            NdsHeader.parse(NdsCartridge.image(arm9Size = -16))
        }
        assertTrue("hors de la ROM" in (erreur.message ?: ""), erreur.message ?: "")
    }

    @Test
    fun `un titre abime ne peut pas glisser de caracteres de controle`() {
        val rom = NdsCartridge.image(title = "JEU")
        rom[0x003] = 0x07 // cloche
        rom[0x004] = 0x1B // échappement
        val header = NdsHeader.parse(rom)
        assertEquals("JEU??", header.title)
    }
}
