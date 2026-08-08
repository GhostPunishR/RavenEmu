package com.ravenemu.core.gba

import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.FramebufferFormat
import com.ravenemu.emulation.api.RomLoadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KotlinGbaCoreTest {

    private fun redBackdropRom() =
        SyntheticRom.build(programWords = SyntheticRom.backdropProgram(0x1F)) // BGR555 rouge pur

    @Test
    fun `caracteristiques video de la GBA`() {
        val core = KotlinGbaCore()
        assertEquals(ConsoleType.GAME_BOY_ADVANCE, core.console)
        assertEquals(240, core.video.width)
        assertEquals(160, core.video.height)
        assertEquals(FramebufferFormat.ARGB_8888, core.framebufferFormat)
        assertTrue(core.video.refreshRateHz in 59.5..60.0)
    }

    @Test
    fun `une ROM sans marqueur GBA est refusee`() {
        val core = KotlinGbaCore()
        assertFailsWith<RomLoadException> { core.loadRom(ByteArray(1024)) }
    }

    @Test
    fun `runFrame produit une image 240x160 d'une couleur unie`() {
        val core = KotlinGbaCore()
        core.loadRom(redBackdropRom())
        val framebuffer = IntArray(core.video.pixelCount)
        core.runFrame(framebuffer)

        val expected = 0xFFFF0000.toInt() // ARGB rouge opaque
        assertEquals(expected, framebuffer[0])
        assertTrue(framebuffer.all { it == expected }, "tous les pixels doivent être identiques")
    }

    @Test
    fun `runFrame sans ROM echoue`() {
        val core = KotlinGbaCore()
        assertFailsWith<IllegalStateException> { core.runFrame(IntArray(240 * 160)) }
    }

    @Test
    fun `un framebuffer trop petit est refuse`() {
        val core = KotlinGbaCore()
        core.loadRom(redBackdropRom())
        assertFailsWith<IllegalArgumentException> { core.runFrame(IntArray(100)) }
    }

    @Test
    fun `reset relance le meme programme`() {
        val core = KotlinGbaCore()
        core.loadRom(redBackdropRom())
        val framebuffer = IntArray(core.video.pixelCount)
        core.runFrame(framebuffer)
        core.reset()
        core.runFrame(framebuffer)
        assertEquals(0xFFFF0000.toInt(), framebuffer[0])
    }

    @Test
    fun `pas d'audio ni de RAM a pile dans ce premier lot`() {
        val core = KotlinGbaCore()
        core.loadRom(redBackdropRom())
        assertEquals(0, core.readAudio(ShortArray(64)))
        assertEquals(false, core.hasBatteryRam)
        assertEquals(null, core.snapshotBatteryRam())
    }
}
