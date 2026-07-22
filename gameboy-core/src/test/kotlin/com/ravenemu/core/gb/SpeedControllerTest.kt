package com.ravenemu.core.gb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeedControllerTest {

    @Test
    fun `inerte hors mode CGB`() {
        val speed = SpeedController(cgbMode = false)
        assertEquals(0xFF, speed.readKey1())
        speed.writeKey1(0x01)
        assertFalse(speed.onStop())
        assertFalse(speed.doubleSpeed)
        assertEquals(0, speed.peripheralShift)
    }

    @Test
    fun `bascule de vitesse armee puis STOP`() {
        val speed = SpeedController(cgbMode = true)
        assertFalse(speed.doubleSpeed)
        assertEquals(0x7E, speed.readKey1()) // simple vitesse, non armé
        speed.writeKey1(0x01) // arme
        assertEquals(0x7F, speed.readKey1())
        assertTrue(speed.onStop()) // bascule
        assertTrue(speed.doubleSpeed)
        assertEquals(1, speed.peripheralShift)
        assertEquals(0xFE, speed.readKey1()) // double vitesse, désarmé
    }

    @Test
    fun `STOP sans armement ne bascule pas`() {
        val speed = SpeedController(cgbMode = true)
        assertFalse(speed.onStop())
        assertFalse(speed.doubleSpeed)
    }

    @Test
    fun `retour a la vitesse simple`() {
        val speed = SpeedController(cgbMode = true)
        speed.writeKey1(0x01); speed.onStop()
        assertTrue(speed.doubleSpeed)
        speed.writeKey1(0x01); speed.onStop()
        assertFalse(speed.doubleSpeed)
    }
}
