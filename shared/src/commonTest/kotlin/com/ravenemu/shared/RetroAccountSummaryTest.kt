package com.ravenemu.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class RetroAccountSummaryTest {
    private val account = RetroSnapshot(
        status = "Connecté • Mode normal (Softcore)", username = "joueur", displayName = "Joueur",
        softcorePoints = 1200, hardcorePoints = 40,
    )

    @Test
    fun signedInWithAGameShowsPointsSoftcoreFirstAndProgress() {
        val summary = retroAccountSummary(account.copy(
            gameTitle = "Jeu", gameId = 7, unlockedCount = 3, totalCount = 20,
            unlockedPoints = 25, totalPoints = 200, pendingAwards = 1,
        ))
        assertEquals(RetroAccountSummary(
            name = "Joueur",
            points = "Softcore 1200 pts  ·  Hardcore 40 pts",
            game = "3/20 succès  ·  25/200 pts  ·  1 en attente",
            mode = "Softcore",
        ), summary)
    }

    @Test
    fun hardcoreSessionNamesItsMode() {
        assertEquals("Hardcore", retroAccountSummary(account.copy(hardcore = true)).mode)
    }

    @Test
    fun signedInWithoutASetExplainsWhy() {
        assertEquals("Aucun jeu reconnu pour le moment.", retroAccountSummary(account).game)
        assertEquals("Chargement des succès…",
            retroAccountSummary(account.copy(status = "Chargement des succès…")).game)
    }

    @Test
    fun accountWithoutDisplayNameUsesTheUsername() {
        assertEquals("joueur", retroAccountSummary(account.copy(displayName = "")).name)
    }

    @Test
    fun signedOutInvitesToSignInOrKeepsTheServerReason() {
        assertEquals(RetroAccountSummary("Non connecté", "Touchez pour vous connecter", "", ""),
            retroAccountSummary(RetroSnapshot()))
        assertEquals("Connexion en cours…",
            retroAccountSummary(RetroSnapshot(status = "Connexion en cours…", busy = true)).name)
        val refused = "Connexion refusée par RetroAchievements : mot de passe incorrect."
        assertEquals(RetroAccountSummary("Non connecté", refused, "", ""),
            retroAccountSummary(RetroSnapshot(status = refused)))
    }
}
