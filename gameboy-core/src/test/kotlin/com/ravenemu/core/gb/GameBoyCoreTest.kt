package com.ravenemu.core.gb

import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.RomLoadException
import com.ravenemu.emulation.api.SaveStateException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GameBoyCoreTest {

    /** ROM inoffensive : boucle infinie JR -2 à l'entrée. */
    private fun idleRom(type: Int = 0x00, ramSizeCode: Int = 0x00): ByteArray =
        TestRoms.build(type = type, ramSizeCode = ramSizeCode) { rom ->
            rom[0x0100] = 0x18
            rom[0x0101] = 0xFE.toByte()
        }

    private fun loadedCore(rom: ByteArray = idleRom()): GameBoyCore =
        GameBoyCore(clock = { 0L }).apply { loadRom(rom) }

    @Test
    fun `caracteristiques video conformes DMG`() {
        val core = GameBoyCore()
        assertEquals(160, core.video.width)
        assertEquals(144, core.video.height)
        assertEquals(59.7275, core.video.refreshRateHz, 0.001)
    }

    @Test
    fun `runFrame sans rom leve une erreur`() {
        assertFailsWith<IllegalStateException> {
            GameBoyCore().runFrame(IntArray(160 * 144))
        }
    }

    @Test
    fun `rom invalide rejetee`() {
        assertFailsWith<RomLoadException> { GameBoyCore().loadRom(ByteArray(100)) }
    }

    @Test
    fun `le framebuffer contient des niveaux monochromes`() {
        val core = loadedCore()
        assertEquals(
            com.ravenemu.emulation.api.FramebufferFormat.INDEXED_4,
            core.framebufferFormat,
        )
        val frame = IntArray(core.video.pixelCount)
        core.runFrame(frame)
        // Aucune colorisation : uniquement des niveaux 0..3, jamais d'ARGB.
        assertTrue(frame.all { it in 0..3 })
        // VRAM vierge + BGP 0xFC → niveau 0 partout.
        assertTrue(frame.all { it == 0 })
    }

    @Test
    fun `framebuffer trop petit refuse`() {
        val core = loadedCore()
        assertFailsWith<IllegalArgumentException> { core.runFrame(IntArray(100)) }
    }

    @Test
    fun `les boutons atteignent le registre joypad`() {
        val core = loadedCore()
        core.setButton(EmulatorButton.START, true)
        val m = assertNotNull(core.machine)
        m.bus.write(0xFF00, 0x10) // sélection groupe actions
        assertEquals(0b0111, m.bus.read(0xFF00) and 0x0F)
    }

    @Test
    fun `ram a pile exportee et acquittee`() {
        val core = loadedCore(idleRom(type = 0x03, ramSizeCode = 0x02))
        assertTrue(core.hasBatteryRam)
        assertFalse(core.batteryRamDirty)
        val m = assertNotNull(core.machine)
        m.bus.write(0x0000, 0x0A)
        m.bus.write(0xA000, 0x42)
        assertTrue(core.batteryRamDirty)
        val sav = assertNotNull(core.exportBatteryRam())
        assertEquals(0x42, sav[0].toInt() and 0xFF)
        assertFalse(core.batteryRamDirty)
    }

    @Test
    fun `sav restaure au chargement`() {
        val sav = ByteArray(8 * 1024) { (it and 0xFF).toByte() }
        val core = GameBoyCore(clock = { 0L })
        core.loadRom(idleRom(type = 0x03, ramSizeCode = 0x02), sav)
        val m = assertNotNull(core.machine)
        m.bus.write(0x0000, 0x0A)
        assertEquals(0x01, m.bus.read(0xA001))
        assertEquals(0x7F, m.bus.read(0xA07F))
    }

    @Test
    fun `reset conserve la ram a pile`() {
        val core = loadedCore(idleRom(type = 0x03, ramSizeCode = 0x02))
        val m = assertNotNull(core.machine)
        m.bus.write(0x0000, 0x0A)
        m.bus.write(0xA000, 0x99)
        core.reset()
        val m2 = assertNotNull(core.machine)
        m2.bus.write(0x0000, 0x0A)
        assertEquals(0x99, m2.bus.read(0xA000))
        assertEquals(0x0100, m2.cpu.pc) // machine repartie du boot
    }

    @Test
    fun `execution deterministe`() {
        val frame1 = IntArray(160 * 144)
        val frame2 = IntArray(160 * 144)
        val core1 = loadedCore()
        val core2 = loadedCore()
        repeat(3) {
            core1.runFrame(frame1)
            core2.runFrame(frame2)
        }
        assertContentEquals(frame1, frame2)
        assertContentEquals(core1.saveState(), core2.saveState())
    }

    @Test
    fun `etat instantane aller-retour`() {
        val core = loadedCore()
        val frame = IntArray(core.video.pixelCount)
        core.runFrame(frame)
        val state = core.saveState()

        // L'état se re-sérialise à l'identique après restauration.
        core.runFrame(frame)
        core.runFrame(frame)
        core.loadState(state)
        assertContentEquals(state, core.saveState())
    }

    @Test
    fun `etat d'une autre rom refuse`() {
        val core = loadedCore()
        val other = loadedCore(idleRom(type = 0x03, ramSizeCode = 0x02))
        val state = other.saveState()
        assertFailsWith<SaveStateException> { core.loadState(state) }
    }

    @Test
    fun `etat corrompu refuse`() {
        val core = loadedCore()
        val state = core.saveState()
        assertFailsWith<SaveStateException> {
            core.loadState(state.copyOf(state.size / 2))
        }
        assertFailsWith<SaveStateException> { core.loadState(ByteArray(10)) }
    }

    @Test
    fun `magic invalide refuse`() {
        val core = loadedCore()
        val state = core.saveState()
        state[0] = 'X'.code.toByte()
        assertFailsWith<SaveStateException> { core.loadState(state) }
    }

    @Test
    fun `une trame produit des echantillons audio`() {
        val core = loadedCore()
        val frame = IntArray(core.video.pixelCount)
        core.runFrame(frame)
        val audio = ShortArray(4096)
        val count = core.readAudio(audio)
        assertTrue(count in 1096..1098, "obtenu $count")
        // ROM inerte : aucun canal déclenché, silence attendu.
        assertTrue((0 until count).all { audio[it] == 0.toShort() })
    }

    @Test
    fun `vblank cadence par trame`() {
        val core = loadedCore()
        val m = assertNotNull(core.machine)
        val frame = IntArray(core.video.pixelCount)
        m.ppu.frameReady = false
        core.runFrame(frame)
        assertTrue(m.ppu.frameReady)
    }
}
