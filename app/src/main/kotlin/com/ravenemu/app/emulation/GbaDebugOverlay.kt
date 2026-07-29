package com.ravenemu.app.emulation

import android.util.Log
import com.ravenemu.app.BuildConfig
import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.diag.GbaDiagnostics
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.audio.AudioTransportStats

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
     * Texte de la surcouche : la cadence, et le transport audio quand il est
     * relevé.
     *
     * Le relevé audio est inactif hors construction de diagnostic : [stats] rend
     * alors une chaîne vide et la surcouche se réduit à la cadence, comme
     * auparavant. La cadence seule ne suffisait pas à instruire un craquement —
     * elle reste à 60 alors même que la sortie se vide.
     */
    fun render(fps: Double, stats: AudioTransportStats? = null): String {
        val cadence = "%.1f FPS".format(fps)
        val audio = stats?.summary().orEmpty()
        return if (audio.isEmpty()) cadence else "$cadence  $audio"
    }

    /**
     * Journalise un échec de la sortie audio, en Debug uniquement.
     *
     * La session poursuit sans son : sans cette trace, la seule manifestation
     * serait un silence inexpliqué.
     */
    fun logAudioFailure(error: Exception) {
        if (!BuildConfig.DIAGNOSTICS) return
        Log.w(TAG, "sortie audio en échec : ${error.javaClass.simpleName}", error)
    }

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
