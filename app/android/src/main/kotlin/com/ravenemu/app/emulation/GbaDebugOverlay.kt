package com.ravenemu.app.emulation

import android.util.Log
import com.ravenemu.app.BuildConfig
import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.diag.GbaDiagnostics
import com.ravenemu.core.gba.diag.toDiagnosticText
import com.ravenemu.core.nds.NdsCore
import com.ravenemu.core.nds.diag.toDiagnosticText as toNdsDiagnosticText
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.audio.AudioTransportStats

/**
 * Surcouche affichée par-dessus l'image : **la cadence par défaut**, et le
 * relevé complet du moteur lorsqu'une enquête le demande.
 *
 * Le relevé — registres de composition, dynamique de l'image, pixels produits
 * par chaque couche, cadrage des plans affines, répartition du temps de trame —
 * n'a rien à faire à l'écran quand on joue : un mur de chiffres masque l'image
 * et n'apprend rien à qui ne cherche rien. Il ne se déclenche donc que sur
 * demande explicite, et seulement là où `BuildConfig.DIAGNOSTICS` l'autorise.
 *
 * Ce n'est pas une précaution de style : le relevé allume le chronométrage par
 * sous-système, un incrément par pixel dessiné et un balayage complet de l'image
 * par trame. Le laisser en place fausserait précisément ce qu'il sert à mesurer.
 *
 * Reste branché en permanence, toujours sous `DIAGNOSTICS`, le **journal des
 * anomalies** : il ne coûte rien tant que rien ne va mal, et c'est lui qui
 * signale qu'il se passe quelque chose.
 */
object GbaDebugOverlay {

    private const val TAG = "RavenEmuGba"

    /**
     * Texte de la surcouche.
     *
     * Trois niveaux, du plus discret au plus bavard :
     *
     * - la **cadence** seule, toujours ;
     * - le **transport audio** à sa suite, dès que [audioStats] relève quelque
     *   chose — la cadence ne suffit pas à instruire un craquement, elle reste à
     *   60 alors même que la sortie se vide ;
     * - le **relevé complet du moteur** à la place de la cadence, quand la
     *   mesure est active : registres de composition, dynamique de l'image,
     *   pixels par couche, répartition du temps de trame.
     *
     * Hors construction de diagnostic, il ne reste que la cadence : rien n'est
     * calculé, et aucun détail interne n'est exposé.
     */
    fun render(
        fps: Double,
        frameTimeMs: Double = 0.0,
        core: EmulatorCore? = null,
        audioStats: AudioTransportStats? = null,
        audioTrackUnderruns: Int = 0,
    ): String {
        val transport = audioStats?.summary().orEmpty()
        // La Nintendo DS a son propre relevé, et il ne coûte rien : ses
        // compteurs tournent de toute façon. Il est donc montré dès que la
        // surcouche est demandée, sans attendre qu'une mesure soit allumée —
        // c'est justement quand l'écran reste noir qu'on en a besoin.
        val nds = (core as? NdsCore)
            ?.takeIf { BuildConfig.DIAGNOSTICS }
            ?.debugSnapshot()
            ?.toNdsDiagnosticText(fps, frameTimeMs)
        if (nds != null) {
            return if (transport.isEmpty()) nds else "$nds\n$transport"
        }
        val mesure = (core as? GbaCore)
            ?.takeIf { BuildConfig.DIAGNOSTICS && it.measuringTime }
            ?.debugSnapshot()
            ?.toDiagnosticText(fps, frameTimeMs, audioTrackUnderruns)
        if (mesure != null) {
            return if (transport.isEmpty()) mesure else "$mesure\n$transport"
        }
        val cadence = "%.1f FPS".format(fps)
        return if (transport.isEmpty()) cadence else "$cadence  $transport"
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
     * Branche la journalisation des anomalies du moteur et règle la mesure, en
     * Debug uniquement.
     *
     * Le moteur bride lui-même le nombre de messages par catégorie : une
     * instruction indéfinie dans une boucle serrée ne produit que quelques
     * lignes, jamais une par instruction.
     *
     * [measuring] commande le chronométrage par sous-système, le comptage des
     * pixels par couche et la mesure de la dynamique de l'image. Faux, rien de
     * tout cela n'est calculé.
     */
    fun attachLogging(core: EmulatorCore?, measuring: Boolean = false) {
        val gba = core as? GbaCore ?: return
        gba.measuringTime = measuring && BuildConfig.DIAGNOSTICS
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
