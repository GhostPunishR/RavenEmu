package com.ravenemu.core.gba.diag

/**
 * Mise en forme d'une [GbaDebugSnapshot] pour une surcouche de débogage.
 *
 * La fonction est ici, dans le moteur, plutôt que dans la couche Android : c'est
 * du texte pur, sans dépendance de plateforme, et il est ainsi couvert par les
 * tests JVM. La couche applicative n'a plus qu'à afficher la chaîne obtenue.
 *
 * [fps] et [frameTimeMs] ne viennent pas du moteur : seul l'appelant sait à
 * quelle cadence il le fait tourner.
 */
fun GbaDebugSnapshot.toDebugText(fps: Double, frameTimeMs: Double): String {
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
        "FIFO A %2d  B %2d  sous-alim. %d".format(fifoASize, fifoBSize, audioUnderruns),
    )
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
    if (unsupportedAccessCount > 0) parts += "accès $unsupportedAccessCount"
    if (missingInterruptCount > 0) parts += "IRQ absente $missingInterruptCount"
    if (decompressionErrorCount > 0) parts += "décompr. $decompressionErrorCount"
    return "anomalies : " + parts.joinToString("  ")
}
