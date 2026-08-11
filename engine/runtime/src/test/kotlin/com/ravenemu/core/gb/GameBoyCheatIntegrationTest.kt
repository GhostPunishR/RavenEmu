package com.ravenemu.core.gb

import com.ravenemu.emulation.cheats.CheatCapableCore
import com.ravenemu.emulation.cheats.CheatCode
import com.ravenemu.emulation.cheats.CheatFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Kotlin -> JNI -> cœur C++ réellement livré, avec ROM synthétique originale. */
class GameBoyCheatIntegrationTest {
    private val frame = IntArray(160 * 144)
    private val activeCode = CheatCode(CheatFormat.GAMESHARK_GB_GBC, "002A00A0")

    @Test
    fun `une cartouche CGB applique et retire un GameShark a chaud`() {
        GameBoyCore().use { core ->
            core.loadRom(cheatRom(), null)
            val capability = core as CheatCapableCore
            assertTrue(capability.cheatSupport.supports(CheatFormat.GAMESHARK_GB_GBC))
            assertEquals(
                setOf(CheatFormat.GAMESHARK_GB_GBC),
                capability.cheatSupport.formats,
            )

            core.runFrame(frame)
            assertEquals(0x01, batteryByte(core, 0))

            capability.replaceActiveCheats(listOf(activeCode))
            core.runFrame(frame)
            assertEquals(0x2A, batteryByte(core, 0))

            capability.replaceActiveCheats(emptyList())
            core.runFrame(frame)
            assertEquals(0x01, batteryByte(core, 0))
        }
    }

    @Test
    fun `plusieurs lignes reset et save state conservent la configuration`() {
        GameBoyCore().use { core ->
            core.loadRom(cheatRom(), null)
            val capability = core as CheatCapableCore
            val codes = listOf(
                activeCode,
                CheatCode(CheatFormat.GAMESHARK_GB_GBC, "003B01A0"),
            )
            capability.replaceActiveCheats(codes)
            core.runFrame(frame)
            assertEquals(0x2A, batteryByte(core, 0))
            assertEquals(0x3B, batteryByte(core, 1))

            core.reset()
            core.runFrame(frame)
            assertEquals(0x2A, batteryByte(core, 0))

            capability.replaceActiveCheats(emptyList())
            core.runFrame(frame)
            val state = core.saveState()
            capability.replaceActiveCheats(codes)
            core.loadState(state)
            core.runFrame(frame)
            assertEquals(0x2A, batteryByte(core, 0))
            assertEquals(0x3B, batteryByte(core, 1))
        }
    }

    @Test
    fun `le coeur refuse le format natif malforme meme si Kotlin est contourne`() {
        GameBoyCore().use { core ->
            core.loadRom(cheatRom(), null)
            assertFailsWith<IllegalArgumentException> {
                core.replaceActiveCheats(
                    listOf(CheatCode(CheatFormat.GAMESHARK_GB_GBC, "910238CD"))
                )
            }
        }
    }

    @Test
    fun `une cartouche DMG n'annonce aucun cheat`() {
        GameBoyCore().use { core ->
            core.loadRom(TestRoms.build(), null)
            assertTrue(core.cheatSupport.formats.isEmpty())
            assertFailsWith<IllegalArgumentException> {
                core.replaceActiveCheats(listOf(activeCode))
            }
        }
    }

    @Test
    fun `le coeur CGB refuse le format GBA`() {
        GameBoyCore().use { core ->
            core.loadRom(cheatRom(), null)
            assertFailsWith<IllegalArgumentException> {
                core.replaceActiveCheats(
                    listOf(
                        CheatCode(
                            CheatFormat.GAMESHARK_GBA_V1_V2,
                            "CD93194F 089CE0B4",
                        )
                    )
                )
            }
        }
    }

    private fun batteryByte(core: GameBoyCore, offset: Int): Int =
        requireNotNull(core.snapshotBatteryRam()).data[offset].toInt() and 0xFF

    private fun cheatRom(): ByteArray = TestRoms.build(
        type = 0x09,
        ramSizeCode = 0x02,
        cgbFlag = 0x80,
    ) { rom ->
        val program = intArrayOf(
            0x3E, 0x01,       // LD A,01
            0xEA, 0x00, 0xA0, // LD (A000),A
            0x18, 0xF9,       // JR 0100
        )
        for ((index, opcode) in program.withIndex()) {
            rom[TestRoms.ENTRY_POINT + index] = opcode.toByte()
        }
    }
}
