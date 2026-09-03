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

    /**
     * Charge une ROM depuis un descripteur de fichier **déjà ouvert**, sans la
     * faire passer par la mémoire Java.
     *
     * Une cartouche Nintendo DS pèse jusqu'à un demi-gigaoctet. Lue par
     * [loadRom], elle doit tenir entière dans le tas Java, plafonné bien en
     * dessous de ce que l'appareil permettrait : c'est ce plafond, et non le
     * matériel, qui refusait les grosses cartouches.
     *
     * Le descripteur reste la propriété de l'appelant : ce chemin ne le ferme
     * pas, et l'appelant doit le rendre lui-même.
     *
     * @return `false` si ce moteur ne sait pas charger ainsi. L'appelant
     *   retombe alors sur [loadRom], qui reste le chemin de toutes les
     *   consoles dont les cartouches sont petites.
     * @throws RomLoadException si la ROM est invalide ou non prise en charge.
     */
    fun loadRomFromDescriptor(
        descriptor: java.io.FileDescriptor,
        sizeBytes: Long,
        batteryRam: ByteArray? = null,
    ): Boolean = false

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
     * Pose ou lève un contact sur l'écran tactile de la console.
     *
     * Les coordonnées sont **en pixels de l'écran tactile**, l'origine en haut
     * à gauche : c'est à l'appelant de les y ramener depuis l'écran de
     * l'appareil, lui seul sachant comment il a disposé les écrans. Un contact
     * hors de l'écran est ramené sur son bord plutôt qu'ignoré, un doigt qui
     * glisse au-delà d'une lisière étant un geste ordinaire.
     *
     * Sans écran tactile, l'appel ne fait rien : la plupart des consoles n'en
     * ont pas, et leur imposer une implémentation vide n'apprendrait rien.
     */
    fun setTouch(down: Boolean, x: Int, y: Int) {
        // Volontairement sans effet.
    }

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
     * `true` si ce moteur sait enregistrer et relire un instantané.
     *
     * Un moteur encore en construction n'a pas de format d'état : en figer un
     * avant que la console soit complète promettrait une compatibilité que le
     * prochain organe ajouté briserait. L'appelant consulte cette propriété
     * pour ne pas proposer ce qu'il ne peut pas tenir ; appeler quand même
     * [saveState] reste une erreur signalée, non un instantané vide.
     */
    val supportsSaveState: Boolean get() = true

    /**
     * Sérialise l'état complet du moteur en un instantané versionné propre à
     * RavenEmu. Le format n'est pas garanti compatible entre consoles ni avec
     * d'autres émulateurs.
     *
     * @throws IllegalStateException si [supportsSaveState] est faux.
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
