package com.ravenemu.core.gb.cartridge

import com.ravenemu.core.gb.TestRoms
import com.ravenemu.emulation.api.RomLoadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lit le marqueur 16 bits écrit au début de chaque banque par TestRoms. */
private fun Cartridge.bankMarkerAt(address: Int): Int =
    readRom(address) or (readRom(address + 1) shl 8)

class MbcTest {

    // ---- Fabrique ----

    @Test
    fun `create choisit le bon controleur`() {
        assertTrue(Cartridge.create(TestRoms.build(type = 0x00)) is Mbc0)
        assertTrue(Cartridge.create(TestRoms.build(type = 0x01)) is Mbc1)
        assertTrue(Cartridge.create(TestRoms.build(type = 0x05)) is Mbc2)
        assertTrue(Cartridge.create(TestRoms.build(type = 0x11)) is Mbc3)
        assertTrue(Cartridge.create(TestRoms.build(type = 0x19)) is Mbc5)
        assertFailsWith<RomLoadException> {
            Cartridge.create(TestRoms.build(type = 0xFC))
        }
    }

    // ---- Mbc0 ----

    @Test
    fun `mbc0 rom fixe et ram non bancarisee`() {
        val cart = Cartridge.create(
            TestRoms.build(type = 0x08, ramSizeCode = 0x02) { rom ->
                rom[0x0000] = 0x11
                rom[0x7FFF] = 0x22
            }
        )
        assertEquals(0x11, cart.readRom(0x0000))
        assertEquals(0x22, cart.readRom(0x7FFF))
        cart.writeRam(0xA123, 0x5A)
        assertEquals(0x5A, cart.readRam(0xA123))
        assertTrue(cart.ramDirty)
    }

    // ---- Mbc1 ----

    @Test
    fun `mbc1 banque 0 lue comme banque 1`() {
        val cart = Cartridge.create(TestRoms.buildBankMarked(0x01, romSizeCode = 0x02))
        cart.writeControl(0x2000, 0x00)
        assertEquals(1, cart.bankMarkerAt(0x4000))
    }

    @Test
    fun `mbc1 commutation de banques`() {
        // 128 KiB = 8 banques.
        val cart = Cartridge.create(TestRoms.buildBankMarked(0x01, romSizeCode = 0x02))
        assertEquals(0, cart.bankMarkerAt(0x0000))
        for (bank in 1..7) {
            cart.writeControl(0x2000, bank)
            assertEquals(bank, cart.bankMarkerAt(0x4000), "banque $bank")
        }
        // Le masque replie les banques hors plage.
        cart.writeControl(0x2000, 0x0F)
        assertEquals(0x0F and 0x07, cart.bankMarkerAt(0x4000))
    }

    @Test
    fun `mbc1 bits hauts en mode avance pour grande ROM`() {
        // 1 MiB = 64 banques : le registre secondaire fournit les bits 5-6.
        val cart = Cartridge.create(TestRoms.buildBankMarked(0x01, romSizeCode = 0x05))
        cart.writeControl(0x2000, 0x01)
        cart.writeControl(0x4000, 0x01) // bits hauts = 1 → banque 33
        assertEquals(33, cart.bankMarkerAt(0x4000))
        // En mode avancé, la zone fixe 0x0000 est elle aussi décalée.
        cart.writeControl(0x6000, 0x01)
        assertEquals(32, cart.bankMarkerAt(0x0000))
    }

    @Test
    fun `mbc1 ram desactivee par defaut puis activee`() {
        val cart = Cartridge.create(TestRoms.build(type = 0x03, ramSizeCode = 0x03))
        assertEquals(0xFF, cart.readRam(0xA000))
        cart.writeRam(0xA000, 0x12)
        assertEquals(0xFF, cart.readRam(0xA000))

        cart.writeControl(0x0000, 0x0A)
        cart.writeRam(0xA000, 0x12)
        assertEquals(0x12, cart.readRam(0xA000))

        cart.writeControl(0x0000, 0x00)
        assertEquals(0xFF, cart.readRam(0xA000))
    }

