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
 * En **Release**, `BuildConfig.DEBUG` étant faux, elle se réduit au seul nombre
 * d'images par seconde — aucun détail interne n'est exposé, et la journalisation
 * n'est jamais branchée.
 */
object GbaDebugOverlay {

    private const val TAG = "RavenEmuGba"

    /** Texte de la surcouche pour le [core] courant. */
    fun render(core: EmulatorCore?, fps: Double, frameTimeMs: Double): String {
        val basic = "%.1f FPS".format(fps)
        if (!BuildConfig.DEBUG) return basic
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
        if (!BuildConfig.DEBUG) {
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
