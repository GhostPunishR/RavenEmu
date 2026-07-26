package com.ravenemu.app.emulation

import android.util.Log
import com.ravenemu.app.BuildConfig
import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.diag.GbaDiagnostics
import com.ravenemu.core.gba.diag.toDebugText
import com.ravenemu.emulation.api.EmulatorCore

/**
 * Surcouche de débogage Game Boy Advance.
 *
 * En **Debug**, elle enrichit la surcouche de performance de tout ce qui permet
 * de comprendre pourquoi un jeu ne tourne pas : instructions par trame, `PC`,
 * mode ARM ou Thumb, dernier appel logiciel, dernière interruption, `VCOUNT`,
 * DMA en cours, remplissage des FIFO audio, sous-alimentations, et le décompte
 * des anomalies relevées.
 *
 * Elle est pilotée par `BuildConfig.DIAGNOSTICS`, vrai en Debug et dans la
 * variante de mesure « profil », faux en **Release** — où elle se réduit à la
 * cadence et au temps de trame, sans exposer le moindre détail interne, et où
 * la journalisation n'est jamais branchée.
 */
object GbaDebugOverlay {

    private const val TAG = "RavenEmuGba"

    /**
     * Texte de la surcouche pour le [core] courant.
     *
     * Le temps de trame figure dans **toutes** les variantes : c'est la seule
     * mesure qui dit s'il reste de la marge, là où la cadence plafonne à celle
     * de la console dès que le moteur suit. Ce n'est pas un détail interne.
     */
    fun render(core: EmulatorCore?, fps: Double, frameTimeMs: Double): String {
        val basic = "%.1f FPS  %.2f ms/trame".format(fps, frameTimeMs)
        if (!BuildConfig.DIAGNOSTICS) return basic
        val snapshot = (core as? GbaCore)?.debugSnapshot() ?: return basic
        return snapshot.toDebugText(fps, frameTimeMs)
    }

    /**
     * Branche la journalisation des anomalies du moteur, en Debug uniquement.
     *
     * Le moteur bride lui-même le nombre de messages par catégorie : une
     * instruction indéfinie dans une boucle serrée ne produit que quelques
     * lignes, jamais une par instruction. Les compteurs, eux, continuent de
     * progresser et sont lisibles dans la surcouche.
     */
    fun attachLogging(core: EmulatorCore?) {
        val gba = core as? GbaCore ?: return
        if (!BuildConfig.DIAGNOSTICS) {
            gba.onDiagnosticEvent = null
            gba.measuringTime = false
            return
        }
        // Le chronométrage par sous-système accompagne les diagnostics : c'est
        // lui qui dit où passe le temps d'une trame sur l'appareil réel, seul
        // endroit où la question se pose vraiment.
        gba.measuringTime = true
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
