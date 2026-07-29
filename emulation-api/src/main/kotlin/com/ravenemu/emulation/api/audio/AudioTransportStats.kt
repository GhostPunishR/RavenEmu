package com.ravenemu.emulation.api.audio

/**
 * Relevé du transport audio, du moteur jusqu'à la sortie de la plateforme.
 *
 * Un craquement peut naître à trois endroits, et rien ne les distingue à
 * l'oreille : le moteur qui produit un flux discontinu, le rééchantillonnage qui
 * en perd un morceau, ou la sortie système qui se vide entre deux écritures.
 * Chacun laisse ici une trace différente :
 *
 * - [samplesSubmitted] très inférieur à la cadence attendue → le moteur ne
 *   produit pas assez ;
 * - [samplesWritten] inférieur à [samplesResampled] → la sortie refuse une
 *   partie du bloc, et [shortWrites] compte les fois où c'est arrivé ;
 * - [outputUnderruns] et [restarts] qui montent → la sortie se vide plus vite
 *   qu'on ne l'alimente ;
 * - [failures] non nul → la piste est en erreur, [lastFailure] dit laquelle.
 *
 * ### Coût
 * Tout est conditionné à [enabled], laissé à `false` par défaut. Désactivé, un
 * relevé se réduit à la lecture d'un booléen ; aucun compteur n'est touché,
 * aucune chaîne n'est construite. Les compteurs sont des entiers 64 bits :
 * quelques dizaines d'écritures par seconde ne les débordent pas.
 *
 * ### Fils d'exécution
 * Les compteurs sont alimentés par le seul thread d'émulation et lus par
 * l'interface. Les champs sont `@Volatile` pour que la lecture voie une valeur
 * récente ; aucune cohérence d'ensemble n'est promise entre deux compteurs, et
 * aucune décision n'en dépend — ce sont des indicateurs, pas un état.
 */
class AudioTransportStats {

    /** Relevé actif. Faux par défaut : rien n'est compté. */
    @Volatile
    var enabled: Boolean = false

    /** Échantillons entrelacés remis par le moteur à la sortie. */
    @Volatile
    var samplesSubmitted: Long = 0L
        private set

    /** Échantillons entrelacés produits par le rééchantillonnage. */
    @Volatile
    var samplesResampled: Long = 0L
        private set

    /** Échantillons entrelacés effectivement acceptés par la sortie. */
    @Volatile
    var samplesWritten: Long = 0L
        private set

    /** Blocs dont la fin n'a pas pu être écrite : autant de discontinuités. */
    @Volatile
    var shortWrites: Int = 0
        private set

    /** Réamorçages de la piste après une rupture (arrêt, vidage, préremplissage). */
    @Volatile
    var restarts: Int = 0
        private set

    /** Ruptures cumulées rapportées par la plateforme. */
    @Volatile
    var outputUnderruns: Int = 0
        private set

    /** Exceptions levées par la sortie. */
    @Volatile
    var failures: Int = 0
        private set

    /** Description de la dernière exception, ou `null`. */
    @Volatile
    var lastFailure: String? = null
        private set

    /** Un bloc a traversé la chaîne : soumis, rééchantillonné, écrit. */
    fun onBlock(submitted: Int, resampled: Int, written: Int) {
        if (!enabled) return
        samplesSubmitted += submitted.coerceAtLeast(0)
        samplesResampled += resampled.coerceAtLeast(0)
        samplesWritten += written.coerceAtLeast(0)
        if (written < resampled) shortWrites++
    }

    /** La piste a été arrêtée, vidée et repréremplie. */
    fun onRestart() {
        if (!enabled) return
        restarts++
    }

    /** Compteur cumulatif de ruptures rapporté par la plateforme. */
    fun onUnderrunCount(total: Int) {
        if (!enabled) return
        outputUnderruns = maxOf(outputUnderruns, total.coerceAtLeast(0))
    }

    /**
     * La sortie a levé une exception. Le message est conservé tel quel : il
     * vient de la plateforme et ne contient ni chemin ni donnée de l'utilisateur.
     */
    fun onFailure(error: Throwable) {
        if (!enabled) return
        failures++
        lastFailure = "${error.javaClass.simpleName}: ${error.message ?: "sans message"}"
    }

    /**
     * Résumé d'une ligne, destiné à la surcouche de débogage. Vide tant que le
     * relevé est inactif : rien n'est mis en forme pour rien.
     */
    fun summary(): String {
        if (!enabled) return ""
        val perdus = samplesResampled - samplesWritten
        return buildString {
            append("audio ")
            append(samplesSubmitted / 2)
            append('→')
            append(samplesWritten / 2)
            append(" tr")
            if (perdus > 0) append(" -").append(perdus / 2)
            if (shortWrites > 0) append(" tronq:").append(shortWrites)
            if (outputUnderruns > 0) append(" vide:").append(outputUnderruns)
            if (restarts > 0) append(" relance:").append(restarts)
            if (failures > 0) append(" err:").append(failures)
        }
    }

    fun reset() {
        samplesSubmitted = 0L
        samplesResampled = 0L
        samplesWritten = 0L
        shortWrites = 0
        restarts = 0
        outputUnderruns = 0
        failures = 0
        lastFailure = null
    }
}
