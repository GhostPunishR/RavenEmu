package com.ravenemu.core.gba.diag

/**
 * Relevé complet de l'état du moteur, mis en forme pour une enquête.
 *
 * **La surcouche affichée à l'écran ne s'en sert pas** : elle se limite à la
 * cadence, et c'est délibéré — un mur de chiffres gêne quand on joue. Ce relevé
 * est l'outil qu'on rebranche en une ligne quand un jeu se comporte mal, et il
 * est maintenu et testé pour être opérationnel ce jour-là plutôt qu'écrit dans
 * l'urgence.
 *
 * La fonction est ici, dans le moteur, plutôt que dans la couche Android : c'est
 * du texte pur, sans dépendance de plateforme, et il est ainsi couvert par les
 * tests JVM.
 *
 * [fps], [frameTimeMs] et [audioTrackUnderruns] ne viennent pas du moteur :
 * seul l'appelant connaît la cadence et l'état de la sortie Android.
 */
fun GbaDebugSnapshot.toDiagnosticText(
    fps: Double,
    frameTimeMs: Double,
    audioTrackUnderruns: Int = 0,
): String {
    val builder = StringBuilder(256)
    builder.append("%.1f FPS  %.2f ms/trame\n".format(fps, frameTimeMs))
    builder.append("%d instr/trame\n".format(instructionsPerFrame))
    builder.append(
        "PC %08X  %s%s\n".format(
            programCounter,
            if (thumb) "THUMB" else "ARM",
            if (halted) "  (en pause)" else "",
        ),
    )
    builder.append(
        "SWI %s  IRQ %s\n".format(
            if (lastSwi >= 0) "%02X".format(lastSwi) else "—",
            if (lastInterruptMask != 0) "%04X".format(lastInterruptMask) else "—",
        ),
    )
    builder.append("VCOUNT %3d  DMA %s\n".format(vcount, dmaLabel()))
    builder.append(
        "FIFO A %2d  B %2d  vides A %d B %d".format(
            fifoASize,
            fifoBSize,
            fifoAEmptyReads,
            fifoBEmptyReads,
        ),
    )
    builder.append('\n').append(
        "sortie moteur %d  Android %d".format(audioUnderruns, audioTrackUnderruns),
    )
    // Répartition du temps de trame. Le processeur est obtenu par différence :
    // c'est tout ce qui n'est ni composition, ni DMA, ni mixage.
    val measured = ppuMillis + dmaMillis + apuMillis
    if (measured > 0.0) {
        builder.append('\n').append(
            "cpu %.1f  ppu %.1f  dma %.1f  apu %.1f ms".format(
                (frameTimeMs - measured).coerceAtLeast(0.0),
                ppuMillis,
                dmaMillis,
                apuMillis,
            ),
        )
    }
    builder.append('\n').append(videoLine())
    // Zéro partout = mesure inactive : une ligne de zéros n'apprendrait rien et
    // se lirait à tort comme une image noire.
    if (lumaMax > 0) builder.append('\n').append(lumaLine())
    if (layerPixels.isNotEmpty()) builder.append('\n').append(layerPixelLine())
    if ((dispcnt and 0x7) in 1..2) builder.append('\n').append(affineLine())
    if (swiCounts.any { it > 0 }) builder.append('\n').append(swiLine())
    if (hasAnomalies) builder.append('\n').append(anomalyLine())
    return builder.toString()
}

/**
 * Pixels réellement produits par chaque couche activée, à la trame précédente.
 *
 * C'est la mesure qui départage les deux explications d'un élément manquant.
 * Une couche activée qui affiche `0` n'a rien dessiné : ni son décor ni ses
 * tuiles ne sont arrivés là où le rendu les cherche. Une couche qui compte des
 * milliers de pixels alors qu'on ne la voit pas dessine bel et bien, et c'est la
 * composition qui l'efface.
 */
private fun GbaDebugSnapshot.layerPixelLine(): String {
    val builder = StringBuilder(56)
    builder.append("px")
    for (bg in 0 until 4) {
        if (bgEnabled(bg)) builder.append("  BG").append(bg).append(' ').append(layerPixels[bg])
    }
    if (dispcnt and 0x1000 != 0) builder.append("  OBJ ").append(layerPixels[4])
    return builder.toString()
}

/**
 * Cadrage du plan affine `BG2`, seul existant en modes 1 et 2 : point de
 * référence en pixels, échelles horizontale et verticale en virgule fixe 8.8
 * (`0100` = échelle 1), registre de contrôle brut, puis le nombre d'écritures du
 * jeu vers la matrice et vers le point de référence.
 *
 * Un point de référence très éloigné, ou une échelle nulle, suffit à repousser
 * toute la couche hors de l'écran sans que rien d'autre ne le laisse voir. Les
 * deux décomptes disent alors si le jeu a seulement tenté de l'écrire : à zéro,
 * la divergence est dans son déroulement ; non nuls face à une matrice nulle,
 * c'est l'écriture qui se perd.
 */
