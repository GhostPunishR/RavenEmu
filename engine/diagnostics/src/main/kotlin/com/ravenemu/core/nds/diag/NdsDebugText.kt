package com.ravenemu.core.nds.diag

/**
 * Met le relevé de la Nintendo DS en quelques lignes lisibles.
 *
 * L'ordre suit la question qu'on se pose devant un écran noir, de la plus
 * grossière à la plus fine : la console avance-t-elle, produit-elle une image,
 * et si oui que lui manque-t-il pour être juste. Une ligne dont tous les
 * compteurs sont à zéro n'est pas écrite : ce qui va bien n'a rien à dire.
 */
fun NdsDebugSnapshot.toDiagnosticText(fps: Double, frameTimeMs: Double): String {
    val lignes = mutableListOf<String>()
    lignes += "NDS %.1f FPS  %.1f ms".format(fps, frameTimeMs)
    lignes += "ARM9 %s %,d instr  pc %08X".format(
        if (mainHalted) "arrêt" else "actif",
        mainInstructions,
        mainProgramCounter,
    )
    lignes += "ARM7 %s %,d instr  pc %08X".format(
        if (secondaryHalted) "arrêt" else "actif",
        secondaryInstructions,
        secondaryProgramCounter,
    )
    lignes += "image %,d px allumés  A %04X  B %04X%s".format(
        nonBlackPixels,
        mainDisplayControl,
        secondaryDisplayControl,
        if (screensSwapped) "  écrans échangés" else "",
    )

    val manques = buildList {
        if (unimplementedLayers > 0) add("plans %,d".format(unimplementedLayers))
        if (unimplementedDisplay > 0) add("modes %,d".format(unimplementedDisplay))
        if (unimplementedObjects > 0) add("sprites %,d".format(unimplementedObjects))
    }
    if (manques.isNotEmpty()) lignes += "non dessiné : " + manques.joinToString("  ")

    val ignores = buildList {
        if (mainUnimplementedIo > 0) {
            add("E/S9 %,d dès %08X".format(mainUnimplementedIo, mainFirstUnimplementedIo))
        }
        if (secondaryUnimplementedIo > 0) {
            add("E/S7 %,d dès %08X".format(secondaryUnimplementedIo, secondaryFirstUnimplementedIo))
        }
        if (cartridgeUnsupported > 0) add("cartouche %,d".format(cartridgeUnsupported))
    }
    if (ignores.isNotEmpty()) lignes += "ignoré : " + ignores.joinToString("  ")

    val fautes = buildList {
        if (mainUndefined > 0) {
            add("indéf.9 %,d dès %08X".format(mainUndefined, mainFirstUndefined))
        }
        if (secondaryUndefined > 0) {
            add("indéf.7 %,d dès %08X".format(secondaryUndefined, secondaryFirstUndefined))
        }
        if (mainUnsupportedSwi > 0) add("BIOS9 %,d".format(mainUnsupportedSwi))
        if (secondaryUnsupportedSwi > 0) add("BIOS7 %,d".format(secondaryUnsupportedSwi))
    }
    if (fautes.isNotEmpty()) lignes += "buté : " + fautes.joinToString("  ")

    return lignes.joinToString("\n")
}
