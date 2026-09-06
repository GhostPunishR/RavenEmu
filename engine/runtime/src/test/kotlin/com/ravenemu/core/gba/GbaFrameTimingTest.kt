package com.ravenemu.core.gba

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GbaFrameTimingTest {

    @Test
    fun `le depassement de cycles ne s accumule pas entre les trames`() {
        // Cette ROM n'utilise ni DMA ni pause : seule une instruction peut
        // dépasser la frontière, avec un coût très inférieur à cette borne.
        val maximumSingleStepOvershoot = 64
        val machine = GbaMachine(SyntheticRom.build())

        repeat(120) { frame ->
            machine.runFrame()
            val ppuState = machine.ppu.stateFields()
            assertEquals(0, ppuState[0], "trame ${frame + 1} au-delà de sa frontière")
            assertTrue(
                ppuState[1] in 0 until maximumSingleStepOvershoot,
                "dépassement accumulé à la trame ${frame + 1} : ${ppuState[1]} cycles",
            )
        }
    }
}
