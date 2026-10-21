package com.ravenemu.android

import com.ravenemu.android.HardcoreGate.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardcoreGateTest {
    @Test fun `en hardcore, le jeu attend la connexion et l'identification de la ROM`() {
        for (stage in listOf(Stage.SIGNING_IN, Stage.LOADING, Stage.WAITING_NETWORK))
            assertTrue(stage.name, HardcoreGate.holdFrames(hardcoreWanted = true, gameRunning = true, stage = stage))
        // Suivi en cours, ou rien à évaluer (ROM inconnue, refus, pas de compte) : le jeu tourne.
        for (stage in listOf(Stage.IDLE, Stage.TRACKING, Stage.UNTRACKED))
            assertFalse(stage.name, HardcoreGate.holdFrames(hardcoreWanted = true, gameRunning = true, stage = stage))
    }

    @Test fun `en softcore, rien ne retient le jeu`() {
        for (stage in Stage.entries) assertFalse(stage.name, HardcoreGate.holdFrames(false, true, stage))
    }

    // Les nouvelles tentatives suivent la barrière : sans jeu ouvert, aucune ne part.
    @Test fun `sans jeu ouvert, rien n'attend ni ne se relance`() {
        for (stage in Stage.entries) assertFalse(stage.name, HardcoreGate.holdFrames(true, false, stage))
    }

    @Test fun `les nouvelles tentatives s'espacent de 5 a 60 secondes`() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L),
            (0..5).map(HardcoreGate::retryDelayMillis))
        assertEquals(60_000L, HardcoreGate.retryDelayMillis(1_000))
    }
}
