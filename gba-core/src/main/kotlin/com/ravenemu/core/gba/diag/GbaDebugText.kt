package com.ravenemu.core.gba.diag

/**
 * Mise en forme d'une [GbaDebugSnapshot] pour une surcouche de débogage.
 *
 * La fonction est ici, dans le moteur, plutôt que dans la couche Android : c'est
 * du texte pur, sans dépendance de plateforme, et il est ainsi couvert par les
 * tests JVM. La couche applicative n'a plus qu'à afficher la chaîne obtenue.
 *
 * [fps], [frameTimeMs] et [audioTrackUnderruns] ne viennent pas du moteur :
 * seul l'appelant connaît la cadence et l'état de la sortie Android.
 */
fun GbaDebugSnapshot.toDebugText(
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
    if (hasAnomalies) builder.append('\n').append(anomalyLine())
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
