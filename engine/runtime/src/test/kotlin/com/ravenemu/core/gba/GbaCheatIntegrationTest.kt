package com.ravenemu.core.gba

import com.ravenemu.emulation.api.session.EmulationSession
import com.ravenemu.emulation.cheats.CheatCapableCore
import com.ravenemu.emulation.cheats.CheatCode
import com.ravenemu.emulation.cheats.CheatCodeListParseResult
import com.ravenemu.emulation.cheats.CheatFormat
import com.ravenemu.emulation.cheats.CheatParserRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Kotlin -> session -> JNI -> cœur C++ GBA livré, avec ROM synthétique originale. */
class GbaCheatIntegrationTest {
    private val frame = IntArray(240 * 160)

    @Test
    fun `GbaCore expose uniquement GameShark GBA v1 v2 apres chargement`() {
        GbaCore().use { core ->
            val capability = assertIs<CheatCapableCore>(core)
            assertTrue(capability.cheatSupport.formats.isEmpty())

            core.loadRom(cheatRom(), null)
            assertEquals(
                setOf(CheatFormat.GAMESHARK_GBA_V1_V2),
                capability.cheatSupport.formats,
            )
        }
    }

    @Test
    fun `un code GameShark publie traverse parser session JNI et coeur reel`() {
        GbaCore().use { core ->
            core.loadRom(cheatRom(), null)
            val parsed = assertIs<CheatCodeListParseResult.Success>(
                CheatParserRegistry.DEFAULT.parseLines(
                    CheatFormat.GAMESHARK_GBA_V1_V2,
                    "CD93194F 089CE0B4",
                )
            )

            val commandApplied = CountDownLatch(1)
            val frameProduced = CountDownLatch(1)
            val stateCaptured = CountDownLatch(1)
            val state = AtomicReference<ByteArray>()
            val session = EmulationSession(
                core = core,
                callbacks = object : EmulationSession.Callbacks {
                    override fun onFrame(framebuffer: IntArray) {
                        frameProduced.countDown()
                    }

                    override fun onStats(fps: Double, frameTimeMs: Double) = Unit
                    override fun onBatterySave(data: ByteArray): Boolean = true
                },
            )
            session.pause()
            session.start()
            try {
                session.post { loadedCore ->
                    (loadedCore as CheatCapableCore).replaceActiveCheats(parsed.codes)
                    commandApplied.countDown()
                }
                assertTrue(commandApplied.await(2, TimeUnit.SECONDS))
                session.resume()
                assertTrue(frameProduced.await(2, TimeUnit.SECONDS))
                session.pause()
                session.post { loadedCore ->
                    state.set(loadedCore.saveState())
                    stateCaptured.countDown()
                }
                assertTrue(stateCaptured.await(2, TimeUnit.SECONDS))
                assertEquals(0x2F, state.get()[IWRAM_STATE_OFFSET + 0x1C88].toInt() and 0xFF)
            } finally {
                assertEquals(EmulationSession.StopResult.CLEAN, session.stop())
            }
        }
    }

    @Test
    fun `ecritures conditions activation et desactivation agissent a chaud`() {
        GbaCore().use { core ->
            core.loadRom(cheatRom(), null)
            core.runFrame(frame)
            assertEquals(0x11, ewramByte(core, 0))

            val parsed = parse(
                "RAW 12000010 00001234\n" +
                    "RAW D2000010 00001234\n" +
                    "RAW 02000000 0000002A\n" +
                    "RAW 12000002 0000BEEF\n" +
                    "RAW 22000004 12345678",
            )
            core.replaceActiveCheats(parsed.codes)
            core.runFrame(frame)
            assertEquals(0x2A, ewramByte(core, 0))
            assertEquals(0xEF, ewramByte(core, 2))
            assertEquals(0xBE, ewramByte(core, 3))
            assertEquals(0x78, ewramByte(core, 4))
            assertEquals(0x12, ewramByte(core, 7))

            core.replaceActiveCheats(emptyList())
            core.runFrame(frame)
            assertEquals(0x11, ewramByte(core, 0))
        }
    }

    @Test
    fun `reset save state et frame skipping conservent GameShark actif`() {
        GbaCore().use { core ->
            core.loadRom(cheatRom(), null)
            val code = gameSharkCode("RAW 02000000 0000002A")
            core.replaceActiveCheats(listOf(code))

            core.reset()
            core.runFrame(frame, renderVideo = false)
            assertEquals(0x2A, ewramByte(core, 0))

            core.replaceActiveCheats(emptyList())
            core.runFrame(frame)
            val stateWithoutCheatValue = core.saveState()
            core.replaceActiveCheats(listOf(code))
            core.loadState(stateWithoutCheatValue)
            core.runFrame(frame)
            assertEquals(0x2A, ewramByte(core, 0))
        }
    }

    @Test
    fun `le coeur GBA revalide chiffrement commandes adresses et Master Codes`() {
        GbaCore().use { core ->
            core.loadRom(cheatRom(), null)
            listOf(
                "CD93194F 089CE0B",
                "CD93194G 089CE0B4",
                "RAW 40000000 00000000",
                "RAW 01000000 0000002A",
                "RAW 12000001 00001234",
                "RAW D2000010 00001234",
                "RAW 30000002 12345678",
                "RAW DEADFACE 00000000",
            ).forEach { malformed ->
                assertFailsWith<IllegalArgumentException> {
                    core.replaceActiveCheats(listOf(gameSharkCode(malformed)))
                }
            }
            assertFailsWith<IllegalArgumentException> {
                core.replaceActiveCheats(
                    listOf(CheatCode(CheatFormat.GAMESHARK_GB_GBC, "002A00A0"))
                )
            }
        }
    }

    private fun parse(raw: String): CheatCodeListParseResult.Success =
        assertIs(
            CheatParserRegistry.DEFAULT.parseLines(
                CheatFormat.GAMESHARK_GBA_V1_V2,
                raw,
            )
        )

    private fun gameSharkCode(normalized: String) =
        CheatCode(CheatFormat.GAMESHARK_GBA_V1_V2, normalized)

    private fun ewramByte(core: GbaCore, offset: Int): Int =
        core.saveState()[EWRAM_STATE_OFFSET + offset].toInt() and 0xFF

    private fun cheatRom(): ByteArray = SyntheticRom.build(
        programWords = intArrayOf(
            0xE59F0008.toInt(), // LDR r0,[pc,#8] -> 02000000
            0xE3A01011.toInt(), // MOV r1,#11
            0xE5C01000.toInt(), // STRB r1,[r0]
            0xEAFFFFFC.toInt(), // B vers MOV : le jeu rétablit 11 en continu
            0x02000000,
        )
    )

    companion object {
        private const val EWRAM_STATE_OFFSET =
            4 + 2 + 1 + 32 + 16 * 4 + 4 + 1 + 4 + 28 * 4
        private const val IWRAM_STATE_OFFSET = EWRAM_STATE_OFFSET + 0x40000
    }
}