    @Test
    fun `mbc1 banques de ram en mode avance`() {
        val cart = Cartridge.create(TestRoms.build(type = 0x03, ramSizeCode = 0x03))
        cart.writeControl(0x0000, 0x0A)
        cart.writeControl(0x6000, 0x01) // mode avancé
        for (bank in 0..3) {
            cart.writeControl(0x4000, bank)
            cart.writeRam(0xA000, 0x40 + bank)
        }
        for (bank in 0..3) {
            cart.writeControl(0x4000, bank)
            assertEquals(0x40 + bank, cart.readRam(0xA000), "banque RAM $bank")
        }
    }

    // ---- Mbc2 ----

    @Test
    fun `mbc2 selection de banque via bit 8 d'adresse`() {
        val cart = Cartridge.create(TestRoms.buildBankMarked(0x05, romSizeCode = 0x03))
        cart.writeControl(0x2100, 0x03) // bit 8 = 1 → banque
        assertEquals(3, cart.bankMarkerAt(0x4000))
        cart.writeControl(0x2100, 0x00) // banque 0 interdite → 1
        assertEquals(1, cart.bankMarkerAt(0x4000))
    }

    @Test
    fun `mbc2 ram de quartets avec echo`() {
        val cart = Cartridge.create(TestRoms.build(type = 0x06))
        cart.writeControl(0x2000, 0x0A) // bit 8 = 0 → activation RAM
        cart.writeRam(0xA000, 0xAB)
        // Seul le quartet bas est conservé, bits hauts lus à 1.
        assertEquals(0xFB, cart.readRam(0xA000))
        // La zone se répète tous les 512 octets.
        assertEquals(0xFB, cart.readRam(0xA200))
    }

    // ---- Mbc3 ----

    @Test
    fun `mbc3 banques rom 7 bits`() {
        val cart = Cartridge.create(TestRoms.buildBankMarked(0x11, romSizeCode = 0x04))
        cart.writeControl(0x2000, 0x00)
        assertEquals(1, cart.bankMarkerAt(0x4000))
        cart.writeControl(0x2000, 0x1F)
        assertEquals(0x1F, cart.bankMarkerAt(0x4000))
    }

    @Test
    fun `mbc3 banques de ram directes`() {
        val cart = Cartridge.create(TestRoms.build(type = 0x13, ramSizeCode = 0x03))
        cart.writeControl(0x0000, 0x0A)
        for (bank in 0..3) {
            cart.writeControl(0x4000, bank)
            cart.writeRam(0xA000, 0x30 + bank)
        }
        for (bank in 0..3) {
            cart.writeControl(0x4000, bank)
            assertEquals(0x30 + bank, cart.readRam(0xA000))
        }
    }

    @Test
    fun `mbc3 rtc verrouillage et progression`() {
        var now = 1_000_000L
        val cart = Cartridge.create(TestRoms.build(type = 0x10, ramSizeCode = 0x03)) { now }
        cart.writeControl(0x0000, 0x0A)

        // Verrouille à t0.
        cart.writeControl(0x6000, 0x00)
        cart.writeControl(0x6000, 0x01)
        cart.writeControl(0x4000, 0x08)
        val s0 = cart.readRam(0xA000)

        // 90 s plus tard, sans verrouillage, la lecture ne bouge pas.
        now += 90
        assertEquals(s0, cart.readRam(0xA000))

        // Nouveau verrouillage : 90 s écoulées → +1 min 30 s.
        cart.writeControl(0x6000, 0x00)
        cart.writeControl(0x6000, 0x01)
        assertEquals((s0 + 30) % 60, cart.readRam(0xA000))
        cart.writeControl(0x4000, 0x09)
        assertEquals(1, cart.readRam(0xA000))
    }

