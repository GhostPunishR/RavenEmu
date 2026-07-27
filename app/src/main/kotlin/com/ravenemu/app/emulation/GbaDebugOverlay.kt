package com.ravenemu.app.emulation

import android.util.Log
import com.ravenemu.app.BuildConfig
import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.diag.GbaDiagnostics
import com.ravenemu.emulation.api.EmulatorCore

/**
 * Surcouche affichée par-dessus l'image : **la cadence, et rien d'autre**.
 *
 * Le moteur sait produire un relevé complet de son état — instructions par
 * trame, `PC`, appels du BIOS, pixels produits par chaque couche, cadrage des
 * plans affines. Il a servi à traquer des défauts précis, et reste maintenu et
 * testé dans [com.ravenemu.core.gba.diag.toDiagnosticText]. Mais il n'a rien à
 * faire à l'écran quand on joue : un mur de chiffres masque l'image et n'apprend
 * rien à qui ne mène pas d'enquête. Le rebrancher tient en une ligne, le jour où
 * une enquête reprend.
 *
 * Reste branché en revanche, et seulement là où `BuildConfig.DIAGNOSTICS`
 * l'autorise, le **journal des anomalies** : il ne coûte rien tant que rien ne
 * va mal, et c'est lui qui signale qu'il se passe quelque chose.
 */
object GbaDebugOverlay {

    private const val TAG = "RavenEmuGba"

    /**
     * Texte de la surcouche : la cadence.
     *
     * Identique dans toutes les variantes de construction. Aucun détail interne
     * n'est exposé, et rien n'est calculé pour l'afficher.
     */
    fun render(fps: Double): String = "%.1f FPS".format(fps)

    /**
     * Branche la journalisation des anomalies du moteur, en Debug uniquement.
     *
     * Le moteur bride lui-même le nombre de messages par catégorie : une
     * instruction indéfinie dans une boucle serrée ne produit que quelques
     * lignes, jamais une par instruction.
     *
     * Le chronométrage par sous-système et le comptage des pixels par couche
     * restent **éteints** : ils coûtent des lectures d'horloge et un incrément
     * par pixel dessiné, et plus rien ne les affiche.
     */
    fun attachLogging(core: EmulatorCore?) {
        val gba = core as? GbaCore ?: return
        gba.measuringTime = false
        if (!BuildConfig.DIAGNOSTICS) {
            gba.onDiagnosticEvent = null
            return
        }
        gba.onDiagnosticEvent = { event, detail ->
            val label = when (event) {
                GbaDiagnostics.Event.UNSUPPORTED_SWI -> "appel BIOS non géré"
                GbaDiagnostics.Event.UNDEFINED_INSTRUCTION -> "instruction indéfinie"
                GbaDiagnostics.Event.UNSUPPORTED_ACCESS -> "accès mémoire non géré"
                GbaDiagnostics.Event.MISSING_INTERRUPT -> "interruption attendue absente"
                GbaDiagnostics.Event.DECOMPRESSION_ERROR -> "décompression impossible"
            }
            Log.w(TAG, "$label : $detail")
        }
    }
}
