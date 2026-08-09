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

    /**
     * Vrai si le moteur peut avancer une trame complète sans recomposer
     * l'image. Les timings vidéo, interruptions et DMA doivent rester actifs.
     */
    val supportsVideoFrameSkipping: Boolean get() = false

    /**
     * Exécute une trame en demandant ou non sa composition. Le comportement par
     * défaut conserve la compatibilité des moteurs qui rendent toujours.
     */
    fun runFrame(framebuffer: IntArray, renderVideo: Boolean) {
        runFrame(framebuffer)
    }

    /** Applique l'état d'un bouton. Prend effet dès le prochain cycle émulé. */
    fun setButton(button: EmulatorButton, pressed: Boolean)

    /**
     * Copie au plus `buffer.size` échantillons audio disponibles vers
     * [buffer] et retourne le nombre d'échantillons copiés. Retourne 0 tant
     * que le moteur ne produit pas d'audio (phase audio non livrée ou son
     * désactivé).
     */
    fun readAudio(buffer: ShortArray): Int

    /** État instantané du moteur de vibration matériel de la cartouche. */
    val rumbleActive: Boolean get() = false

    /** `true` si la cartouche chargée possède une RAM sauvegardée par pile. */
    val hasBatteryRam: Boolean

    /**
     * `true` si la RAM de cartouche a changé depuis le dernier acquittement.
     * Permet à l'appelant de ne réécrire le `.sav` que lorsque nécessaire.
     */
    val batteryRamDirty: Boolean

    /**
     * Copie de la RAM de cartouche au format brut `.sav`, accompagnée de la
     * génération qui l'identifie — ou `null` sans RAM à pile.
     *
     * **Ne baisse pas** [batteryRamDirty] : la sauvegarde n'est acquittée qu'au
     * retour de [acknowledgeBatteryRamSaved], une fois l'écriture confirmée.
     * Tant que rien n'est confirmé, le moteur continue de se déclarer modifié,
     * et une écriture ratée sera retentée au lieu d'être perdue en silence.
     */
    fun snapshotBatteryRam(): BatteryRamSnapshot?

    /**
     * Acquitte la sauvegarde de la [generation] indiquée.
     *
     * L'acquittement est **ignoré si le jeu a modifié la RAM depuis
     * l'instantané** : la génération courante a alors changé, et abaisser le
     * drapeau perdrait les octets écrits entre-temps.
     */
    fun acknowledgeBatteryRamSaved(generation: Long)

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
