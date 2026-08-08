package com.ravenemu.core.gba.save

import com.ravenemu.core.gba.KotlinGbaCore
import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GbaSaveMemoryTest {

    /** ROM synthétique portant un marqueur de type de sauvegarde. */
    private fun romWithMarker(marker: String): ByteArray {
        val rom = SyntheticRom.build(sizeBytes = 4096)
        val bytes = marker.toByteArray(Charsets.US_ASCII)
        // Les marqueurs sont alignés sur 4 octets dans les ROM réelles.
        bytes.copyInto(rom, 0x400)
        return rom
    }

    // ---- Détection ----

    @Test
    fun `detecte chaque type de sauvegarde`() {
        assertEquals(GbaSaveType.SRAM, GbaSaveType.detect(romWithMarker("SRAM_V113")))
        assertEquals(GbaSaveType.FLASH_64K, GbaSaveType.detect(romWithMarker("FLASH_V126")))
        assertEquals(GbaSaveType.FLASH_64K, GbaSaveType.detect(romWithMarker("FLASH512_V130")))
        assertEquals(GbaSaveType.FLASH_128K, GbaSaveType.detect(romWithMarker("FLASH1M_V102")))
        assertEquals(GbaSaveType.EEPROM_512, GbaSaveType.detect(romWithMarker("EEPROM_V122")))
    }

    @Test
    fun `sans marqueur aucun type n'est detecte`() {
        assertEquals(GbaSaveType.NONE, GbaSaveType.detect(SyntheticRom.build()))
        val core = KotlinGbaCore()
        core.loadRom(SyntheticRom.build())
        assertFalse(core.hasBatteryRam)
        assertNull(core.snapshotBatteryRam())
    }

    @Test
    fun `le type force prime sur la detection`() {
        val core = KotlinGbaCore(forcedSaveType = GbaSaveType.FLASH_128K)
        core.loadRom(romWithMarker("SRAM_V113"))
        assertEquals(GbaSaveType.FLASH_128K, core.saveType)
    }

    // ---- SRAM ----

    @Test
    fun `la SRAM conserve les octets ecrits`() {
        val m = GbaMachine(romWithMarker("SRAM_V113"))
        m.bus.write8(0x0E00_0000, 0x42)
        m.bus.write8(0x0E00_0001, 0x43)
        assertEquals(0x42, m.bus.read8(0x0E00_0000))
        assertEquals(0x43, m.bus.read8(0x0E00_0001))
        assertTrue(m.cartridge.save!!.dirty)
    }

    // ---- Flash ----

    /** Envoie la séquence de déverrouillage puis une commande Flash. */
    private fun flashCommand(m: GbaMachine, command: Int) {
        m.bus.write8(0x0E00_5555, 0xAA)
        m.bus.write8(0x0E00_2AAA, 0x55)
        m.bus.write8(0x0E00_5555, command)
    }

    @Test
    fun `la Flash vierge vaut 0xFF`() {
        val m = GbaMachine(romWithMarker("FLASH_V126"))
        assertEquals(0xFF, m.bus.read8(0x0E00_0000))
    }

    @Test
    fun `la Flash ecrit un octet apres la commande dediee`() {
        val m = GbaMachine(romWithMarker("FLASH_V126"))
        flashCommand(m, 0xA0)
        m.bus.write8(0x0E00_0010, 0x5A)
        assertEquals(0x5A, m.bus.read8(0x0E00_0010))
    }

    @Test
    fun `une ecriture Flash sans commande est ignoree`() {
        val m = GbaMachine(romWithMarker("FLASH_V126"))
        m.bus.write8(0x0E00_0010, 0x5A)
        assertEquals(0xFF, m.bus.read8(0x0E00_0010), "la Flash exige une commande")
    }

    @Test
    fun `le mode identification expose le constructeur et le modele`() {
        val m = GbaMachine(romWithMarker("FLASH1M_V102"))
        flashCommand(m, 0x90)
        assertEquals(0x62, m.bus.read8(0x0E00_0000))
        assertEquals(0x13, m.bus.read8(0x0E00_0001))
        flashCommand(m, 0xF0)
        assertEquals(0xFF, m.bus.read8(0x0E00_0000), "sortie du mode identification")
    }

    @Test
    fun `l'effacement total remet la Flash a 0xFF`() {
        val m = GbaMachine(romWithMarker("FLASH_V126"))
        flashCommand(m, 0xA0)
        m.bus.write8(0x0E00_0010, 0x5A)
        flashCommand(m, 0x80)
        flashCommand(m, 0x10)
        assertEquals(0xFF, m.bus.read8(0x0E00_0010))
    }

    @Test
    fun `la Flash 128 Kio commute de banc`() {
        val m = GbaMachine(romWithMarker("FLASH1M_V102"))
        flashCommand(m, 0xA0)
        m.bus.write8(0x0E00_0000, 0x11) // banc 0
        flashCommand(m, 0xB0)
        m.bus.write8(0x0E00_0000, 0x01) // bascule vers le banc 1
        flashCommand(m, 0xA0)
        m.bus.write8(0x0E00_0000, 0x22) // banc 1
        assertEquals(0x22, m.bus.read8(0x0E00_0000))

        flashCommand(m, 0xB0)
        m.bus.write8(0x0E00_0000, 0x00) // retour au banc 0
        assertEquals(0x11, m.bus.read8(0x0E00_0000))
    }

    // ---- EEPROM ----

    /** Envoie une suite de bits sur le port série EEPROM. */
    private fun sendBits(m: GbaMachine, bits: String) {
        for (bit in bits) m.bus.write16(0x0D00_0000, if (bit == '1') 1 else 0)
    }

    @Test
    fun `l'EEPROM restitue les donnees ecrites`() {
        val m = GbaMachine(romWithMarker("EEPROM_V122"))
        val eeprom = assertIs<GbaSaveMemory.Eeprom>(m.cartridge.save)

        // Écriture : "10" + adresse 6 bits (0) + 64 bits de données + bit d'arrêt.
        val payload = "0000000100000010000000110000010000000101000001100000011100001000"
        sendBits(m, "10" + "000000" + payload + "0")

        // Lecture : "11" + adresse + bit d'arrêt, puis 4 bits nuls et 64 bits.
        sendBits(m, "11" + "000000" + "0")
        val readBits = StringBuilder()
        repeat(68) { readBits.append(m.bus.read16(0x0D00_0000) and 1) }
        assertEquals(payload, readBits.substring(4), "les 64 bits doivent être restitués")
        assertTrue(eeprom.dirty)
    }

    @Test
    fun `la longueur du transfert DMA fixe la largeur d'adresse`() {
        val m = GbaMachine(romWithMarker("EEPROM_V122"))
        val eeprom = assertIs<GbaSaveMemory.Eeprom>(m.cartridge.save)
        // 17 mots = adresse 14 bits (EEPROM 8 Kio).
        eeprom.hintTransferLength(17)
        sendBits(m, "11" + "00000000000001" + "0")
        // La requête a été acceptée : la lecture débite des bits sans erreur.
        repeat(68) { m.bus.read16(0x0D00_0000) }
    }

    // ---- Intégration `.sav` ----

    @Test
    fun `le contenu de sauvegarde survit a un reset et s'exporte`() {
        val core = KotlinGbaCore()
        core.loadRom(romWithMarker("SRAM_V113"))
        core.machine!!.bus.write8(0x0E00_0000, 0x7E)
        assertTrue(core.batteryRamDirty)

        val snapshot = core.snapshotBatteryRam()!!
        assertEquals(0x7E, snapshot.data[0].toInt() and 0xFF)
        assertTrue(core.batteryRamDirty, "l'instantané seul ne doit rien acquitter")
        core.acknowledgeBatteryRamSaved(snapshot.generation)
        assertFalse(core.batteryRamDirty, "l'acquittement confirme la sauvegarde")

        core.reset()
        assertEquals(0x7E, core.machine!!.bus.read8(0x0E00_0000), "la pile survit au reset")
    }

    @Test
    fun `un sav importe au chargement est restitue`() {
        val saved = ByteArray(32 * 1024).also { it[5] = 0x33 }
        val core = KotlinGbaCore()
        core.loadRom(romWithMarker("SRAM_V113"), saved)
        assertEquals(0x33, core.machine!!.bus.read8(0x0E00_0005))
    }

    @Test
    fun `la sauvegarde est incluse dans les etats instantanes`() {
        val core = KotlinGbaCore()
        core.loadRom(romWithMarker("SRAM_V113"))
        core.machine!!.bus.write8(0x0E00_0000, 0x21)
        val state = core.saveState()

        core.machine!!.bus.write8(0x0E00_0000, 0x00)
        core.loadState(state)
        assertEquals(0x21, core.machine!!.bus.read8(0x0E00_0000))
    }
}