    @Test
    fun `mbc3 rtc arret via bit halt`() {
        var now = 5_000L
        val cart = Cartridge.create(TestRoms.build(type = 0x0F)) { now }
        cart.writeControl(0x0000, 0x0A)
        cart.writeControl(0x4000, 0x0C)
        cart.writeRam(0xA000, 0x40) // halt
        now += 3600
        cart.writeControl(0x6000, 0x00)
        cart.writeControl(0x6000, 0x01)
        cart.writeControl(0x4000, 0x08)
        assertEquals(0, cart.readRam(0xA000))
    }

    // ---- Mbc5 ----

    @Test
    fun `mbc5 banque 0 reellement selectionnable`() {
        val cart = Cartridge.create(TestRoms.buildBankMarked(0x19, romSizeCode = 0x03))
        cart.writeControl(0x2000, 0x00)
        assertEquals(0, cart.bankMarkerAt(0x4000))
    }

    @Test
    fun `mbc5 banque 9 bits`() {
        // 4 MiB = 256 banques ; le bit haut est requis au-delà de 255.
        val cart = Cartridge.create(TestRoms.buildBankMarked(0x19, romSizeCode = 0x07))
        cart.writeControl(0x2000, 0x2A)
        assertEquals(0x2A, cart.bankMarkerAt(0x4000))
        cart.writeControl(0x3000, 0x01)
        assertEquals(0x12A and 0xFF, cart.bankMarkerAt(0x4000) and 0xFF)
    }

    @Test
    fun `mbc5 banques de ram 4 bits`() {
        val cart = Cartridge.create(TestRoms.build(type = 0x1B, ramSizeCode = 0x04))
        cart.writeControl(0x0000, 0x0A)
        for (bank in 0..15) {
            cart.writeControl(0x4000, bank)
            cart.writeRam(0xA000, bank)
        }
        for (bank in 0..15) {
            cart.writeControl(0x4000, bank)
            assertEquals(bank, cart.readRam(0xA000))
        }
    }

    // ---- Pile / .sav ----

    @Test
    fun `export battery nul sans pile`() {
        val cart = Cartridge.create(TestRoms.build(type = 0x01))
        assertNull(cart.exportBattery())
    }

    @Test
    fun `export puis import battery conserve la ram`() {
        val cart = Cartridge.create(TestRoms.build(type = 0x03, ramSizeCode = 0x02))
        cart.writeControl(0x0000, 0x0A)
        cart.writeRam(0xA000, 0x77)
        cart.writeRam(0xBFFF, 0x66)
        val sav = cart.exportBattery()!!
        assertEquals(8 * 1024, sav.size)

        val restored = Cartridge.create(TestRoms.build(type = 0x03, ramSizeCode = 0x02))
        restored.importBattery(sav)
        restored.writeControl(0x0000, 0x0A)
        assertEquals(0x77, restored.readRam(0xA000))
        assertEquals(0x66, restored.readRam(0xBFFF))
    }

    @Test
    fun `sav mbc3 rtc contient le pied de 48 octets et se restaure`() {
        var now = 10_000L
        val cart = Cartridge.create(TestRoms.build(type = 0x10, ramSizeCode = 0x02)) { now }
        cart.writeControl(0x0000, 0x0A)
        cart.writeRam(0xA000, 0x42)
        val sav = cart.exportBattery()!!
        assertEquals(8 * 1024 + Mbc3.RTC_FOOTER_SIZE, sav.size)

        // Restauration 120 s plus tard : l'horloge doit avoir avancé.
        now += 120
        val restored =
            Cartridge.create(TestRoms.build(type = 0x10, ramSizeCode = 0x02)) { now }
        restored.importBattery(sav)
        restored.writeControl(0x0000, 0x0A)
        assertEquals(0x42, restored.readRam(0xA000))
        restored.writeControl(0x6000, 0x00)
        restored.writeControl(0x6000, 0x01)
        restored.writeControl(0x4000, 0x09)
        assertEquals(2, restored.readRam(0xA000))
    }
}
