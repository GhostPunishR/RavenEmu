package com.ravenemu.emulation.api

/**
 * Contrat commun à tous les moteurs d'émulation RavenEmu.
 *
 * Un moteur est un objet **passif et mono-thread** : il n'a ni thread, ni
 * horloge, ni callback. L'appelant (la session d'émulation de l'application)
 * pilote la cadence en appelant [runFrame] à la fréquence voulue depuis un
 * unique thread. Pause, reprise et arrêt sont donc du ressort de l'appelant :
 * ne plus appeler [runFrame] suffit à suspendre l'émulation sans perte d'état.
 *
 * Ce découplage garantit le déterminisme (mêmes entrées → même sortie), rend
 * le moteur testable sur JVM et laisse l'intégration Android libre de sa
 * stratégie de synchronisation.
 */
interface EmulatorCore {
    /** Console émulée par ce moteur. */
    val console: ConsoleType

    /** Dimensions natives du framebuffer et cadence théorique. */
    val video: VideoSpec

    /** Format des échantillons produits par [readAudio]. */
    val audio: AudioSpec

    /**
     * Format des valeurs écrites par [runFrame] dans le framebuffer. Par
     * défaut [FramebufferFormat.ARGB_8888] ; un moteur monochrome (Game Boy)
     * produit des niveaux [FramebufferFormat.INDEXED_4] que le renderer
     * colorise via un profil d'écran.
     */
    val framebufferFormat: FramebufferFormat get() = FramebufferFormat.ARGB_8888

    /**
     * Charge une ROM et réinitialise entièrement le moteur.
     *
     * @param rom contenu complet du fichier ROM.
     * @param batteryRam contenu d'une sauvegarde `.sav` à restaurer dans la
     *   RAM de cartouche, ou `null` pour partir d'une RAM vierge. Ignoré si la
     *   cartouche n'a pas de RAM persistante.
     * @throws RomLoadException si la ROM est invalide ou non prise en charge.
     */
    fun loadRom(rom: ByteArray, batteryRam: ByteArray? = null)

    /** Réinitialise la console (équivalent power-cycle), ROM conservée. */
    fun reset()

    /**
     * Exécute exactement une trame vidéo et écrit l'image produite dans
     * [framebuffer], ligne par ligne, au format indiqué par
     * [framebufferFormat] : couleurs ARGB 8888, ou niveaux `0..3` pour un
     * écran monochrome (le renderer applique alors le profil d'écran).
     *
     * @param framebuffer tableau d'au moins [VideoSpec.pixelCount] entiers.
     * @throws IllegalStateException si aucune ROM n'est chargée.
     */
    fun runFrame(framebuffer: IntArray)

    /** Applique l'état d'un bouton. Prend effet dès le prochain cycle émulé. */
    fun setButton(button: EmulatorButton, pressed: Boolean)

    /**
     * Copie au plus `buffer.size` échantillons audio disponibles vers
     * [buffer] et retourne le nombre d'échantillons copiés. Retourne 0 tant
     * que le moteur ne produit pas d'audio (phase audio non livrée ou son
     * désactivé).
     */
    fun readAudio(buffer: ShortArray): Int

    /** `true` si la cartouche chargée possède une RAM sauvegardée par pile. */
    val hasBatteryRam: Boolean

    /**
     * `true` si la RAM de cartouche a été modifiée depuis le dernier
     * [exportBatteryRam]. Permet à l'appelant de ne réécrire le `.sav` que
     * lorsque nécessaire.
     */
    val batteryRamDirty: Boolean

    /**
     * Retourne une copie de la RAM de cartouche persistante au format brut
     * `.sav` (ou `null` sans RAM à pile) et abaisse [batteryRamDirty].
     */
    fun exportBatteryRam(): ByteArray?

    /**
     * Sérialise l'état complet du moteur en un instantané versionné propre à
     * RavenEmu. Le format n'est pas garanti compatible entre consoles ni avec
     * d'autres émulateurs.
     */
    fun saveState(): ByteArray

    /**
     * Restaure un instantané produit par [saveState] pour la même ROM.
     *
     * @throws SaveStateException si l'instantané est illisible, d'une version
     *   non prise en charge ou issu d'une autre ROM.
     */
    fun loadState(state: ByteArray)
}