private fun GbaDebugSnapshot.affineLine(): String =
    "aff BG2 x%d y%d  pa%04X pd%04X  cnt%04X  w%d/%d".format(
        bg2ReferenceX,
        bg2ReferenceY,
        bg2ScaleX and 0xFFFF,
        bg2ScaleY and 0xFFFF,
        bg2Control and 0xFFFF,
        bg2MatrixWrites,
        bg2ReferenceWrites,
    )

/**
 * État des couches d'affichage : mode vidéo, plans actifs avec leur priorité,
 * sprites et leur mode de projection, fenêtres, mélange.
 *
 * C'est la ligne qui répond à « pourquoi cet élément ne s'affiche-t-il pas ? ».
 * Un plan absent de la liste n'est pas activé ; un plan présent mais invisible
 * est masqué par une fenêtre, recouvert par une priorité plus forte, ou effacé
 * par le mélange. Les trois se distinguent d'un coup d'œil.
 */
private fun GbaDebugSnapshot.videoLine(): String {
    val builder = StringBuilder(64)
    builder.append("m").append(dispcnt and 0x7)
    if (dispcnt and 0x0080 != 0) builder.append(" BLANC")
    for (bg in 0 until 4) {
        if (bgEnabled(bg)) builder.append("  BG").append(bg).append('p').append(bgPriority(bg))
    }
    if (dispcnt and 0x1000 != 0) {
        builder.append("  OBJ").append(if (dispcnt and 0x0040 != 0) "1D" else "2D")
    }
    val windows = StringBuilder(3)
    if (dispcnt and 0x2000 != 0) windows.append('0')
    if (dispcnt and 0x4000 != 0) windows.append('1')
    if (dispcnt and 0x8000.toInt() != 0) windows.append('O')
    if (windows.isNotEmpty()) builder.append("  WIN").append(windows)
    if (windows.isNotEmpty()) {
        builder.append(" in%04X out%04X".format(windowInside, windowOutside))
    }
    // Les coefficients comptent autant que le mode : un mélange programmé avec
    // EVA à 16 et EVB à 0 ne change rien, un éclaircissement à EVY 16 blanchit
    // tout. Le mode seul ne dit pas lequel des deux on regarde.
    when ((blendControl ushr 6) and 0x3) {
        1 -> builder.append(
            "  BLD alpha eva%d evb%d".format(blendAlpha and 0x1F, (blendAlpha ushr 8) and 0x1F),
        )
        2 -> builder.append("  BLD clair evy").append(blendBrightness and 0x1F)
        3 -> builder.append("  BLD sombre evy").append(blendBrightness and 0x1F)
    }
    return builder.toString()
}

/**
 * Dynamique de la trame produite par le moteur.
 *
 * C'est la mesure qui situe un voile signalé à l'écran, et elle se lit d'un coup
 * d'œil : sur une image comportant du noir, `min` doit descendre près de zéro.
 * S'il reste haut, le moteur produit déjà l'image délavée et la cause est dans
 * la composition. S'il est à zéro alors que l'écran paraît voilé, le moteur est
 * hors de cause : reste le post-traitement d'affichage et la surface Android.
 */
private fun GbaDebugSnapshot.lumaLine(): String =
    "luma min %d  moy %d  max %d".format(lumaMin, lumaMean, lumaMax)

/**
 * Services du BIOS réellement employés, avec leur nombre d'appels.
 *
 * Le dernier appel ne dit presque rien : c'est le plus fréquent qui l'emporte au
 * moment du relevé. Ce qui est instructif, ce sont les **absents** — un service
 * indispensable à une scène et jamais appelé désigne une branche du programme
 * qui n'est pas prise, donc une divergence bien en amont de l'affichage.
 */
private fun GbaDebugSnapshot.swiLine(): String {
    val builder = StringBuilder(64)
    builder.append("swi")
    for (number in swiCounts.indices) {
        val count = swiCounts[number]
        if (count > 0) builder.append(' ').append("%02X".format(number)).append(':').append(count)
    }
    return builder.toString()
}

private fun GbaDebugSnapshot.dmaLabel(): String = when {
    dmaActive && lastDmaChannel >= 0 -> "actif (canal $lastDmaChannel)"
    lastDmaChannel >= 0 -> "canal $lastDmaChannel"
    else -> "—"
}

/** Résumé des anomalies relevées, en n'affichant que les catégories non nulles. */
private fun GbaDebugSnapshot.anomalyLine(): String {
    val parts = ArrayList<String>(5)
    if (unsupportedSwiCount > 0) parts += "SWI $unsupportedSwiCount"
    if (undefinedInstructionCount > 0) parts += "instr $undefinedInstructionCount"
    if (unsupportedAccessCount > 0) {
        // L'adresse fautive vaut mieux qu'un décompte : elle se retrouve dans
        // le programme, le décompte non.
        parts += "accès $unsupportedAccessCount @%08X".format(firstUnsupportedAddress)
    }
    if (missingInterruptCount > 0) parts += "IRQ absente $missingInterruptCount"
    if (decompressionErrorCount > 0) parts += "décompr. $decompressionErrorCount"
    return "anomalies : " + parts.joinToString("  ")
}
